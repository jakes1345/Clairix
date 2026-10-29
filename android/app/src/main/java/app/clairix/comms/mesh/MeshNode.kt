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
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
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
    private val _status: MutableStateFlow<MeshStatus>
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
            _status.update { it.copy(running = true, error = null, carrying = store.size) }
            advertise(true)
            scan(true)
        } catch (e: SecurityException) {
            _status.update { it.copy(error = "Nearby devices permission is needed") }
        }
    }

    fun stop() = exec.execute {
        advertise(false)
        scan(false)
        (serverConns.values + clientConns.values).forEach { end(it) }
        serverConns.clear(); clientConns.clear()
        runCatching { server?.close() }
        server = null
        _status.update { it.copy(running = false, advertising = false, scanning = false, longRange = false) }
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

    private fun advertise(on: Boolean) {
        val adv = manager?.adapter?.bluetoothLeAdvertiser ?: return
        if (longRange) runCatching {
            if (!on) adv.stopAdvertisingSet(longRangeCallback)
            else adv.startAdvertisingSet(
                // Extended, connectable, coded on both channels, as loud as the phone allows.
                AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(true).setScannable(false)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build(),
                serviceOnly(), null, null, null, longRangeCallback
            )
        }.onFailure { e -> Log.i(TAG, "Long-range advertising refused: ${e.message}") }
        runCatching {
            if (!on) { adv.stopAdvertising(advertiseCallback); _status.update { it.copy(advertising = false) }; return }
            adv.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
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

    private fun scan(on: Boolean) {
        val sc = manager?.adapter?.bluetoothLeScanner ?: return
        runCatching {
            if (!on) { sc.stopScan(scanCallback); _status.update { it.copy(scanning = false) }; return }
            // Filtered on the service, so the scan keeps running with the screen off.
            sc.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshProtocol.SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).apply {
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
