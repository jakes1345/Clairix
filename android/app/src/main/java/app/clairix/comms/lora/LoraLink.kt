package app.clairix.comms.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import app.clairix.comms.mesh.MeshProtocol
import app.clairix.comms.mesh.MeshStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The LoRa bridge as the Offline mesh page shows it. */
data class LoraStatus(
    val state: State = State.OFF,
    /** The paired radio's Bluetooth name, as it was when chosen. */
    val radioName: String? = null,
    /** The radio's Meshtastic node number, once it has said. */
    val nodeNum: Int = 0,
    val packetsSent: Int = 0,
    val bundlesHeard: Int = 0,
    val receivedForYou: Int = 0,
    val lastHeardAt: Long = 0L,
    val error: String? = null,
) {
    enum class State { OFF, CONNECTING, CONFIGURING, READY, WAITING }
}

/**
 * The phone's link to a Meshtastic LoRa radio over Bluetooth: bundles the phone
 * carries go out over LoRa, and Clairix bundles heard over LoRa come in to the same
 * store the Bluetooth mesh fills, so a message can cross kilometres between phones
 * that never come near each other.
 *
 * The radio does the LoRa side (its own channel, encryption and rebroadcasting);
 * Clairix only writes Meshtastic ToRadio packets on [MeshtasticProto.CLAIRIX_PORT]
 * and reads FromRadio. Sequence, per the Meshtastic client API: connect, ask for an
 * MTU of 512, write want_config_id, read FromRadio until it comes back empty,
 * subscribe to FromNum, and read FromRadio again whenever FromNum notifies.
 *
 * Android allows one GATT operation at a time per connection, so every write, read
 * and descriptor write goes through [ops]. Everything runs on one thread.
 */
