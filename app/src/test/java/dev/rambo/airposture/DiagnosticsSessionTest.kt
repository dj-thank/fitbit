package dev.rambo.airposture

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.content.ContextWrapper
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.shadows.ShadowSystemClock

/** Exercises production logging/callbacks; Android is simulated and no radio is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DiagnosticsSessionTest {
    @Test fun deviceTokensAreStableWithinSessionAndChangeInTheNextSession() {
        val logger = newLogger()
        val firstFile = logger.startNewSession()
        val firstToken = logger.bleAddressToken(ADDRESS_A)
        assertEquals(firstToken, logger.bleAddressToken(ADDRESS_A))
        assertNotEquals(firstToken, logger.bleAddressToken(ADDRESS_B))
        logger.event("synthetic_device", mapOf("device_token" to firstToken))

        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val secondFile = logger.startNewSession()
        val secondToken = logger.bleAddressToken(ADDRESS_A)
        logger.event("synthetic_device", mapOf("device_token" to secondToken))

        assertNotEquals(firstFile.name, secondFile.name)
        assertNotEquals("A new diagnostic session must use a different token", firstToken, secondToken)
        assertEquals(secondToken, logger.bleAddressToken(ADDRESS_A))
        assertNoRawAddresses(firstFile)
        assertNoRawAddresses(secondFile)
    }

    @Test fun anOldGattCallbackCannotWriteIntoTheNewSessionButFreshCallbacksStillWork() {
        val logger = newLogger()
        val firstFile = logger.startNewSession()
        val oldToken = logger.bleAddressToken(ADDRESS_A)
        val oldProbe = AirGattProbe(logger, oldToken, logger.currentSessionId()) {}
        notify(oldProbe, 0x11)
        assertEquals(listOf("11"), notifications(firstFile).map { it.getString("value_hex") })

        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val secondFile = logger.startNewSession()
        notify(oldProbe, 0x22)
        logger.event("marker_neutral")
        val freshToken = logger.bleAddressToken(ADDRESS_A)
        val freshProbe = AirGattProbe(logger, freshToken, logger.currentSessionId()) {}
        notify(freshProbe, 0x33)

        val secondNotifications = notifications(secondFile)
        assertEquals("Old-session data must not be relabeled into the new session", listOf("33"),
            secondNotifications.map { it.getString("value_hex") })
        assertEquals(freshToken, secondNotifications.single().getString("device_token"))
        assertTrue(events(secondFile).any { it.getString("type") == "marker_neutral" })
        assertFalse(secondFile.readText(Charsets.UTF_8).contains(oldToken))
        assertNoRawAddresses(secondFile)
    }

    @Test fun aDeferredProbeKeepsTheSessionOfItsOriginalScan() {
        val logger = newLogger()
        logger.startNewSession()
        val scanSession = logger.currentSessionId()
        val scanToken = logger.bleAddressToken(ADDRESS_A)
        val secondFile = logger.startNewSession()

        val deferredProbe = AirGattProbe(logger, scanToken, scanSession) {}
        notify(deferredProbe, 0x55)
        val freshProbe = AirGattProbe(logger, logger.bleAddressToken(ADDRESS_A), logger.currentSessionId()) {}
        notify(freshProbe, 0x66)

        assertEquals(listOf("66"), notifications(secondFile).map { it.getString("value_hex") })
        assertFalse(secondFile.readText(Charsets.UTF_8).contains(scanToken))
        assertNoRawAddresses(secondFile)
    }

    @Test @Suppress("DEPRECATION")
    fun oldGattStatusAndLegacyNotificationsCannotEnterTheNewSession() {
        val logger = newLogger()
        logger.startNewSession()
        val messages = mutableListOf<String>()
        val oldProbe = AirGattProbe(logger, logger.bleAddressToken(ADDRESS_A), logger.currentSessionId(), messages::add)
        val gatt = ShadowBluetoothGatt.newInstance(ShadowBluetoothDevice.newInstance(ADDRESS_A))
        val characteristic = BluetoothGattCharacteristic(
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(descriptor)
        characteristic.value = byteArrayOf(0x44)

        val secondFile = logger.startNewSession()
        oldProbe.onMtuChanged(gatt, 247, 0)
        oldProbe.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
        oldProbe.onServicesDiscovered(gatt, 0)
        oldProbe.onDescriptorWrite(gatt, descriptor, 0)
        oldProbe.onCharacteristicChanged(gatt, characteristic)
        logger.event("marker_walk")

        assertEquals(listOf("session_start", "marker_walk"), events(secondFile).map { it.getString("type") })
        assertTrue(messages.isEmpty())
        assertNoRawAddresses(secondFile)
    }

    @Test fun oldScanCallbacksAreDiscardedAndANewExplicitScanCanStillRecord() {
        val context = newContext()
        val logger = DiagnosticsLogger(context)
        logger.startNewSession()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = shadowOf(adapter.bluetoothLeScanner)
        val manager = AirBleManager(context, logger) {}
        // These scanner calls run exclusively in Robolectric's in-memory Android shadow.
        manager.scanAndConnect()
        val oldCallback = scanner.scanCallbacks.single()

        val secondFile = logger.startNewSession()
        oldCallback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        manager.scanAndConnect()
        val freshCallback = scanner.scanCallbacks.single()
        freshCallback.onScanFailed(ScanCallback.SCAN_FAILED_ALREADY_STARTED)
        manager.stop()

        val errors = events(secondFile).filter { it.getString("type") == "scan_error" }
        assertEquals(listOf(ScanCallback.SCAN_FAILED_ALREADY_STARTED), errors.map { it.getInt("error_code") })
        assertNoRawAddresses(secondFile)
    }

    private fun newLogger(): DiagnosticsLogger = DiagnosticsLogger(newContext())

    private fun newContext(): ContextWrapper {
        val root = File(
            System.getProperty("fitbit.test.outputDir", System.getProperty("java.io.tmpdir")),
            "air-posture-session-test-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    private fun notify(probe: AirGattProbe, byte: Int) {
        val gatt = ShadowBluetoothGatt.newInstance(ShadowBluetoothDevice.newInstance(ADDRESS_A))
        val characteristic = BluetoothGattCharacteristic(
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        probe.onCharacteristicChanged(gatt, characteristic, byteArrayOf(byte.toByte()))
    }

    private fun events(file: File): List<JSONObject> =
        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map(::JSONObject)

    private fun notifications(file: File): List<JSONObject> =
        events(file).filter { it.getString("type") == "gatt_notify" }

    private fun assertNoRawAddresses(file: File) {
        val contents = file.readText(Charsets.UTF_8)
        assertFalse(contents.contains(ADDRESS_A))
        assertFalse(contents.contains(ADDRESS_B))
    }

    companion object {
        private const val ADDRESS_A = "02:00:00:00:00:01"
        private const val ADDRESS_B = "02:00:00:00:00:02"
    }
}
