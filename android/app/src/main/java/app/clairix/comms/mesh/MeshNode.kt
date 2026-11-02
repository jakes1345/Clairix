package app.clairix.comms.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import app.clairix.analysis.LowPowerMode
import app.clairix.analysis.MotionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the mesh is doing, for the Comms page. */
data class MeshStatus(
    val running: Boolean = false,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val meetings: Int = 0,
    val lastMeetingAt: Long = 0L,
    val carrying: Int = 0,
    val receivedForYou: Int = 0,
    val handedOn: Int = 0,
    /** Whether this phone also advertises and listens on Bluetooth's long-range (coded) radio mode. */
    val longRange: Boolean = false,
    /** Whether this phone's Bluetooth has the long-range mode at all. */
    val longRangeSupported: Boolean = false,
    /** Whether the phone is taken to be still, which pauses the long-range advertisement ([MeshPacing]). */
    val still: Boolean = false,
    /** Whether the radio runs at full pace (something to carry, a conversation open, a meeting lately) or at low power. */
    val fullPace: Boolean = false,
    /** Whether Wi-Fi Aware runs beside Bluetooth ([MeshAware]). */
    val aware: Boolean = false,
    /** Whether this phone could run Wi-Fi Aware: Android 13, the hardware, and the Nearby Wi-Fi devices permission. */
    val awareSupported: Boolean = false,
    val error: String? = null
)

/**
 * The radio half of the mesh, over Bluetooth Low Energy. Every phone with the mesh on
 * is a GATT server (advertising [MeshProtocol.SERVICE_UUID], connectable, no name) and
 * a scanner that connects to the servers it finds, at most one meeting per peer per
 * [PEER_COOLDOWN_MS]. A meeting is one [MeshSession] over one characteristic: the
 * client writes chunks, the server notifies chunks, each one at a time as the stack
 * requires, and the client disconnects when both sides are done or after [MEETING_MS].
 *
 * On a phone whose Bluetooth has the long-range (LE Coded) radio mode, it also
 * advertises a second, extended set on that mode at full power, listens for both, and
 * connects on it to the phones it heard there: at the same power a coded signal carries
 * several times as far, at an eighth of the speed. The ordinary advertisement stays, so
 * phones without the mode still meet this one, and this one still meets them.
 *
 * It does not run flat out for nothing ([MeshPacing]): with nothing to carry, no
 * conversation open and no meeting lately it listens and advertises at low power, and
 * the long-range set pauses while the phone is still. The pace is looked at again
 * whenever a bundle is added or a meeting ends, when the phone starts moving, and every
 * [MeshPacing.REVIEW_MS] while the phone is awake.
 *
 * All Bluetooth callbacks are handed to one thread, which owns every map here.
 * Needs BLUETOOTH_SCAN, BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE; the caller checks.
 */
