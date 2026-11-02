package app.clairix.comms.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import app.clairix.comms.mesh.MeshProtocol
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * This phone's Bluetooth connection to its LoRa radio: the phone is the GATT client,
 * the radio the server ([LoraLinkProtocol]).
 *
 * Pairing: the radio advertises its service only for two minutes after its button is
 * pressed (or while no phone is bonded), so [scan] finds it only then. [bond] asks
 * Android to bond, and Android asks for the six digits on the radio's screen (on a
 * radio with no screen, the XIAO, it bonds without them, which a nearby attacker
 * could intercept: pair that one where nobody else is). Once bonded the radio
 * advertises nothing that names it, from an address that changes, and only a bonded
 * phone resolves it; [connect] then reaches it by its bonded identity with
 * auto-connect, so Android reconnects whenever the two come back in range.
 *
 * Every Bluetooth callback is handed to one thread, which owns all state here and
 * calls the [Listener].
 */
@SuppressLint("MissingPermission")
class LoraRadioLink(context: Context, private val listener: Listener) {

    interface Listener {
        /** The link is up, encrypted and subscribed: frames can go both ways. */
        fun onReady()
        fun onFrame(frame: LoraLinkProtocol.Frame)
        fun onDown(reason: String?)
    }

    /** A radio heard advertising while its pairing window is open. */
    data class Found(val address: String, val name: String, val rssi: Int, val bonded: Boolean)

