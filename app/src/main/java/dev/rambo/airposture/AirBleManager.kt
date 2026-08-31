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
import java.io.File

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
    private var probe: AirGattProbe? = null

    // The logger monitor also orders session rotation against admitted BLE work.
    fun scanAndConnect(timeoutMs: Long = 12_000): Unit = synchronized(diagnostics) {
        stopScan()
        val expectedSessionId = diagnostics.currentSessionId()
        if (!adapter.isEnabled) {
            record("scan_error", mapOf("reason" to "bluetooth_disabled"), "Bluetooth is disabled", expectedSessionId)
            return@synchronized
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult): Unit = synchronized(diagnostics) {
                if (!isActiveScan(this, expectedSessionId)) return@synchronized
                val advertised = result.scanRecord?.deviceName.orEmpty()
                val connectedName = runCatching { result.device.name.orEmpty() }.getOrDefault("")
                val name = if (advertised.isNotBlank()) advertised else connectedName
                val token = diagnostics.bleAddressToken(result.device.address)
                if (!diagnostics.event(
                    "scan_result",
                    mapOf(
                        "device_token" to token,
                        "name" to name,
                        "rssi" to result.rssi,
                        "connectable" to result.isConnectable,
                        "service_uuids" to (result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList<String>()),
                        "advertisement_hex" to (result.scanRecord?.bytes?.toHex() ?: ""),
                    ),
                    expectedSessionId,
                )) return@synchronized
                onLog("seen name='$name' id=$token rssi=${result.rssi}")

                if (!isActiveScan(this, expectedSessionId)) return@synchronized
                if (name.contains("fitbit", ignoreCase = true) || name.contains("air", ignoreCase = true)) {
                    if (!record(
                        "candidate_selected",
                        mapOf("device_token" to token, "name" to name, "rssi" to result.rssi),
                        "candidate selected: $name id=$token",
                        expectedSessionId,
                    )) return@synchronized
                    connect(result.device, token, expectedSessionId, this)
                }
            }

            override fun onScanFailed(errorCode: Int): Unit = synchronized(diagnostics) {
                if (isActiveScan(this, expectedSessionId)) {
                    record("scan_error", mapOf("error_code" to errorCode), "BLE scan failed code=$errorCode", expectedSessionId)
                }
            }
        }
        callback = cb
        if (!record("scan_start", mapOf("timeout_ms" to timeoutMs), "BLE scan started", expectedSessionId) ||
            !isActiveScan(cb, expectedSessionId)) return@synchronized
        scanner.startScan(cb)
        // A synchronous platform callback can stop this request before startScan returns.
        if (!isActiveScan(cb, expectedSessionId)) {
            runCatching { scanner.stopScan(cb) }
            return@synchronized
        }
        handler.postDelayed({
            synchronized(diagnostics) {
                if (isActiveScan(cb, expectedSessionId)) {
                    record("scan_timeout", mapOf("timeout_ms" to timeoutMs), "BLE scan timeout", expectedSessionId)
                    if (callback === cb) stopScan()
                }
            }
        }, timeoutMs)
    }

    fun stopScan(): Unit = synchronized(diagnostics) {
        val previous = callback
        callback = null
        previous?.let { runCatching { scanner.stopScan(it) } }
    }

    fun stop(): Unit = synchronized(diagnostics) {
        stopScan()
        closeConnection()
    }

    fun startNewSession(): File = synchronized(diagnostics) {
        stop()
        diagnostics.startNewSession()
    }

    private fun closeConnection() {
        probe?.stop()
        probe = null
        val previous = gatt
        gatt = null
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
    }

    private fun isActiveScan(origin: ScanCallback, expectedSessionId: String): Boolean =
        callback === origin && diagnostics.isCurrentSession(expectedSessionId)

    private fun connect(device: BluetoothDevice, token: String, expectedSessionId: String, origin: ScanCallback) {
        if (!isActiveScan(origin, expectedSessionId)) return
        if (!record("connect_start", mapOf("device_token" to token), "connecting id=$token", expectedSessionId)) return
        if (!isActiveScan(origin, expectedSessionId)) return
        closeConnection()
        if (!isActiveScan(origin, expectedSessionId)) return
        val nextProbe = AirGattProbe(diagnostics, token, expectedSessionId, onLog)
        probe = nextProbe
        stopScan()
        if (probe !== nextProbe || !nextProbe.isActive()) return
        val connected = device.connectGatt(
            appContext,
            false,
            nextProbe,
            BluetoothDevice.TRANSPORT_LE,
        )
        if (probe === nextProbe && nextProbe.isActive()) {
            gatt = connected
        } else {
            // A reentrant callback may stop/rotate before connectGatt returns ownership.
            runCatching { connected?.disconnect() }
            runCatching { connected?.close() }
        }
    }

    private fun record(type: String, fields: Map<String, Any?>, message: String, expectedSessionId: String): Boolean {
        if (!diagnostics.event(type, fields, expectedSessionId)) return false
        onLog(message)
        return true
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
