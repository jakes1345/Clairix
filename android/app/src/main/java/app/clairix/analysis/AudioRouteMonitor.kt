package app.clairix.analysis

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.clairix.Alert
import app.clairix.AudioRouteEvent
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.Store
import app.clairix.comms.CallManager
import app.clairix.detect.BleNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Watches for a microphone appearing where none should be.
 *
 * A Bluetooth or USB headset that connects while the owner is on a call or
 * listening to something is a headset. One whose *input* side appears while
 * nothing is playing and no call is up is how a paired earpiece left in a bag,
 * a "smart" pen, or a Bluetooth audio bug gets a live microphone on this phone:
 * once the phone routes its input to the device, anything that records hears
 * the room through it, and some devices stream the other way.
 *
 * Getting into the car, opening an earbuds case or plugging in a USB-C headset
 * before pressing play look exactly the same, and a detector that cries wolf at
 * each is one the owner learns to swipe away. So the alert is gated ([verdict]):
 *
 * - an accessory the owner marked "This is my headset" on Phone check is never
 *   reported again ([acknowledge]);
 * - a paired device that was already connected on an earlier day (the first
 *   time each paired device is seen connected is kept, sealed) is the owner's
 *   car kit or earbuds, and is left alone;
 * - whatever is connected when scanning is switched on is listed on Phone check,
 *   not alerted;
 * - anything else is LOW, on the timeline without a notification, unless the
 *   microphone becomes the phone's call audio (the communication device) with the
 *   screen off and no call up, which is MEDIUM and does notify; that is checked as
 *   it connects and again whenever the call audio moves ([escalates]).
 *
 * Every microphone it lists or reports goes to [Registry.publishAudioRoute] for
 * Phone check; reports go through [Registry.publishAlert].
 */
object AudioRouteMonitor {

