package dev.rambo.airposture

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * GATT inventory + notification logger for a Fitbit Air you own.
 *
 * The only write performed here is the standard Client Characteristic
 * Configuration Descriptor (CCCD) required to subscribe to notifications.
 * No proprietary characteristic or command payload is written.
 */
@SuppressLint("MissingPermission")
class AirGattProbe(
    private val diagnostics: DiagnosticsLogger,
    private val deviceToken: String,
    private val expectedSessionId: String,
    private val onLog: (String) -> Unit,
) : BluetoothGattCallback() {
    private var active = true

    internal fun stop(): Unit = synchronized(diagnostics) { active = false }

    internal fun isActive(): Boolean = synchronized(diagnostics) {
        active && diagnostics.isCurrentSession(expectedSessionId)
    }

    private inline fun <T> whileActive(operation: () -> T): T? = synchronized(diagnostics) {
        if (isActive()) operation() else null
    }

    companion object {
        private const val TAG = "AirGattProbe"
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (!emit(
            "gatt_connection",
            mapOf("status" to status, "state" to newState),
            "connection status=$status state=$newState id=$deviceToken",
        )) return
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            whileActive { runCatching { gatt.requestMtu(247) } }
            whileActive { gatt.discoverServices() }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        emit("gatt_mtu", mapOf("mtu" to mtu, "status" to status), "MTU=$mtu status=$status")
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!emit(
            "gatt_services_discovered",
            mapOf("status" to status, "count" to gatt.services.size),
            "services discovered status=$status count=${gatt.services.size}",
        )) return
        gatt.services.forEach { logService(it) }
        subscribeFirstNotifiable(gatt)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (!emit(
            "gatt_cccd_write",
            mapOf("characteristic_uuid" to descriptor.characteristic.uuid.toString(), "status" to status),
            "CCCD write ${descriptor.characteristic.uuid} status=$status",
        )) return
        subscribeFirstNotifiable(gatt, afterUuid = descriptor.characteristic.uuid)
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        emitNotification(characteristic.uuid, characteristic.value)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        emitNotification(characteristic.uuid, value)
    }

    private fun logService(service: BluetoothGattService) {
        emit(
            "gatt_service",
            mapOf("service_uuid" to service.uuid.toString(), "characteristic_count" to service.characteristics.size),
            "SERVICE ${service.uuid}",
        )
        service.characteristics.forEach { c ->
            val descriptors = c.descriptors.map { it.uuid.toString() }
            emit(
                "gatt_characteristic",
                mapOf(
                    "service_uuid" to service.uuid.toString(),
                    "characteristic_uuid" to c.uuid.toString(),
                    "properties" to c.properties,
                    "properties_hex" to "0x${c.properties.toString(16)}",
                    "descriptors" to descriptors,
                ),
                "  CHAR ${c.uuid} props=0x${c.properties.toString(16)} descriptors=$descriptors",
            )
        }
    }

    private fun subscribeFirstNotifiable(gatt: BluetoothGatt, afterUuid: UUID? = null): Unit = whileActive {
        val chars = gatt.services.flatMap { it.characteristics }.filter { c ->
            (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) &&
                c.getDescriptor(CCCD) != null
        }
        val start = if (afterUuid == null) 0 else chars.indexOfFirst { it.uuid == afterUuid } + 1
        if (start !in chars.indices) {
            emit("gatt_subscriptions_complete", mapOf("count" to chars.size), "notification subscriptions complete")
            return@whileActive
        }
        val c = chars[start]
        val descriptor = c.getDescriptor(CCCD) ?: return@whileActive
        if (!isActive()) return@whileActive
        val localAccepted = gatt.setCharacteristicNotification(c, true)
        val enableValue = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        // Platform calls and onLog can synchronously reenter stop/session rotation.
        if (!isActive()) return@whileActive
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, enableValue) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = enableValue
                gatt.writeDescriptor(descriptor)
            }
        }
        emit(
            "gatt_subscribe",
            mapOf(
                "characteristic_uuid" to c.uuid.toString(),
                "set_local_accepted" to localAccepted,
                "descriptor_write_accepted" to accepted,
            ),
            "subscribe ${c.uuid} local=$localAccepted accepted=$accepted",
        )
        if (!accepted) subscribeFirstNotifiable(gatt, c.uuid)
    } ?: Unit

    private fun emitNotification(uuid: UUID, value: ByteArray) {
        val hex = value.toHex()
        emit(
            "gatt_notify",
            mapOf(
                "characteristic_uuid" to uuid.toString(),
                "length" to value.size,
                "value_hex" to hex,
            ),
            "NOTIFY $uuid len=${value.size} $hex",
        )
    }

    private fun emit(type: String, fields: Map<String, Any?>, msg: String): Boolean = whileActive {
        if (!diagnostics.event(type, mapOf("device_token" to deviceToken) + fields, expectedSessionId)) {
            return@whileActive false
        }
        Log.i(TAG, msg)
        onLog(msg)
        isActive()
    } ?: false
}
