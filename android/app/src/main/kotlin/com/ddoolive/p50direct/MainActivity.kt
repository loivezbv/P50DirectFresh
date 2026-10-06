package com.ddoolive.p50direct

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import java.util.zip.Deflater

class MainActivity : FlutterActivity() {
    private val channel = "ddoolive/p50_bt"
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

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
            if (adapter == null) {
                result.error("NO_BT", "블루투스를 지원하지 않는 기기", null)
                return@setMethodCallHandler
            }
            try {
                when (call.method) {
                    "pairedDevices" -> result.success(adapter.bondedDevices.map {
                        mapOf("name" to (it.name ?: "기기"), "address" to it.address)
                    })
                    "connect" -> {
                        val address = call.argument<String>("address") ?: throw Exception("주소 없음")
                        socket?.close()
                        socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(spp)
                        if (Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                            adapter.cancelDiscovery()
                        }
                        socket!!.connect()
                        result.success(true)
                    }
                    "printTest" -> {
                        val s = socket ?: throw Exception("먼저 P50 연결 필요")
                        val widthBytes = 48
                        val height = 160
                        val raw = ByteArray(widthBytes * height)
                        for (y in 0 until height) {
                            for (x in 0 until widthBytes) {
                                val border = y < 8 || y >= height - 8 || x == 0 || x == widthBytes - 1
                                val bars = y in 40 until 120 && ((x / 2) % 2 == 0)
                                raw[y * widthBytes + x] = if (border || bars) 0xFF.toByte() else 0x00
                            }
                        }
                        val deflater = Deflater()
                        deflater.setInput(raw)
                        deflater.finish()
                        val tmp = ByteArray(raw.size + 256)
                        val n = deflater.deflate(tmp)
                        deflater.end()
                        val data = tmp.copyOf(n)
                        fun send(bytes: ByteArray) { s.outputStream.write(bytes); s.outputStream.flush(); Thread.sleep(80) }
                        send(byteArrayOf(0x1F,0x70,0x02,0x03))
                        send(byteArrayOf(0x1F,0xC0.toByte(),0x01,0x00))
                        send(byteArrayOf(0x1F,0x11,0x51))
                        val header = byteArrayOf(
                            0x1F,0x10,
                            ((widthBytes shr 8) and 0xFF).toByte(),(widthBytes and 0xFF).toByte(),
                            ((height shr 8) and 0xFF).toByte(),(height and 0xFF).toByte(),
                            ((n shr 24) and 0xFF).toByte(),((n shr 16) and 0xFF).toByte(),
                            ((n shr 8) and 0xFF).toByte(),(n and 0xFF).toByte()
                        )
                        send(header + data)
                        send(byteArrayOf(0x1F,0x12,0x20,0x00))
                        send(byteArrayOf(0x1F,0xC0.toByte(),0x01,0x01))
                        send(byteArrayOf(0x1F,0x11,0x50))
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("BT_ERROR", e.message ?: e.javaClass.simpleName, null)
            }
        }
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
