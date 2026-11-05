package app.clairix.analysis

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.clairix.Alert
import app.clairix.EventKind
import app.clairix.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bluetooth devices newly paired with the phone. A paired device is trusted from then on: a
 * headset can take the phone's call audio and its microphone, a car or a computer may be allowed
 * the contacts and messages, and a keyboard types into every app. Someone holding the phone for a
 * minute can pair one of their own, and the pairing outlasts the minute.
 *
 * The bonded devices (BluetoothAdapter.getBondedDevices) are compared with the last ones seen,
 * which [StalkwareWatch] keeps sealed in its record, on every check it runs (once a day, at boot
 * and after an update, when Clairix comes to the front after an install, and after an install
 * while the scanner runs), and a new pairing is reported while the scanner runs as it happens,
 * from ACTION_BOND_STATE_CHANGED ([StalkwareWatch.watchPackages]). That broadcast is protected:
 * Android 12+ delivers it only to an app holding BLUETOOTH_CONNECT, and only to a receiver
 * registered at run time (a manifest receiver gets no implicit broadcast since Android 8), which is
 * why it is heard only while the scanner's service keeps one registered; the checks catch the rest.
 * Without BLUETOOTH_CONNECT, or with Bluetooth off (Android then lists no bonded device at all),
 * nothing is read and the last list is kept, so switching Bluetooth on again raises nothing.
 *
 * Nothing here scans or connects: the list is the phone's own record of what it paired with.
 */
internal object BondWatch {

    /** A paired device: its address and the name it gave, when Android shows one. */
    data class Bond(val address: String, val name: String?)

    /**
     * The devices of [current] not among [known] (addresses, compared without case); none when
     * [known] is null, which is the first look and only the baseline.
     */
    fun newBonds(current: List<Bond>, known: Set<String>?): List<Bond> {
        if (known == null) return emptyList()
        val seen = known.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }
        return current.filter { it.address.uppercase(Locale.ROOT) !in seen }.distinctBy { it.address.uppercase(Locale.ROOT) }
    }

    /** The addresses to keep after a look that found [current]. */
    fun snapshot(current: List<Bond>): Set<String> = current.mapTo(HashSet()) { it.address.uppercase(Locale.ROOT) }

    /** The timeline entry and notification for [bonds], newly paired; [now] is when they were noticed. */
    fun alert(bonds: List<Bond>, now: Long, silent: Boolean): Alert? {
        if (bonds.isEmpty()) return null
        val one = bonds.singleOrNull()
        val title = if (one != null) "New Bluetooth device paired: ${label(one)}" else "${bonds.size} new Bluetooth devices paired"
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        val list = bonds.joinToString("\n") { "${label(it)} (${it.address})" }
        val detail = "$list\nNoticed at $time. A paired device is trusted from then on: a headset can carry your calls and " +
            "its microphone, a car or computer may be allowed your contacts and messages, a keyboard types into every app. " +
            "If you did not pair ${if (one != null) "it" else "them"}, someone who had your phone did: unpair it under " +
            "Settings → Connected devices, and check what else changed while they had it."
        return Alert(
            id = "bt_bond@$now", ts = now, severity = Severity.MEDIUM, title = title, detail = detail,
            kind = EventKind.PLATFORM,
            dedupeKey = "bt_bond:" + bonds.map { it.address.uppercase(Locale.ROOT) }.sorted().joinToString(","),
            silent = silent,
        )
    }

    private fun label(b: Bond): String = b.name?.takeIf { it.isNotBlank() } ?: "an unnamed device"

    /** The phone's bonded devices now; null when they cannot be read (no permission, no adapter, Bluetooth off). */
    fun read(context: Context): List<Bond>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull() ?: return null
        // Off, Android answers with an empty list, which would read as every device unpaired.
        if (runCatching { adapter.state }.getOrNull() != BluetoothAdapter.STATE_ON) return null
        val devices = runCatching { adapter.bondedDevices }.getOrNull() ?: return null
        return devices.map { bond(context, it) }
    }

    /** [device] as a [Bond]; its name needs BLUETOOTH_CONNECT, and is left out without it. */
    fun bond(context: Context, device: BluetoothDevice): Bond {
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val name = if (connect) runCatching { device.name }.getOrNull() else null
        return Bond(device.address, name)
    }
}