@SuppressLint("MissingPermission")
class LoraLink(
    context: Context,
    private val address: String,
    private val store: MeshStore,
    private val ownTags: () -> Set<String>,
    private val onDeliver: (ByteArray) -> Unit,
    private val status: MutableStateFlow<LoraStatus>,
    private val planner: LoraPlanner = LoraPlanner(),
) {
    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "clairix-lora").apply { isDaemon = true } }
    private val random = SecureRandom()
    private val reassembler = LoraFrames.Reassembler()

    private sealed interface Op {
        class Write(val bytes: ByteArray) : Op
        data object Read : Op
        data object Subscribe : Op
    }

    private val ops = ArrayDeque<Op>()
    private var busy = false
    private var gatt: BluetoothGatt? = null
    private var configNonce = 0
    private var configured = false
    private var myNode = 0
    private var stopped = false
    private var backoffMs = MIN_BACKOFF_MS
    private var tickTask: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null

    fun start() = exec.execute { stopped = false; connect() }

    fun stop() {
        exec.execute {
            stopped = true
            tickTask?.cancel(false); retryTask?.cancel(false)
            close()
            status.update { it.copy(state = LoraStatus.State.OFF) }
        }
        exec.shutdown()
    }

    private fun connect() {
        if (stopped) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { retry("Bluetooth is off"); return }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run { retry("not a Bluetooth address"); return }
        status.update { it.copy(state = LoraStatus.State.CONNECTING, error = null) }
        gatt = try {
            device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to connect to the radio", e)
            null
        }
        if (gatt == null) retry("could not connect")
    }

    private fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        ops.clear(); busy = false; configured = false
    }

    /** Drops the connection and tries again later, waiting longer each time up to [MAX_BACKOFF_MS]. */
    private fun retry(why: String) {
        close()
        tickTask?.cancel(false)
        if (stopped) return
        Log.i(TAG, "LoRa radio: $why; retrying in ${backoffMs / 1000}s")
        status.update { it.copy(state = LoraStatus.State.WAITING, error = why) }
        retryTask?.cancel(false)
        retryTask = exec.schedule(Runnable { connect() }, backoffMs, TimeUnit.MILLISECONDS)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    // ── One GATT operation at a time ──────────────────────────────────────

    private fun enqueue(op: Op) { ops.addLast(op); next() }

    private fun next() {
        if (busy) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        val service = g.getService(MeshtasticProto.SERVICE_UUID) ?: run { retry("not a Meshtastic radio"); return }
        busy = true
        val refused = try {
            when (op) {
                is Op.Write -> write(g, service, op.bytes)
                Op.Read -> requestRead(g, service)
                Op.Subscribe -> subscribe(g, service)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission for the radio", e)
            "no Bluetooth permission"
        }
        if (refused != null) retry(refused)
    }

    /** Reads FromRadio; returns why it couldn't, or null once the read is under way. */
    private fun requestRead(g: BluetoothGatt, service: BluetoothGattService): String? {
        val ch = service.getCharacteristic(MeshtasticProto.FROM_RADIO_UUID) ?: return "radio has no FromRadio"
        return if (g.readCharacteristic(ch)) null else "radio refused a read"
    }

    /** Writes [bytes] to ToRadio; returns why it couldn't, or null once the write is under way. */
    private fun write(g: BluetoothGatt, service: BluetoothGattService, bytes: ByteArray): String? {
        val ch = service.getCharacteristic(MeshtasticProto.TO_RADIO_UUID) ?: return "radio has no ToRadio"
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        return if (ok) null else "radio refused a write"
    }

    /** Turns on FromNum notifications; returns why it couldn't, or null once the descriptor write is under way. */
    private fun subscribe(g: BluetoothGatt, service: BluetoothGattService): String? {
        val ch = service.getCharacteristic(MeshtasticProto.FROM_NUM_UUID) ?: return "radio has no FromNum"
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return "radio's FromNum can't notify"
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        return if (ok) null else "radio refused notifications"
    }

    private fun done() { busy = false; next() }

    // ── Radio callbacks ───────────────────────────────────────────────────

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = exec.execute {
            if (g != gatt) { runCatching { g.close() }; return@execute }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(WANT_MTU)) g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> retry("radio disconnected")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = exec.execute {
            if (g == gatt) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = exec.execute {
            if (g != gatt) return@execute
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(MeshtasticProto.SERVICE_UUID) == null) {
                retry("not a Meshtastic radio"); return@execute
            }
            beginConfig()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = exec.execute {
            if (g != gatt) return@execute
            if (status != BluetoothGatt.GATT_SUCCESS) { retry(writeError(status)); return@execute }
            done()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val copy = value.copyOf()
            exec.execute { if (g == gatt) read(copy, status) }
        }

        @Deprecated("Android 12")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val copy = ch.value?.copyOf() ?: ByteArray(0)
            exec.execute { if (g == gatt) read(copy, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = exec.execute {
            if (g != gatt) return@execute
            if (status != BluetoothGatt.GATT_SUCCESS) { retry(writeError(status)); return@execute }
            backoffMs = MIN_BACKOFF_MS
            this@LoraLink.status.update { it.copy(state = LoraStatus.State.READY, error = null, nodeNum = myNode) }
            tickTask?.cancel(false)
            tickTask = exec.scheduleWithFixedDelay(Runnable { tick() }, 5, TICK_S, TimeUnit.SECONDS)
            done()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            exec.execute { if (g == gatt && ch.uuid == MeshtasticProto.FROM_NUM_UUID) enqueue(Op.Read) }
        }

        @Deprecated("Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            exec.execute { if (g == gatt && ch.uuid == MeshtasticProto.FROM_NUM_UUID) enqueue(Op.Read) }
        }
    }

    /** Pairing comes up the first time the radio's protected characteristics are used. */
    private fun writeError(status: Int): String = when (status) {
        GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> "pair with the radio: enter the PIN it shows (123456 on radios without a screen)"
        else -> "radio error $status"
    }

    private fun beginConfig() {
        configured = false
        configNonce = random.nextInt(Int.MAX_VALUE - 1) + 1
        status.update { it.copy(state = LoraStatus.State.CONFIGURING) }
        enqueue(Op.Write(MeshtasticProto.wantConfig(configNonce)))
        enqueue(Op.Read)
    }

    private fun read(value: ByteArray, gattStatus: Int) {
        if (gattStatus != BluetoothGatt.GATT_SUCCESS) { retry(writeError(gattStatus)); return }
        if (value.isEmpty()) {
            // The radio's queue is empty. The first time, after the config download, subscribe.
            if (configured && status.value.state == LoraStatus.State.CONFIGURING) enqueue(Op.Subscribe)
            // Empty before config_complete_id: the radio is still gathering its state. Ask again shortly.
            else if (!configured) exec.schedule(Runnable { if (gatt != null) enqueue(Op.Read) }, CONFIG_POLL_MS, TimeUnit.MILLISECONDS)
            done()
            return
        }
        when (val f = MeshtasticProto.parseFromRadio(value)) {
            is MeshtasticProto.FromRadio.MyInfo -> myNode = f.nodeNum
            is MeshtasticProto.FromRadio.ConfigComplete -> if (f.id == configNonce) configured = true
            is MeshtasticProto.FromRadio.Queue -> planner.radioQueueFull = f.free == 0
            is MeshtasticProto.FromRadio.Packet -> heard(f)
            MeshtasticProto.FromRadio.Rebooted -> { done(); beginConfig(); return }
            MeshtasticProto.FromRadio.Other, null -> Unit
        }
        enqueue(Op.Read)
        done()
    }

    private fun heard(p: MeshtasticProto.FromRadio.Packet) {
        if (p.port != MeshtasticProto.CLAIRIX_PORT || (myNode != 0 && p.from == myNode)) return
        val now = System.currentTimeMillis()
        val b = reassembler.add(p.payload, now) ?: return
        planner.heard(b)
        status.update { it.copy(bundlesHeard = it.bundlesHeard + 1, lastHeardAt = now) }
        if (MeshProtocol.hex(b.tag) in ownTags()) {
            if (store.markDelivered(b, now)) {
                status.update { it.copy(receivedForYou = it.receivedForYou + 1) }
                runCatching { onDeliver(b.data) }.onFailure { Log.w(TAG, "delivery failed", it) }
            }
        } else {
            store.add(b, now)
        }
    }

    /** Sends what the planner allows of what the phone carries. */
    private fun tick() {
        if (status.value.state != LoraStatus.State.READY) return
        val now = System.currentTimeMillis()
        for (b in planner.pick(store.snapshot(now), now)) {
            val parts = LoraFrames.split(b)
            for (part in parts) {
                enqueue(Op.Write(MeshtasticProto.broadcast(part, MeshtasticProto.CLAIRIX_PORT, random.nextInt(Int.MAX_VALUE - 1) + 1, hopLimit = HOP_LIMIT)))
            }
            planner.sent(b, parts.size, now)
            status.update { it.copy(packetsSent = it.packetsSent + parts.size) }
        }
    }

    companion object {
        private const val TAG = "LoraLink"
        private const val WANT_MTU = 512
        private const val TICK_S = 60L
        private const val CONFIG_POLL_MS = 250L
        /** Meshtastic's default: each packet is passed on at most three times. */
        private const val HOP_LIMIT = 3
        private const val MIN_BACKOFF_MS = 10_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private val CCCD_UUID = MeshProtocol.CCCD_UUID

        /**
         * Lists Meshtastic radios in Bluetooth range, by the service they advertise,
         * until the returned function is called. [found] is called on the main thread
         * with each radio's address and name.
         */
        fun scan(context: Context, found: (address: String, name: String) -> Unit): () -> Unit {
            val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return {}
            val seen = HashSet<String>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val addr = result.device?.address ?: return
                    if (!seen.add(addr)) return
                    val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: addr
                    found(addr, name)
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshtasticProto.SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            return try {
                scanner.startScan(listOf(filter), settings, cb)
                ({ runCatching { scanner.stopScan(cb) }; Unit })
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission to scan for radios", e)
                val nothingToStop: () -> Unit = {}
                nothingToStop
            }
        }

        /** Asks Android to pair with the radio, which shows the PIN prompt; Meshtastic radios require it. */
        fun pair(context: Context, address: String) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
            try {
                if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
            } catch (e: SecurityException) {
                Log.i(TAG, "pairing not permitted", e)
            }
        }
    }
}
