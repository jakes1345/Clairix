package app.clairix.security

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.clairix.detect.BleNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * What this phone's Bluetooth gives away to a sensor it passes, and what can be done
 * about it from an app.
 *
 * The threat is a receiver by the road, in a shop or on a pole that logs every
 * Bluetooth address it hears, with the time: the plate reader's cousin, built for
 * traffic counting and retail analytics and just as happy to follow a person. It
 * cannot open the phone or read anything off it; what it gets is identifiers. Of
 * those, the phone's own are well protected: Android advertises with a resolvable
 * private address that changes every quarter hour or so, and its fixed Classic
 * address is only answered to while the Bluetooth settings page is open. The weak
 * points are the name, if the owner put theirs in it, and the accessories. A car's
 * hands-free, most earbuds and many watches use Classic Bluetooth, whose address
 * never changes, so the car announces the same number at every sensor for its whole
 * life, and that number is paired with this phone.
 *
 * A third-party app cannot switch Bluetooth off (Android 13 removed that), unpair a
 * device or change an accessory's address. It can rename the phone, which this does,
 * and it can show the owner which accessory is the fixed one, which is the rest.
 */
object BluetoothExposure {

    /** One paired device and how it can be followed. */
    data class Accessory(
        val address: String,
        val name: String,
        /** "Classic", "BLE", "Dual" or "Unknown". */
        val radio: String,
        /** "public", "random static", "resolvable private (rotates)"…, or null when the platform does not say. */
        val addressKind: String?,
        /** True when the address it is heard on never changes; false when it rotates; null when unknown. */
        val fixedAddress: Boolean?,
        val connected: Boolean
    ) {
        /** What a sensor gets from it, in a few words. */
        val exposure: String get() = when (fixedAddress) {
            true -> "Fixed address: logged as the same device wherever it is on"
            false -> "Rotating address: hard to follow between sensors"
            null -> "Address type unknown to this Android version"
        }
    }

    data class Snapshot(
        val hasAdapter: Boolean,
        val enabled: Boolean,
        /** Whether the Nearby devices permission allowed the name and the paired list to be read. */
        val readable: Boolean,
        val name: String?,
        val personalName: Boolean,
        /** [BluetoothAdapter.getScanMode], or null when it could not be read. */
        val scanMode: Int?,
        val accessories: List<Accessory>,
        /** The phone's own model name, which is what the rename offers. */
        val modelName: String
    ) {
        val discoverable: Boolean get() = scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        val connected: List<Accessory> get() = accessories.filter { it.connected }
        val fixedConnected: List<Accessory> get() = connected.filter { it.fixedAddress == true }
    }

    /** The current picture. Reads the adapter and the bonded list; cheap, but call off the main thread. */
    fun snapshot(context: Context, connected: Set<String>): Snapshot {
        val app = context.applicationContext
        val adapter = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val model = defaultName()
        if (adapter == null) return Snapshot(false, false, false, null, false, null, emptyList(), model)
        val canConnect = hasConnect(app)
        val canScan = ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        val name = if (canConnect) runCatching { adapter.name }.getOrNull()?.takeIf { it.isNotBlank() } else null
        val scanMode = if (canScan && enabled) runCatching { adapter.scanMode }.getOrNull() else null
        val bonded: List<Accessory> = if (!canConnect) emptyList() else runCatching { adapter.bondedDevices }.getOrNull()
            ?.mapNotNull { d -> accessory(app, d, connected) }
            ?.sortedWith(compareByDescending<Accessory> { it.connected }.thenByDescending { it.fixedAddress == true }.thenBy { it.name.lowercase(Locale.US) })
            ?: emptyList()
        return Snapshot(
            hasAdapter = true, enabled = enabled, readable = canConnect,
            name = name, personalName = name != null && looksLikePersonalName(name),
            scanMode = scanMode, accessories = bonded, modelName = model
        )
    }

