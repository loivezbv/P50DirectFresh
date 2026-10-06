package com.ddoolive.p50direct

import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater

class MainActivity : FlutterActivity() {
    private val channel = "ddoolive/p50_bt"
    private val serviceUuid = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val rxUuid = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val txUuid = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    private val cxUuid = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var credits = 1

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            if (Build.VERSION.SDK_INT >= 31 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 100)
                result.error("PERMISSION", "블루투스 권한을 허용한 뒤 다시 눌러줘", null)
                return@setMethodCallHandler
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { result.error("NO_BT", "블루투스를 지원하지 않는 기기", null); return@setMethodCallHandler }
            when (call.method) {
                "pairedDevices" -> result.success(adapter.bondedDevices.map {
                    mapOf("name" to (it.name ?: "기기"), "address" to it.address)
                })
                "connect" -> {
                    val address = call.argument<String>("address") ?: return@setMethodCallHandler result.error("ADDR","주소 없음",null)
                    Thread {
                        try {
                            val latch = CountDownLatch(1)
                            var error: String? = null
                            runOnUiThread { try { gatt?.close() } catch (_: Exception) {} }
                            val cb = object : BluetoothGattCallback() {
                                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) { error = "BLE 연결 실패 ($status)"; latch.countDown() }
                                }
                                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                                    val svc = g.getService(serviceUuid)
                                    tx = svc?.getCharacteristic(txUuid)
                                    val rx = svc?.getCharacteristic(rxUuid)
                                    val cx = svc?.getCharacteristic(cxUuid)
                                    if (tx == null) { error = "P50S FF02 특성을 찾지 못함"; latch.countDown(); return }
                                    tx!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                    for (ch in listOfNotNull(rx, cx)) {
                                        g.setCharacteristicNotification(ch, true)
                                        ch.getDescriptor(cccdUuid)?.let { d ->
                                            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
                                        }
                                    }
                                    credits = 1
                                    latch.countDown()
                                }
                                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                                    if (characteristic.uuid == cxUuid && value.size >= 2 && value[0].toInt() == 1) credits += value[1].toInt() and 0xff
                                }
                                @Deprecated("Deprecated in Java")
                                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                                    @Suppress("DEPRECATION") val v = characteristic.value ?: return
                                    onCharacteristicChanged(g, characteristic, v)
                                }
                            }
                            val dev = adapter.getRemoteDevice(address)
                            gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(this, false, cb)
                            if (!latch.await(12, TimeUnit.SECONDS)) throw Exception("BLE 연결 시간 초과")
                            if (error != null) throw Exception(error)
                            runOnUiThread { result.success(true) }
                        } catch (e: Exception) { runOnUiThread { result.error("BT_ERROR", e.message, null) } }
                    }.start()
                }
                "printTest" -> Thread {
                    try {
                        val g = gatt ?: throw Exception("먼저 P50S 연결 필요")
                        val ch = tx ?: throw Exception("P50S 출력 채널 없음")
                        val widthBytes = 48
                        val height = 160
                        val raw = ByteArray(widthBytes * height)
                        for (y in 0 until height) for (x in 0 until widthBytes) {
                            val border = y < 8 || y >= height-8 || x == 0 || x == widthBytes-1
                            val bars = y in 40 until 120 && ((x/2)%2==0)
                            raw[y*widthBytes+x] = if (border || bars) 0xff.toByte() else 0
                        }
                        val d = Deflater()
                        d.setInput(raw); d.finish()
                        val tmp = ByteArray(raw.size + 512)
                        val n = d.deflate(tmp); d.end()
                        val data = tmp.copyOf(n)
                        fun writePacket(bytes: ByteArray) {
                            var off = 0
                            while (off < bytes.size) {
                                val end = minOf(off + 95, bytes.size)
                                val p = bytes.copyOfRange(off, end)
                                var waits = 0
                                while (credits <= 0 && waits++ < 34) Thread.sleep(30)
                                if (credits <= 0) credits = 1
                                if (Build.VERSION.SDK_INT >= 33) {
                                    val rc = g.writeCharacteristic(ch, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                                    if (rc != BluetoothStatusCodes.SUCCESS) throw Exception("BLE 쓰기 실패 $rc")
                                } else {
                                    @Suppress("DEPRECATION") ch.value = p
                                    @Suppress("DEPRECATION") if (!g.writeCharacteristic(ch)) throw Exception("BLE 쓰기 실패")
                                }
                                credits--
                                off = end
                                Thread.sleep(30)
                            }
                        }
                        writePacket(byteArrayOf(0x1f,0x70,0x02,0x03))
                        writePacket(byteArrayOf(0x1f,0xc0.toByte(),0x01,0x00))
                        writePacket(byteArrayOf(0x1f,0x11,0x51))
                        val h = byteArrayOf(0x1f,0x10,
                            ((widthBytes shr 8) and 255).toByte(),(widthBytes and 255).toByte(),
                            ((height shr 8) and 255).toByte(),(height and 255).toByte(),
                            ((n shr 24) and 255).toByte(),((n shr 16) and 255).toByte(),((n shr 8) and 255).toByte(),(n and 255).toByte())
                        writePacket(h + data)
                        writePacket(byteArrayOf(0x1f,0x12,0x20,0x00))
                        writePacket(byteArrayOf(0x1f,0xc0.toByte(),0x01,0x01))
                        writePacket(byteArrayOf(0x1f,0x11,0x50))
                        runOnUiThread { result.success(true) }
                    } catch (e: Exception) { runOnUiThread { result.error("BT_ERROR", e.message, null) } }
                }.start()
                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() { try { gatt?.close() } catch (_: Exception) {}; super.onDestroy() }
}
