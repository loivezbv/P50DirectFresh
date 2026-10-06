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

class MainActivity : FlutterActivity() {
    private val channel = "ddoolive/p50_bt"
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            if (Build.VERSION.SDK_INT >= 31 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
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
                        adapter.cancelDiscovery()
                        socket!!.connect()
                        result.success(true)
                    }
                    "printTest" -> {
                        val s = socket ?: throw Exception("먼저 P50 연결 필요")
                        val esc = byteArrayOf(0x1B, 0x40)
                        val text = "P50 DIRECT TEST\nBluetooth SPP OK\n\n\n".toByteArray(Charsets.US_ASCII)
                        s.outputStream.write(esc + text)
                        s.outputStream.flush()
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