    /** One bonded device as an [Accessory]; needs BLUETOOTH_CONNECT, which the caller checked. */
    fun accessory(context: Context, device: BluetoothDevice, connected: Set<String>): Accessory? {
        val raw = device.address ?: return null
        val address = BleNames.formatMac(raw)
        val name = runCatching { BleNames.cleanName(device.alias) ?: BleNames.cleanName(device.name) }.getOrNull()
            ?: "Device [${BleNames.macSuffix(address)}]"
        val type = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        val kind = BleNames.addressKind(device, address)
        return Accessory(
            address = address, name = name, radio = BleNames.radio(context, device),
            addressKind = kind, fixedAddress = fixedAddress(type, kind), connected = address in connected
        )
    }

    /**
     * Whether a device is heard on one address for life. Classic Bluetooth has no
     * address privacy at all, so a Classic or dual-mode device is fixed by definition.
     * An LE device is fixed on a public or random-static address and rotates on a
     * resolvable private one; below Android 15 the stack does not say which.
     */
    fun fixedAddress(type: Int, addressKind: String?): Boolean? = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL -> true
        else -> when (addressKind) {
            "public", "random static" -> true
            "resolvable private (rotates)", "non-resolvable private" -> false
            else -> null
        }
    }

    /**
     * A name that contains a space is treated as a person's unless it is clearly
     * the phone's own model or brand ("Galaxy S26 Ultra", "SM-S948B"), which is
     * what Samsung ships. A possessive ("Jacob's S26") is a name regardless.
     */
    fun looksLikePersonalName(name: String): Boolean {
        val n = name.trim()
        val lower = n.lowercase(Locale.US)
        if (lower.contains("'s ") || lower.endsWith("'s") || lower.contains("’s")) return true
        if (!n.contains(' ')) return false
        val defaults = listOfNotNull(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.MANUFACTURER, Build.BRAND)
            .map { it.lowercase(Locale.US) }.filter { it.length >= 3 } + listOf("galaxy", "pixel", "phone")
        return defaults.none { lower.contains(it) }
    }

    /**
     * The name the rename sets: the bare model, which is what the phone shipped with
     * and what thousands of identical phones are called. The biggest crowd to stand in.
     */
    fun defaultName(): String = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Phone"

    /**
     * Renames the phone's Bluetooth adapter to [defaultName]. Returns false when the
     * stack refused (the radio off, or no Nearby devices permission); the caller says so.
     */
    fun renameToModel(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasConnect(app)) return false
        val adapter = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull() ?: return false
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return false
        return runCatching { adapter.setName(defaultName()) }.getOrDefault(false)
    }

    /** The Settings page where the radio is switched off and devices are forgotten. */
    const val SETTINGS_ACTION: String = Settings.ACTION_BLUETOOTH_SETTINGS

    /**
     * Addresses of the devices connected right now, from the audio profiles and GATT.
     * The platform answers the profile proxies asynchronously, so this waits a moment
     * for each; an unanswered one contributes nothing. Needs BLUETOOTH_CONNECT.
     */
    suspend fun connectedDevices(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val manager = app.getSystemService(BluetoothManager::class.java) ?: return@withContext emptySet()
        val adapter = manager.adapter ?: return@withContext emptySet()
        if (!hasConnect(app) || !runCatching { adapter.isEnabled }.getOrDefault(false)) return@withContext emptySet()
        val out = HashSet<String>()
        runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull()
            ?.forEach { d -> d.address?.let { out += BleNames.formatMac(it) } }
        val profiles = ArrayList<Int>(3).apply {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO)
        }
        for (profile in profiles) {
            val proxy = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
                suspendCancellableCoroutine<BluetoothProfile?> { cont ->
                    val listener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            if (cont.isActive) cont.resume(proxy)
                        }
                        override fun onServiceDisconnected(p: Int) {}
                    }
                    val asked = runCatching { adapter.getProfileProxy(app, listener, profile) }.getOrDefault(false)
                    if (!asked && cont.isActive) cont.resume(null)
                }
            } ?: continue
            runCatching { proxy.connectedDevices }.getOrNull()
                ?.forEach { d -> d.address?.let { out += BleNames.formatMac(it) } }
            runCatching { adapter.closeProfileProxy(profile, proxy) }
        }
        out
    }

    private const val PROXY_TIMEOUT_MS = 2_000L

    private fun hasConnect(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