@SuppressLint("MissingPermission")
class MeshNode(
    context: Context,
    private val store: MeshStore,
    private val ownTags: () -> Set<String>,
    private val onDeliver: (ByteArray) -> Unit,
    /** Where the node reports what it is doing; owned by [Mesh] so the page can watch it before the node exists. */
    private val _status: MutableStateFlow<MeshStatus>,
    /** Whether a conversation is open on screen, when a message may be about to need the mesh. */
    private val conversationOpen: () -> Boolean = { false },
    /** Told the pace after every review: full (true) or low power. */
    private val onPace: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "MeshNode"
        const val PEER_COOLDOWN_MS = 60_000L
        const val MEETING_MS = 30_000L
        private const val MAX_CLIENTS = 2
        private const val WANT_MTU = 517
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mesh").apply { isDaemon = true } }


    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private val serverConns = HashMap<String, Conn>()
    private val clientConns = HashMap<String, Conn>()
    private val lastMet = HashMap<String, Long>()

    /** True when this phone's Bluetooth can both send and hear long-range (coded) advertising. */
    private val longRange: Boolean = manager?.adapter?.let {
        runCatching { it.isLeCodedPhySupported && it.isLeExtendedAdvertisingSupported }.getOrDefault(false)
    } ?: false

    /**
     * Whether the phone is going anywhere, from the significant-motion sensor alone:
     * still once it has not fired for [MeshPacing.STILL_AFTER_MS]. The sensor is armed
     * while the phone is still, as the scanner arms it, and its firing ends the spell.
     * Touched on [exec] only.
     */
    private val motion = MotionPolicy(MeshPacing.STILL_AFTER_MS)
    private val sensors: SensorManager? = app.getSystemService(SensorManager::class.java)
    private val motionSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            exec.execute {
                // One-shot: Android cancelled it as it fired.
                motionArmed = false
                motion.onMotion(System.currentTimeMillis())
                review()
            }
        }
    }
    private var motionArmed = false

    /** What the radio runs now; null while it is not running. Touched on [exec] only. */
    private var plan: MeshPacing.Plan? = null
    private var reviews: ScheduledFuture<*>? = null
    /** A bundle added (the owner's, or one handed over) brings the full pace back at once. */
    private val onStoreChange: () -> Unit = { exec.execute { review() } }

    /** One side of one meeting: its session, its reassembler, and its outgoing chunk queue. */
    private inner class Conn(val device: BluetoothDevice, val initiator: Boolean) {
        var gatt: BluetoothGatt? = null
        var mtu = 23
        var subscribed = false
        val reasm = MeshProtocol.Reassembler()
        val out = ArrayDeque<ByteArray>()
        var busy = false
        var timeout: ScheduledFuture<*>? = null
        val session = MeshSession(
            store, ownTags,
            deliver = { b -> _status.update { it.copy(receivedForYou = it.receivedForYou + 1) }; onDeliver(b.data) },
            send = { frame -> out.addAll(MeshProtocol.chunk(frame, (mtu - 3).coerceAtLeast(20))); pump(this) },
            now = { System.currentTimeMillis() },
            initiator = initiator
        )
    }

    fun start() = exec.execute {
        if (_status.value.running) return@execute
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { _status.update { it.copy(error = "Bluetooth is off") }; return@execute }
        try {
            val s = manager.openGattServer(app, serverCallback)
                ?: run { _status.update { it.copy(error = "This phone would not open a Bluetooth server") }; return@execute }
            val ch = BluetoothGattCharacteristic(
                MeshProtocol.FRAMES_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            ch.addDescriptor(BluetoothGattDescriptor(MeshProtocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            val svc = BluetoothGattService(MeshProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            svc.addCharacteristic(ch)
            s.addService(svc)
            server = s
            characteristic = ch
            _status.update { it.copy(running = true, error = null, carrying = store.size, longRangeSupported = longRange) }
            motion.start(System.currentTimeMillis())
            store.onChange = onStoreChange
            review()
            reviews = exec.scheduleWithFixedDelay({ review() }, MeshPacing.REVIEW_MS, MeshPacing.REVIEW_MS, TimeUnit.MILLISECONDS)
        } catch (e: SecurityException) {
            _status.update { it.copy(error = "Nearby devices permission is needed") }
        }
    }

    fun stop() = exec.execute {
        reviews?.cancel(false)
        reviews = null
        // Only this node's: a node started since has its own.
        if (store.onChange === onStoreChange) store.onChange = null
        motionSensor?.let { sensor -> runCatching { sensors?.cancelTriggerSensor(motionTrigger, sensor) } }
        motionArmed = false
        advertiseOrdinary(false, AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        advertiseLongRange(false)
        scan(false, ScanSettings.SCAN_MODE_BALANCED)
        plan = null
        (serverConns.values + clientConns.values).forEach { end(it) }
        serverConns.clear(); clientConns.clear()
        runCatching { server?.close() }
        server = null
        _status.update { it.copy(running = false, advertising = false, scanning = false, longRange = false, still = false, fullPace = false) }
    }

    // ── Pace ──────────────────────────────────────────────────────────────

    /**
     * Looks at what the mesh has to do and how the phone is moving, and changes the
     * radio when the plan changed ([MeshPacing]). Runs on [exec].
     */
    private fun review() {
        if (!_status.value.running) return
        val now = System.currentTimeMillis()
        // Low power halves the quiet spell before the phone counts as still, and keeps the
        // radio at its quiet pace unless something waits to be carried ([MeshPacing.busy]).
        val pacing = LowPowerMode.pacing
        motion.stillAfterMs = pacing.meshStillAfterMs
        motion.tick(now)
        val sensor = motionSensor
        // Without the sensor nothing would ever say the phone moved again: never still.
        val still = sensor != null && motion.pace == MotionPolicy.Pace.STILL
        if (sensor != null && still != motionArmed) {
            // Armed while still, so moving ends the spell; one-shot, so armed afresh each time.
            motionArmed = if (still) runCatching { sensors?.requestTriggerSensor(motionTrigger, sensor) == true }.getOrDefault(false)
            else false.also { runCatching { sensors?.cancelTriggerSensor(motionTrigger, sensor) } }
        }
        val carrying = store.carrying(now)
        val busy = MeshPacing.busy(
            carrying, runCatching { conversationOpen() }.getOrDefault(false), _status.value.lastMeetingAt, now,
            lowPower = pacing.meshIdle
        )
        val next = MeshPacing.plan(busy, still, longRange)
        val current = plan
        if (next != current) {
            if (current == null || current.scanMode != next.scanMode) scan(true, next.scanMode)
            if (current == null || current.advertiseMode != next.advertiseMode) advertiseOrdinary(true, next.advertiseMode)
            if (current == null || current.longRange != next.longRange) advertiseLongRange(next.longRange)
            plan = next
        }
        _status.update { it.copy(carrying = carrying, still = still, fullPace = busy) }
        onPace(busy)
    }

    // ── Advertising and scanning ──────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { _status.update { it.copy(advertising = true) } }
        override fun onStartFailure(errorCode: Int) { _status.update { it.copy(advertising = false, error = "Advertising failed ($errorCode)") } }
    }

    private val longRangeCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            _status.update { it.copy(longRange = status == ADVERTISE_SUCCESS) }
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) { _status.update { it.copy(longRange = false) } }
    }

    /** The service only, as in the ordinary advertisement. */
    private fun serviceOnly(): AdvertiseData = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(MeshProtocol.SERVICE_UUID)).build()

    /** The long-range set: started or stopped as [MeshPacing] says (never on a phone without the mode). */
    private var longRangeOn = false

    private fun advertiseLongRange(on: Boolean) {
        val adv = manager?.adapter?.bluetoothLeAdvertiser ?: return
        if (!longRange || on == longRangeOn) return
        runCatching {
            if (!on) adv.stopAdvertisingSet(longRangeCallback)
            else adv.startAdvertisingSet(
                // Extended, connectable, coded on both channels, as loud as the phone allows.
                AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(true).setScannable(false)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build(),
                serviceOnly(), null, null, null, longRangeCallback
            )
            longRangeOn = on
        }.onFailure { e -> Log.i(TAG, "Long-range advertising refused: ${e.message}") }
    }

    /** The ordinary advertisement, at [mode]: started, restarted at another mode, or stopped. */
    private fun advertiseOrdinary(on: Boolean, mode: Int) {
        val adv = manager?.adapter?.bluetoothLeAdvertiser ?: return
        runCatching {
            // A running advertisement keeps its settings: a new mode means stopping it first.
            adv.stopAdvertising(advertiseCallback)
            if (!on) { _status.update { it.copy(advertising = false) }; return }
            adv.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(mode)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
                // The service only: no name, no manufacturer data, the address the stack rotates.
                serviceOnly(),
                advertiseCallback
            )
        }.onFailure { e -> _status.update { it.copy(error = "Advertising refused: ${e.message}") } }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { exec.execute { maybeMeet(result.device, heardOnCoded(result)) } }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { exec.execute { results.forEach { maybeMeet(it.device, heardOnCoded(it)) } } }
        override fun onScanFailed(errorCode: Int) { _status.update { it.copy(scanning = false, error = "Scan failed ($errorCode)") } }
    }

    /** The scan, at [mode]: started, restarted at another mode, or stopped. */
    private fun scan(on: Boolean, mode: Int) {
        val sc = manager?.adapter?.bluetoothLeScanner ?: return
        runCatching {
            // A running scan keeps its settings: a new mode means stopping it first.
            sc.stopScan(scanCallback)
            if (!on) { _status.update { it.copy(scanning = false) }; return }
            // Filtered on the service, so the scan keeps running with the screen off.
            sc.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshProtocol.SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(mode).apply {
                    // Extended and coded advertisements as well as ordinary ones.
                    if (longRange) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }.build(),
                scanCallback
            )
            _status.update { it.copy(scanning = true) }
        }.onFailure { e -> _status.update { it.copy(error = "Scan refused: ${e.message}") } }
    }

    // ── Client side: this phone connects ──────────────────────────────────

    private fun heardOnCoded(result: ScanResult): Boolean = longRange && result.primaryPhy == BluetoothDevice.PHY_LE_CODED

    private fun maybeMeet(device: BluetoothDevice, coded: Boolean = false) {
        if (!_status.value.running) return
        val addr = device.address ?: return
        val now = System.currentTimeMillis()
        if (now - (lastMet[addr] ?: 0L) < PEER_COOLDOWN_MS) return
        if (addr in clientConns || addr in serverConns || clientConns.size >= MAX_CLIENTS) return
        lastMet[addr] = now
        val c = Conn(device, initiator = true)
        clientConns[addr] = c
        c.timeout = exec.schedule(Runnable { end(c) }, MEETING_MS, TimeUnit.MILLISECONDS)
        // A phone heard only on the coded mode is likely out of reach of the ordinary one:
        // connect on coded too (the stack picks the faster mode when both work).
        val phy = if (coded) BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK else BluetoothDevice.PHY_LE_1M_MASK
        c.gatt = runCatching { device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE, phy) }.getOrNull()
        if (c.gatt == null) end(c)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = exec.execute {
            val c = clientConns[gatt.device.address] ?: run { runCatching { gatt.close() }; return@execute }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(WANT_MTU)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) end(c)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = exec.execute {
            clientConns[gatt.device.address]?.mtu = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = exec.execute {
            val c = clientConns[gatt.device.address] ?: return@execute
            val ch = gatt.getService(MeshProtocol.SERVICE_UUID)?.getCharacteristic(MeshProtocol.FRAMES_UUID) ?: run { end(c); return@execute }
            gatt.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(MeshProtocol.CCCD_UUID) ?: run { end(c); return@execute }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!ok) end(c)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = exec.execute {
            val c = clientConns[gatt.device.address] ?: return@execute
            if (status != BluetoothGatt.GATT_SUCCESS) { end(c); return@execute }
            c.subscribed = true
            c.session.start()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = exec.execute {
            val c = clientConns[gatt.device.address] ?: return@execute
            c.busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) { end(c); return@execute }
            pump(c)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            exec.execute { clientConns[gatt.device.address]?.let { receive(it, copy) } }
        }

        @Deprecated("Android 12")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            exec.execute { clientConns[gatt.device.address]?.let { receive(it, copy) } }
        }
    }

    // ── Server side: another phone connected to this one ──────────────────

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = exec.execute {
            val addr = device.address ?: return@execute
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (addr in clientConns) return@execute // already meeting it the other way round
                val c = Conn(device, initiator = false)
                serverConns[addr] = c
                c.timeout = exec.schedule(Runnable { end(c) }, MEETING_MS, TimeUnit.MILLISECONDS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConns[addr]?.let { end(it) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = exec.execute { serverConns[device.address]?.mtu = mtu }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) = exec.execute {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
            serverConns[device.address]?.subscribed = true
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val copy = value?.copyOf()
            exec.execute {
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
                val c = serverConns[device.address] ?: return@execute
                if (copy != null) receive(c, copy)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = exec.execute {
            val c = serverConns[device.address] ?: return@execute
            c.busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) { end(c); return@execute }
            pump(c)
        }
    }

    // ── Both sides ────────────────────────────────────────────────────────

    private fun receive(c: Conn, chunk: ByteArray) {
        val frame = try { c.reasm.add(chunk) } catch (e: IllegalArgumentException) { Log.i(TAG, "Bad chunk: ${e.message}"); end(c); return } ?: return
        if (!c.session.onFrame(frame)) { end(c); return }
        _status.update { it.copy(carrying = store.size) }
        finishIfDone(c)
    }

    /** Sends the next queued chunk, if nothing is in flight. */
    private fun pump(c: Conn) {
        if (c.busy) return
        val next = c.out.removeFirstOrNull() ?: run { finishIfDone(c); return }
        c.busy = true
        val ok = try {
            if (c.initiator) {
                val gatt = c.gatt ?: return
                val ch = gatt.getService(MeshProtocol.SERVICE_UUID)?.getCharacteristic(MeshProtocol.FRAMES_UUID) ?: return
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ch.value = next
                        gatt.writeCharacteristic(ch)
                    }
                }
            } else {
                val s = server ?: return
                val ch = characteristic ?: return
                if (!c.subscribed) { c.out.addFirst(next); c.busy = false; return }
                if (Build.VERSION.SDK_INT >= 33) {
                    s.notifyCharacteristicChanged(c.device, ch, false, next) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        ch.value = next
                        s.notifyCharacteristicChanged(c.device, ch, false)
                    }
                }
            }
        } catch (e: SecurityException) { false }
        if (!ok) end(c)
    }

    private fun finishIfDone(c: Conn) {
        if (!c.session.finished || c.busy || c.out.isNotEmpty()) return
        _status.update {
            it.copy(meetings = it.meetings + 1, lastMeetingAt = System.currentTimeMillis(),
                handedOn = it.handedOn + c.session.forwarded, carrying = store.size)
        }
        // The side that connected hangs up; the other just forgets the meeting.
        if (c.initiator) end(c) else { c.timeout?.cancel(false); serverConns.remove(c.device.address) }
        // Other phones are about: full pace for a while.
        review()
    }

    private fun end(c: Conn) {
        c.timeout?.cancel(false)
        val addr = c.device.address
        if (c.initiator) {
            clientConns.remove(addr)
            runCatching { c.gatt?.disconnect() }
            runCatching { c.gatt?.close() }
        } else {
            serverConns.remove(addr)
            runCatching { server?.cancelConnection(c.device) }
        }
    }
}