    /**
     * Input device types worth watching: the wireless ones and USB. Spelled out
     * where the constant is newer than minSdk (TYPE_BLE_BROADCAST is API 33).
     */
    private val SUSPECT_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,   // 7
        AudioDeviceInfo.TYPE_USB_HEADSET,     // 22
        AudioDeviceInfo.TYPE_BLE_HEADSET,     // 26
        30                                    // TYPE_BLE_BROADCAST
    )

    /** Audio devices that are Bluetooth, input or output: each carries the device's address. */
    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,   // 7
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,  // 8
        AudioDeviceInfo.TYPE_HEARING_AID,     // 23
        AudioDeviceInfo.TYPE_BLE_HEADSET,     // 26
        AudioDeviceInfo.TYPE_BLE_SPEAKER,     // 27
        30                                    // TYPE_BLE_BROADCAST
    )

    /** One report per microphone and kind of report within this long. */
    private const val DEDUPE_MS = 6 * 60 * 60_000L

    /** The record of the owner's marks and the first connection of each paired device, sealed. */
    internal const val FILE = "audio_accessories.json"

    // ── The gate ────────────────────────────────────────────────────────────

    /** What the gate decides for a microphone that has just appeared; see [verdict]. */
    internal enum class Verdict {
        /** Nothing to say: the owner's own accessory, or one that connected while audio was in use. */
        NONE,
        /** Connected when scanning was switched on: listed on Phone check, not alerted. */
        LIST,
        /** On the timeline, with no notification. */
        LOW,
        /** A notification: it became the call audio with the screen off. */
        MEDIUM
    }

    /**
     * What [verdict] weighs about a microphone that has just appeared: whether it was already
     * connected when scanning was switched on ([atStart]); whether the owner marked it as theirs
     * ([acknowledged]); whether it is a paired device already seen connected on an earlier day
     * ([knownAccessory]); whether media was playing or a call was up ([audioBusy]); whether it is
     * the phone's call audio right now ([communicationRoute]); and whether the screen is on.
     */
    internal data class Facts(
        val atStart: Boolean,
        val acknowledged: Boolean,
        val knownAccessory: Boolean,
        val audioBusy: Boolean,
        val communicationRoute: Boolean,
        val screenOn: Boolean
    )

    internal fun verdict(f: Facts): Verdict = when {
        f.acknowledged || f.knownAccessory -> Verdict.NONE
        f.atStart -> Verdict.LIST
        f.audioBusy -> Verdict.NONE
        f.communicationRoute && !f.screenOn -> Verdict.MEDIUM
        else -> Verdict.LOW
    }

    /**
     * Whether a microphone listed or reported this session becomes MEDIUM now that the call audio
     * has moved to it: not the owner's, not a known paired accessory, not reported at MEDIUM
     * already, the screen off and no call up (a phone call, a ringing phone or a Clairix call),
     * since a call is what the call audio is for.
     */
    internal fun escalates(
        acknowledged: Boolean,
        knownAccessory: Boolean,
        alreadyMedium: Boolean,
        communicationRoute: Boolean,
        screenOn: Boolean,
        callUp: Boolean
    ): Boolean = !acknowledged && !knownAccessory && !alreadyMedium && communicationRoute && !screenOn && !callUp

    /** Whether [firstSeen] fell on a calendar day before [now]'s, in [zone]; false when it was never seen. */
    internal fun seenOnEarlierDay(firstSeen: Long?, now: Long, zone: ZoneId): Boolean {
        if (firstSeen == null) return false
        val first = Instant.ofEpochMilli(firstSeen).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return first.isBefore(today)
    }

    /** A microphone as the gate sees it: its type, its Bluetooth address (null for USB or none given) and its name. */
    internal data class Mic(val type: Int, val address: String?, val name: String) {
        val bluetooth: Boolean get() = type != AudioDeviceInfo.TYPE_USB_HEADSET

        /** What "This is my headset" remembers it by: the address, or the kind and name when there is none. */
        val key: String get() = if (address != null) "bt:$address" else "name:$type:$name"
    }

    /** The phone's call audio device ([AudioManager.getCommunicationDevice]): type, Bluetooth address and name. */
    internal data class Route(val type: Int, val address: String?, val name: String)

    /**
     * Whether [route] is [mic]: for Bluetooth the same device by address (by name when either has
     * none), the input and the call output being two sides of one headset; for USB the same kind and name.
     */
    internal fun matches(mic: Mic, route: Route): Boolean =
        if (mic.bluetooth) {
            route.type in BLUETOOTH_TYPES &&
                if (mic.address != null && route.address != null) mic.address == route.address else mic.name == route.name
        } else {
            route.type == mic.type && route.name == mic.name
        }

    // ── Running ─────────────────────────────────────────────────────────────

    private val lock = Any()
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var callback: AudioDeviceCallback? = null
    private var routeListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    /**
     * Decisions read the record (a sealed file) and the radios; they run here, off the main
     * thread the platform calls back on, one at a time and in order.
     */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-route").apply { isDaemon = true } }

    /**
     * Ids of the devices that were connected when [start] ran. They belong to the
     * startup sweep, which lists them. Registering the callback also delivers every
     * device already present through onAudioDevicesAdded, so without this each one
     * was handled twice, once by each path. A device leaves the set when it is
     * removed, so the same device coming back is news.
     */
    private val presentAtStart = HashSet<Int>()

    /** A microphone listed or reported this session, by its [AudioDeviceInfo] id, and whether it was reported at MEDIUM. */
    private class Seen(val mic: Mic, val medium: Boolean)
    private val seen = HashMap<Int, Seen>()

    /**
     * Sweeps what is already connected, then registers for changes. Each device is
     * handled by exactly one of the two. Idempotent.
     */
    fun start(context: Context) {
        synchronized(lock) {
            if (callback != null) return
            val app = context.applicationContext
            val am = app.getSystemService(AudioManager::class.java) ?: return
            // Taken before registering: anything that appears between the two is
            // missing here and is handled by the callback as newly connected.
            val present = runCatching { am.getDevices(AudioManager.GET_DEVICES_ALL) }.getOrNull() ?: emptyArray()
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                    added ?: return
                    val fresh = synchronized(lock) {
                        if (callback !== this) return
                        // remove() is true for a device the sweep already covered.
                        added.filterNot { presentAtStart.remove(it.id) }
                    }
                    if (fresh.isEmpty()) return
                    worker.execute { if (live(this)) runCatching { added(app, am, fresh) } }
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                    removed ?: return
                    synchronized(lock) {
                        removed.forEach { presentAtStart.remove(it.id); seen.remove(it.id) }
                    }
                }
            }
            presentAtStart.clear()
            seen.clear()
            present.forEach { presentAtStart.add(it.id) }
            runCatching { am.registerAudioDeviceCallback(cb, null) }.onFailure { presentAtStart.clear(); return }
            // The call audio moving to a microphone with the screen off is what turns a report into a notification.
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (device != null && live(cb)) runCatching { routeChanged(app, am, device) }
            }
            runCatching { am.addOnCommunicationDeviceChangedListener(worker, listener) }
                .onSuccess { routeListener = listener }
            appContext = app
            audioManager = am
            callback = cb
            // The microphones attached before the service started are listed here,
            // as present at startup; the callback skips them.
            val snapshot = present.toList()
            worker.execute { if (live(cb)) runCatching { sweep(app, snapshot) } }
        }
    }

    fun stop() {
        synchronized(lock) {
            val am = audioManager
            val cb = callback
            val listener = routeListener
            audioManager = null
            callback = null
            routeListener = null
            presentAtStart.clear()
            seen.clear()
            if (am != null && cb != null) runCatching { am.unregisterAudioDeviceCallback(cb) }
            if (am != null && listener != null) runCatching { am.removeOnCommunicationDeviceChangedListener(listener) }
        }
    }

    /** Whether [cb] is still the registered callback: work queued before a stop does nothing after it. */
    private fun live(cb: AudioDeviceCallback): Boolean = synchronized(lock) { callback === cb }

    /** What was connected when scanning was switched on: listed, never alerted. */
    private fun sweep(app: Context, present: List<AudioDeviceInfo>) {
        val now = System.currentTimeMillis()
        val bonded = bondedAddresses(app)
        noteConnected(app, present, bonded, now)
        val marks = record(app).marks().keys
        for (d in present) {
            if (!d.isSource || d.type !in SUSPECT_INPUT_TYPES) continue
            val mic = mic(d)
            val facts = Facts(
                atStart = true, acknowledged = mic.key in marks, knownAccessory = known(app, mic, bonded, now),
                audioBusy = false, communicationRoute = false, screenOn = true
            )
            if (verdict(facts) == Verdict.LIST) {
                synchronized(lock) { seen[d.id] = Seen(mic, medium = false) }
                Registry.publishAudioRoute(event(mic, now, atStart = true, alerted = null))
            }
        }
    }

    /** Devices connected since the sweep: each microphone among them is judged as it arrives. */
    private fun added(app: Context, am: AudioManager, fresh: List<AudioDeviceInfo>) {
        val now = System.currentTimeMillis()
        val bonded = bondedAddresses(app)
        noteConnected(app, fresh, bonded, now)
        val inputs = fresh.filter { it.isSource && it.type in SUSPECT_INPUT_TYPES }
        if (inputs.isEmpty()) return
        val busy = !isQuiet(am)
        val screenOn = screenOn(app)
        val route = communicationRoute(am)
        val marks = record(app).marks().keys
        for (d in inputs) {
            val mic = mic(d)
            val facts = Facts(
                atStart = false, acknowledged = mic.key in marks, knownAccessory = known(app, mic, bonded, now),
                audioBusy = busy, communicationRoute = route != null && matches(mic, route), screenOn = screenOn
            )
            when (verdict(facts)) {
                Verdict.LOW -> report(d.id, mic, now, Severity.LOW)
                Verdict.MEDIUM -> report(d.id, mic, now, Severity.MEDIUM)
                Verdict.NONE, Verdict.LIST -> Unit
            }
        }
    }

    /** The call audio moved to [device]: a microphone listed or reported this session may now be MEDIUM. */
    private fun routeChanged(app: Context, am: AudioManager, device: AudioDeviceInfo) {
        val route = route(device)
        val candidates = synchronized(lock) { seen.filter { (_, s) -> !s.medium && matches(s.mic, route) } }
        if (candidates.isEmpty()) return
        val now = System.currentTimeMillis()
        val screenOn = screenOn(app)
        val callUp = callUp(am)
        val bonded = bondedAddresses(app)
        val marks = record(app).marks().keys
        for ((id, s) in candidates) {
            val up = escalates(
                acknowledged = s.mic.key in marks, knownAccessory = known(app, s.mic, bonded, now),
                alreadyMedium = s.medium, communicationRoute = true, screenOn = screenOn, callUp = callUp
            )
            if (up) report(id, s.mic, now, Severity.MEDIUM)
        }
    }

    private fun report(id: Int, mic: Mic, now: Long, severity: Severity) {
        synchronized(lock) { seen[id] = Seen(mic, medium = severity == Severity.MEDIUM) }
        val event = event(mic, now, atStart = false, alerted = severity)
        Registry.publishAudioRoute(event)
        val what = if (mic.bluetooth) "A Bluetooth microphone" else "A USB microphone"
        val device = "${mic.name} (${event.deviceTypeLabel}${if (mic.address != null) ", ${mic.address}" else ""})"
        val medium = severity == Severity.MEDIUM
        Registry.publishAlert(
            Alert(
                id = "audio_route@${mic.key}@$now",
                ts = now,
                severity = severity,
                title = if (medium) "$what became the phone's call audio with the screen off"
                        else "$what connected while nothing was playing",
                detail = if (medium) {
                    "$device became the phone's call audio with the screen off and no call up, so something on the phone " +
                        "is using it to listen or to play sound. If it is not your headset, find it: Settings → Connected " +
                        "devices lists what is paired, and Forget removes one you do not know. If it is yours, mark it on " +
                        "Phone check with \"This is my headset\" and it is not reported again."
                } else {
                    "$device connected a microphone to this phone with no call up and nothing playing. Your own headset, " +
                        "earbuds out of their case or your car is fine: mark it on Phone check with \"This is my headset\" " +
                        "and it is not reported again. Anything you cannot see is not. Settings → Connected devices lists " +
                        "what is paired."
                },
                kind = EventKind.AUDIO_ROUTE,
                dedupeKey = (if (medium) "audio_route_live@" else "audio_route@") + mic.key
            ),
            dedupeWindowMs = DEDUPE_MS
        )
    }

    private fun event(mic: Mic, now: Long, atStart: Boolean, alerted: Severity?) = AudioRouteEvent(
        ts = now, deviceType = mic.type, deviceName = mic.name, productName = mic.name,
        address = mic.address, key = mic.key, atStart = atStart, alerted = alerted
    )

    private fun mic(d: AudioDeviceInfo): Mic {
        val name = runCatching { d.productName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unnamed device"
        val address = if (d.type == AudioDeviceInfo.TYPE_USB_HEADSET) null else address(d)
        return Mic(d.type, address, name)
    }

    private fun route(d: AudioDeviceInfo): Route {
        val name = runCatching { d.productName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unnamed device"
        return Route(d.type, if (d.type in BLUETOOTH_TYPES) address(d) else null, name)
    }

    /** A Bluetooth audio device's address, as the rest of the app writes it; null when the platform gives none. */
    private fun address(d: AudioDeviceInfo): String? =
        runCatching { d.address }.getOrNull()?.takeIf { it.isNotBlank() }?.let(BleNames::formatMac)

    private fun communicationRoute(am: AudioManager): Route? = runCatching { am.communicationDevice }.getOrNull()?.let(::route)

    /** A paired device already seen connected on an earlier day: the owner's car kit or earbuds. */
    private fun known(app: Context, mic: Mic, bonded: Set<String>, now: Long): Boolean {
        val address = mic.address ?: return false
        if (address !in bonded) return false
        return seenOnEarlierDay(record(app).firstConnected(address), now, ZoneId.systemDefault())
    }

    /**
     * Keeps the first connection of each paired Bluetooth device seen: those among [devices], and
     * whatever the scanner knows to be connected ([Registry.connectedBluetooth]).
     */
    private fun noteConnected(app: Context, devices: List<AudioDeviceInfo>, bonded: Set<String>, now: Long) {
        if (bonded.isEmpty()) return
        val connected = devices.filter { it.type in BLUETOOTH_TYPES }.mapNotNullTo(HashSet()) { address(it) }
        connected += Registry.connectedBluetooth.value.map(BleNames::formatMac)
        record(app).noteConnected(connected, bonded, now)
    }

    /** Addresses of the paired devices; empty without the Nearby devices permission or with Bluetooth off. */
    private fun bondedAddresses(app: Context): Set<String> {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptySet()
        val adapter = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull() ?: return emptySet()
        return runCatching { adapter.bondedDevices }.getOrNull()
            ?.mapNotNullTo(HashSet()) { d -> d.address?.let(BleNames::formatMac) } ?: emptySet()
    }

    private fun screenOn(app: Context): Boolean =
        runCatching { app.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() ?: true

    /**
     * Nothing is using audio: no media playing, the audio mode is normal (not in
     * a cellular or VoIP call, not ringing) and no Clairix call is up. Clairix's own
     * calls set MODE_IN_COMMUNICATION too, but CallManager is consulted directly
     * so a call that is still being set up is not mistaken for silence.
     */
    private fun isQuiet(am: AudioManager): Boolean {
        val music = runCatching { am.isMusicActive }.getOrDefault(false)
        if (music) return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return false
        if (CallManager.call.value != null) return false
        return true
    }

    /**
     * A call the call audio belongs to: a phone call, a ringing or screened phone, or a Clairix
     * call. Communication mode on its own is not one: it is what an app taking the call audio for
     * itself sets.
     */
    private fun callUp(am: AudioManager): Boolean {
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_CALL_SCREENING || CallManager.call.value != null
    }

    // ── The owner's marks ───────────────────────────────────────────────────

    /** An accessory the owner marked as theirs: what it is remembered by ([Mic.key]), its name, and when. */
    data class Mark(val key: String, val name: String, val at: Long)

    @Volatile private var record: AccessoryRecord? = null

    private fun record(context: Context): AccessoryRecord =
        record ?: synchronized(this) {
            record ?: AccessoryRecord(Store(context.applicationContext, FILE)).also { record = it }
        }

    private val _marks = MutableStateFlow<List<Mark>>(emptyList())
    /** The accessories marked as the owner's, for Phone check; filled by [loadMarks]. */
    val marks: StateFlow<List<Mark>> = _marks.asStateFlow()

    /**
     * Settings, Clear all data: forgets when each paired accessory was first seen connected.
     * The headsets marked as the owner's stay, as the trusted devices and the spyware check's
     * marks do. Writes a sealed file; call off the main thread.
     */
    fun forget(context: Context) {
        record(context).clearSeen()
    }

    /** Reads the marks into [marks]. Reads a sealed file; call off the main thread. */
    fun loadMarks(context: Context) {
        _marks.value = runCatching { record(context).marks().values.sortedByDescending { it.at } }.getOrDefault(emptyList())
    }

    /**
     * "This is my headset": [event]'s microphone is not reported again. Put on the timeline, since
     * whoever holds the phone could press it too, and the timeline is where that shows. Writes a
     * sealed file; call off the main thread.
     */
    fun acknowledge(context: Context, event: AudioRouteEvent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        record(app).mark(event.key, event.deviceName, now)
        loadMarks(app)
        Registry.publishAlert(
            Alert(
                id = "audio_route_mine@$now", ts = now, severity = Severity.LOW,
                title = "Marked as yours: ${event.deviceName}",
                detail = "Marked on Phone check with \"This is my headset\": Clairix no longer reports this microphone " +
                    "(${event.deviceTypeLabel}${event.address?.let { ", $it" } ?: ""}). If you did not do this, undo it on the same page.",
                kind = EventKind.AUDIO_ROUTE, dedupeKey = "audio_route_mine@${event.key}", silent = true
            )
        )
    }

    /** Takes back the mark on [key]: the microphone is judged like any other again. Writes a sealed file. */
    fun unacknowledge(context: Context, key: String) {
        val app = context.applicationContext
        record(app).unmark(key)
        loadMarks(app)
    }
}

/**
 * The audio-route monitor's record in a sealed [Store]: the accessories the owner marked as theirs
 * (`acks`: key → name and when) and the first time each paired Bluetooth device was seen connected
 * (`seen`: address → time). Only paired devices are kept, and a device no longer paired is dropped
 * the next time the paired list is read.
 */
internal class AccessoryRecord(private val store: Store) {

    private val lock = Any()

    private fun root(): JSONObject = store.json

    fun marks(): Map<String, AudioRouteMonitor.Mark> {
        synchronized(lock) {
            val acks = root().optJSONObject(ACKS) ?: return emptyMap()
            return acks.keys().asSequence().associateWith { key ->
                val o = acks.optJSONObject(key)
                AudioRouteMonitor.Mark(key, o?.optString("name").orEmpty().ifEmpty { key }, o?.optLong("at") ?: 0L)
            }
        }
    }

    fun mark(key: String, name: String, at: Long) {
        synchronized(lock) {
            val acks = root().optJSONObject(ACKS) ?: JSONObject().also { root().put(ACKS, it) }
            acks.put(key, JSONObject().put("name", name).put("at", at))
            store.markDirty()
            store.flush()
        }
    }

    /** Forgets every first connection; the marks stay. */
    fun clearSeen() {
        synchronized(lock) {
            if (root().remove(SEEN) == null) return
            store.markDirty()
            store.flush()
        }
    }

    fun unmark(key: String) {
        synchronized(lock) {
            val acks = root().optJSONObject(ACKS) ?: return
            if (acks.remove(key) == null) return
            store.markDirty()
            store.flush()
        }
    }

    /** When [address] was first seen connected, or null when it never has been (or is not paired). */
    fun firstConnected(address: String): Long? {
        synchronized(lock) {
            val seen = root().optJSONObject(SEEN) ?: return null
            return if (seen.has(address)) seen.optLong(address) else null
        }
    }

    /**
     * Keeps now as the first connection of each of [connected] that is paired ([bonded]) and has
     * none yet, and drops the devices no longer paired. Writes only when something changed.
     */
    fun noteConnected(connected: Collection<String>, bonded: Set<String>, now: Long) {
        if (bonded.isEmpty()) return
        synchronized(lock) {
            val seen = root().optJSONObject(SEEN) ?: JSONObject().also { root().put(SEEN, it) }
            var changed = false
            for (address in connected) {
                if (address in bonded && !seen.has(address)) {
                    seen.put(address, now)
                    changed = true
                }
            }
            val unpaired = seen.keys().asSequence().filter { it !in bonded }.toList()
            if (unpaired.isNotEmpty()) {
                unpaired.forEach { seen.remove(it) }
                changed = true
            }
            if (changed) {
                store.markDirty()
                store.flush()
            }
        }
    }

    private companion object {
        const val ACKS = "acks"
        const val SEEN = "seen"
    }
}