    companion object {
        private const val TAG = "LoraRadioLink"
        private const val WANT_MTU = 517
        private val CCCD = MeshProtocol.CCCD_UUID
        /** How long a connection may take to come up before it is started again. */
        private const val SETUP_MS = 45_000L
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lora-link").apply { isDaemon = true } }

    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var mtu = 23
    @Volatile private var ready = false
    private var busy = false
    private val out = ArrayDeque<ByteArray>()
    private var reasm = MeshProtocol.Reassembler(maxFrame = 4_096)
    private var setupTimer: ScheduledFuture<*>? = null

    private var scanCallback: ScanCallback? = null
    private var bondReceiver: BroadcastReceiver? = null

    val isReady: Boolean get() = ready

    // ── Finding and bonding ───────────────────────────────────────────────

    /** Looks for radios with their pairing window open; [onFound] is called on the link's thread. */
    fun scan(onFound: (Found) -> Unit) = exec.execute {
        stopScanLocked()
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return@execute
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: "Clairix radio"
                val f = Found(d.address, name, result.rssi, d.bondState == BluetoothDevice.BOND_BONDED)
                exec.execute { onFound(f) }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(LoraLinkProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (runCatching { scanner.startScan(listOf(filter), settings, cb) }.isSuccess) scanCallback = cb
    }

    fun stopScan() = exec.execute { stopScanLocked() }

    private fun stopScanLocked() {
        val cb = scanCallback ?: return
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        scanCallback = null
    }

    /**
     * Bonds with the radio at [address]; Android shows its own dialog for the six
     * digits. [done] gets true once bonded, false if it failed or was turned down.
     */
    fun bond(address: String, done: (Boolean) -> Unit) = exec.execute {
        stopScanLocked()
        val device = runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull() ?: run { done(false); return@execute }
        if (device.bondState == BluetoothDevice.BOND_BONDED) { done(true); return@execute }
        bondReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_BONDING) return
                exec.execute {
                    runCatching { app.unregisterReceiver(this) }
                    if (bondReceiver === this) bondReceiver = null
                    done(state == BluetoothDevice.BOND_BONDED)
                }
            }
        }
        bondReceiver = receiver
        ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        if (!device.createBond()) {
            runCatching { app.unregisterReceiver(receiver) }
            bondReceiver = null
            done(false)
        }
    }

    /** Removes Android's bond with the radio, so it is forgotten on this side too. */
    fun unbond(address: String) = exec.execute {
        val device = runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull() ?: return@execute
        // removeBond is hidden API on every Android version this app supports; when it is
        // refused the owner removes the radio in Android's Bluetooth settings instead.
        runCatching { device.javaClass.getMethod("removeBond").invoke(device) }
            .onFailure { Log.i(TAG, "removeBond refused: ${it.message}") }
    }

    fun bonded(address: String): Boolean =
        runCatching { manager?.adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    // ── The connection ────────────────────────────────────────────────────

    /** Connects to the bonded radio at [address] and keeps reconnecting until [disconnect]. */
    fun connect(address: String) = exec.execute {
        if (this.address == address && gatt != null) return@execute
        closeLocked(null)
        this.address = address
        openLocked()
    }

    fun disconnect() = exec.execute {
        address = null
        closeLocked(null)
    }

    fun shutdown() {
        exec.execute {
            stopScanLocked()
            bondReceiver?.let { runCatching { app.unregisterReceiver(it) } }
            bondReceiver = null
            address = null
            closeLocked(null)
        }
        exec.shutdown()
    }

    /** Queues a whole frame; false when the link is not up (nothing is queued then). */
    fun send(frame: ByteArray): Boolean {
        if (!ready) return false
        exec.execute {
            if (!ready) return@execute
            out.addAll(MeshProtocol.chunk(frame, (mtu - 3).coerceAtLeast(20)))
            pump()
        }
        return true
    }

    private fun openLocked() {
        val addr = address ?: return
        val device = runCatching { manager?.adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
        // Auto-connect: Android watches for the bonded radio and connects whenever it is in range.
        gatt = runCatching { device.connectGatt(app, true, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        armSetupTimer()
    }

    /** Connected to a radio, its MTU raised, but its notifications never switched on. */
    private fun setupStalled(): Boolean = !ready && address != null && gatt != null && mtu != 23

    private fun armSetupTimer() {
        setupTimer?.cancel(false)
        setupTimer = exec.schedule({
            // Connected but never subscribed (a stale bond, a radio that forgot this phone): start over.
            if (setupStalled()) {
                closeLocked("The radio did not finish connecting; if it was reset, pair it again")
                openLocked()
            }
        }, SETUP_MS, TimeUnit.MILLISECONDS)
    }

    private fun closeLocked(reason: String?) {
        setupTimer?.cancel(false)
        val wasUp = ready || gatt != null
        ready = false
        busy = false
        out.clear()
        mtu = 23
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        if (wasUp) listener.onDown(reason)
    }

    private fun characteristic(g: BluetoothGatt): BluetoothGattCharacteristic? =
        g.getService(LoraLinkProtocol.SERVICE_UUID)?.getCharacteristic(LoraLinkProtocol.FRAMES_UUID)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = exec.execute {
            if (g !== gatt) return@execute
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                armSetupTimer()
                if (!g.requestMtu(WANT_MTU)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // The GATT object stays open: with auto-connect Android reconnects by itself.
                val was = ready
                ready = false
                busy = false
                out.clear()
                mtu = 23
                if (was) listener.onDown(if (status == 5 || status == 137) "The radio refused this phone: pair it again" else null)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) = exec.execute {
            if (g !== gatt) return@execute
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) value else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = exec.execute {
            if (g !== gatt) return@execute
            val ch = characteristic(g) ?: run {
                Log.i(TAG, "no Clairix radio service on ${g.device.address}")
                return@execute
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: return@execute
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = exec.execute {
            if (g !== gatt) return@execute
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Insufficient authentication or encryption: Android bonds again if it can, else the bond is stale.
                Log.i(TAG, "subscribe refused: $status")
                return@execute
            }
            setupTimer?.cancel(false)
            reasm = MeshProtocol.Reassembler(maxFrame = 4_096)
            ready = true
            listener.onReady()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = exec.execute {
            if (g !== gatt) return@execute
            busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // A lost chunk breaks the frame it belonged to; the radio drops it, and what it carried is sent again later.
                out.clear()
                return@execute
            }
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            exec.execute { if (g === gatt) receive(copy) }
        }

        @Deprecated("Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            exec.execute { if (g === gatt) receive(copy) }
        }
    }

    private fun receive(chunk: ByteArray) {
        val frame = try {
            reasm.add(chunk)
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "bad chunk from the radio: ${e.message}")
            null
        } ?: return
        LoraLinkProtocol.decode(frame)?.let { listener.onFrame(it) }
    }

    private fun pump() {
        if (busy || !ready) return
        val g = gatt ?: return
        val ch = characteristic(g) ?: return
        val next = out.removeFirstOrNull() ?: return
        busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = next
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            busy = false
            out.addFirst(next)
            exec.schedule({ pump() }, 50, TimeUnit.MILLISECONDS)
        }
    }
}
