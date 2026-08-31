package dev.rambo.airposture

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt

/** Real manager/probe/logger; only Android Bluetooth and app storage are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [EffectGattShadow::class])
class BleLifecycleTest {
    @Test fun transitionAfterConnectLogPreventsTheOldConnection() {
        val f = Fixture()
        f.logHook = { if (it.startsWith("connecting id=")) f.transition() }
        f.scanResult()
        assertTrue("connectGatt must not start after the transition completed", f.connections().isEmpty())
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun transitionAfterConnectionLogPreventsMtuAndDiscovery() {
        val f = Fixture()
        val gatt = f.connect()
        val platform = effect(gatt)
        f.logHook = { if (it.startsWith("connection status=")) f.transition() }
        callback(gatt).onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        assertEquals(listOf("disconnect", "close"), platform.effects)
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun transitionDuringLocalNotificationSetupPreventsTheDescriptorWrite() {
        val f = Fixture()
        val gatt = f.connect()
        val platform = effect(gatt)
        platform.discoverableServices += service()
        platform.notificationHook = { f.transition() }
        callback(gatt).onServicesDiscovered(gatt, 0)
        assertEquals(listOf("local_notification", "disconnect", "close"), platform.effects)
        assertTrue("No CCCD write may begin after transition", platform.writes.isEmpty())
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun stopWithoutRotationAlsoRevokesTheOldProbe() {
        val f = Fixture()
        val gatt = f.connect()
        val platform = effect(gatt)
        f.logHook = { if (it.startsWith("connection status=")) f.manager.stop() }
        callback(gatt).onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        assertEquals(listOf("disconnect", "close"), platform.effects)
        val contents = f.logger.currentLogFile()!!.readText(Charsets.UTF_8)
        callback(gatt).onMtuChanged(gatt, 247, 0)
        assertEquals("Stopped probes must also stop recording", contents,
            f.logger.currentLogFile()!!.readText(Charsets.UTF_8))
    }

    @Test fun transitionDuringMtuPreventsDiscovery() {
        val f = Fixture()
        val gatt = f.connect()
        effect(gatt).mtuHook = { f.transition() }
        callback(gatt).onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        assertEquals(listOf("mtu", "disconnect", "close"), effect(gatt).effects)
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun connectionReturnedAfterReentrantTransitionIsClosedAndNotRetained() {
        val f = Fixture()
        shadowOf(f.device).setGattConnectionInterceptor { f.transition() }
        val returned = f.connect()
        assertEquals(listOf("disconnect", "close"), effect(returned).effects)
        callback(returned).onConnectionStateChange(returned, 0, BluetoothProfile.STATE_CONNECTED)
        f.manager.stop()
        assertEquals("Stale connection must not be retained or used", listOf("disconnect", "close"),
            effect(returned).effects)
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun stoppedScanCallbacksCannotConnectEvenWithoutSessionRotation() {
        val f = Fixture()
        val old = f.scanCallback()
        f.manager.stop()
        old.onScanResult(0, ScanResult(f.device, null, -45, 10L))
        assertTrue(f.connections().isEmpty())
        val fresh = f.connect()
        f.manager.stop()
        assertEquals(listOf("disconnect", "close"), effect(fresh).effects)
    }

    @Test fun transitionFromScanStartLogDoesNotStartTheOldScan() {
        val f = Fixture()
        f.logHook = { if (it == "BLE scan started") f.transition() }
        f.manager.scanAndConnect()
        assertTrue(f.scanCallbacks().isEmpty())
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun transitionWaitsForAdmittedConnectAndThenClosesItsReturnedGatt() {
        val f = Fixture()
        crossTransition(f, { pause -> shadowOf(f.device).setGattConnectionInterceptor { pause() } }) {
            f.scanResult()
        }
        assertEquals(listOf("disconnect", "close"), effect(f.connections().single()).effects)
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun transitionWaitsForAdmittedMtuAndNoWorkStartsAfterItsClose() {
        val f = Fixture()
        val gatt = f.connect()
        crossTransition(f, { pause -> effect(gatt).mtuHook = pause }) {
            callback(gatt).onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        }
        val effects = effect(gatt).effects.toList()
        assertEquals("mtu", effects.first())
        assertEquals(listOf("disconnect", "close"), effects.takeLast(2))
        val finished = effects.toList()
        callback(gatt).onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        assertEquals(finished, effect(gatt).effects)
        assertEquals(listOf("session_start"), f.eventTypes())
    }

    @Test fun aNewExplicitScanStillConnectsAndWritesOnlyTheStandardCccd() {
        val f = Fixture()
        f.transition()
        val gatt = f.connect()
        val platform = effect(gatt)
        callback(gatt).onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        platform.discoverableServices += service()
        callback(gatt).onServicesDiscovered(gatt, 0)
        assertEquals(listOf("mtu", "discover", "local_notification", "descriptor"), platform.effects)
        assertEquals(listOf(CCCD to listOf<Byte>(1, 0)), platform.writes)
        f.logger.event("marker_neutral")
        assertTrue(f.eventTypes().contains("marker_neutral"))
        assertFalse(f.logger.currentLogFile()!!.readText(Charsets.UTF_8).contains(ADDRESS))
        f.manager.stop()
        assertEquals(listOf("disconnect", "close"), platform.effects.takeLast(2))
    }

    private class Fixture {
        private val root = File(System.getProperty("fitbit.test.outputDir", System.getProperty("java.io.tmpdir")),
            "ble-lifecycle-${UUID.randomUUID()}").apply { check(mkdirs()) }
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val logger = DiagnosticsLogger(context).apply { startNewSession() }
        var logHook: (String) -> Unit = {}
        val manager = AirBleManager(context, logger) { logHook(it) }
        private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            .also { shadowOf(it).setEnabled(true) }
        val device: BluetoothDevice = ShadowBluetoothDevice.newInstance(ADDRESS)
            .also { shadowOf(it).setName("Fitbit synthetic") }

        fun transition() { manager.startNewSession() }
        fun scanCallbacks() = shadowOf(adapter.bluetoothLeScanner).scanCallbacks
        fun scanCallback(): android.bluetooth.le.ScanCallback {
            manager.scanAndConnect()
            return scanCallbacks().single()
        }
        fun scanResult() { scanCallback().onScanResult(0, ScanResult(device, null, -45, 10L)) }
        fun connections(): List<BluetoothGatt> = shadowOf(device).bluetoothGatts
        fun connect(): BluetoothGatt { scanResult(); return connections().last() }
        fun eventTypes(): List<String> = logger.currentLogFile()!!.readLines(Charsets.UTF_8)
            .filter(String::isNotBlank).map { org.json.JSONObject(it).getString("type") }
    }

    /** Latches pause a real platform call; thread state only observes the contended monitor. */
    private fun crossTransition(f: Fixture, installPause: (() -> Unit) -> Unit, operation: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptingTransition = CountDownLatch(1)
        val completedTransition = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        installPause {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release platform boundary" }
        }
        val worker = Thread {
            try { operation() } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true }
        val transition = Thread {
            attemptingTransition.countDown()
            try { f.transition() } catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally { completedTransition.countDown() }
        }.apply { isDaemon = true }
        try {
            worker.start()
            assertTrue("platform call was not reached", entered.await(5, TimeUnit.SECONDS))
            transition.start()
            assertTrue(attemptingTransition.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (transition.state != Thread.State.BLOCKED && completedTransition.count != 0L &&
                System.nanoTime() < deadline) Thread.yield()
            assertEquals("transition must wait for the admitted operation", Thread.State.BLOCKED, transition.state)
            assertEquals(1L, completedTransition.count)
        } finally {
            release.countDown()
            worker.join(5_000)
            if (transition.state != Thread.State.NEW) transition.join(5_000)
        }
        assertFalse("callback did not finish", worker.isAlive)
        assertFalse("transition did not finish", transition.isAlive)
        failure.get()?.let { throw AssertionError("controlled worker failed", it) }
    }

    companion object {
        private const val ADDRESS = "02:00:00:00:00:01"
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private fun effect(gatt: BluetoothGatt): EffectGattShadow = Shadow.extract(gatt)
        private fun callback(gatt: BluetoothGatt): BluetoothGattCallback = effect(gatt).gattCallback
        private fun service(): BluetoothGattService = BluetoothGattService(UUID.randomUUID(), 0).apply {
            addCharacteristic(BluetoothGattCharacteristic(UUID.randomUUID(),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ).apply {
                addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
                addDescriptor(BluetoothGattDescriptor(UUID.randomUUID(), BluetoothGattDescriptor.PERMISSION_WRITE))
            })
        }
    }
}

/** Records platform effects, without reproducing any production admission/session logic. */
@Implements(BluetoothGatt::class)
class EffectGattShadow : ShadowBluetoothGatt() {
    val effects = mutableListOf<String>()
    val writes = mutableListOf<Pair<UUID, List<Byte>>>()
    val discoverableServices = mutableListOf<BluetoothGattService>()
    var notificationHook: () -> Unit = {}
    var mtuHook: () -> Unit = {}

    @Implementation public override fun requestMtu(mtu: Int): Boolean { effects += "mtu"; mtuHook(); return true }
    @Implementation public override fun discoverServices(): Boolean { effects += "discover"; return true }
    @Implementation public override fun getServices(): List<BluetoothGattService> = discoverableServices
    @Implementation public override fun setCharacteristicNotification(c: BluetoothGattCharacteristic, enable: Boolean): Boolean {
        effects += "local_notification"
        notificationHook()
        return true
    }
    @Implementation public override fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Int {
        effects += "descriptor"
        writes += d.uuid to value.toList()
        return 0
    }
    @Implementation public override fun disconnect() { effects += "disconnect" }
    @Implementation public override fun close() { effects += "close" }
}
