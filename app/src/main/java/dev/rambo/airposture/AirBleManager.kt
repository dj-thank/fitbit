package dev.rambo.airposture

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper

@SuppressLint("MissingPermission")
class AirBleManager(
    context: Context,
    private val diagnostics: DiagnosticsLogger,
    private val onLog: (String) -> Unit,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var callback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null

    fun scanAndConnect(timeoutMs: Long = 12_000) {
        stopScan()
        if (!adapter.isEnabled) {
            record("scan_error", mapOf("reason" to "bluetooth_disabled"), "Bluetooth is disabled")
            return
        }
        record("scan_start", mapOf("timeout_ms" to timeoutMs), "BLE scan started")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertised = result.scanRecord?.deviceName.orEmpty()
                val connectedName = runCatching { result.device.name.orEmpty() }.getOrDefault("")
                val name = if (advertised.isNotBlank()) advertised else connectedName
                val token = diagnostics.bleAddressToken(result.device.address)
                diagnostics.event(
                    "scan_result",
                    mapOf(
                        "device_token" to token,
                        "name" to name,
                        "rssi" to result.rssi,
                        "connectable" to result.isConnectable,
                        "service_uuids" to (result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList<String>()),
                        "advertisement_hex" to (result.scanRecord?.bytes?.toHex() ?: ""),
                    ),
                )
                onLog("seen name='$name' id=$token rssi=${result.rssi}")

                if (name.contains("fitbit", ignoreCase = true) || name.contains("air", ignoreCase = true)) {
                    record(
                        "candidate_selected",
                        mapOf("device_token" to token, "name" to name, "rssi" to result.rssi),
                        "candidate selected: $name id=$token",
                    )
                    stopScan()
                    connect(result.device, token)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                record("scan_error", mapOf("error_code" to errorCode), "BLE scan failed code=$errorCode")
            }
        }
        callback = cb
        scanner.startScan(cb)
        handler.postDelayed({
            if (callback === cb) {
                record("scan_timeout", mapOf("timeout_ms" to timeoutMs), "BLE scan timeout")
                stopScan()
            }
        }, timeoutMs)
    }

    fun stopScan() {
        callback?.let { runCatching { scanner.stopScan(it) } }
        callback = null
    }

    fun stop() {
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun connect(device: BluetoothDevice, token: String) {
        record("connect_start", mapOf("device_token" to token), "connecting id=$token")
        gatt?.close()
        gatt = device.connectGatt(
            appContext,
            false,
            AirGattProbe(diagnostics, token, onLog),
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    private fun record(type: String, fields: Map<String, Any?>, message: String) {
        diagnostics.event(type, fields)
        onLog(message)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
