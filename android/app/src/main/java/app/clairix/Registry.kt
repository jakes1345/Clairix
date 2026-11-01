package app.clairix

import android.bluetooth.le.ScanResult
import android.content.Context
import app.clairix.security.PlatformAlerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared state between the scanning service, the NFC reader and the UI. */
object Registry {

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _cell = MutableStateFlow(CellStatus())
    val cell: StateFlow<CellStatus> = _cell.asStateFlow()

    private val _timeline = MutableStateFlow<List<TimelineEvent>>(emptyList())
    val timeline: StateFlow<List<TimelineEvent>> = _timeline.asStateFlow()

    private val _map = MutableStateFlow(MapData())
    val map: StateFlow<MapData> = _map.asStateFlow()

    private val _nfc = MutableStateFlow<List<NfcTag>>(emptyList())
    val nfc: StateFlow<List<NfcTag>> = _nfc.asStateFlow()

    fun publish(list: List<Detection>) { _detections.value = list }

    /**
     * Every raw BLE scan result the service receives, as a hot stream with no replay.
     * The RSSI locator follows one address through it and the BLE canary watches for
     * its own advertisement; neither needs its own scan registered with the adapter,
     * which the platform throttles. A slow collector loses old packets, never new.
     */
    private val _scans = MutableSharedFlow<ScanResult>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val scans: SharedFlow<ScanResult> = _scans
    fun publishScan(result: ScanResult) { _scans.tryEmit(result) }

    /**
     * Read-modify-write of the scan status, retried if another thread got there
     * first. The BLE binder thread, the main thread and the service's coroutines all
     * call this; a plain `value = block(value)` let one of two racing updates vanish.
     */
    fun update(block: (ScanStatus) -> ScanStatus) { _status.update(block) }

    fun publishCell(status: CellStatus) { _cell.value = status }

    fun publishTimeline(events: List<TimelineEvent>) { _timeline.value = events }

    fun publishMap(data: MapData) { _map.value = data }

    fun addNfc(tag: NfcTag) {
        // Newest first, and a re-tap of the same card replaces the old row
        // rather than stacking duplicates.
        _nfc.update { current -> (listOf(tag) + current.filter { it.uid != tag.uid }).take(50) }
    }

    fun clearNfc() { _nfc.value = emptyList() }

    private val _wifi = MutableStateFlow<List<WifiAnomaly>>(emptyList())
    val wifi: StateFlow<List<WifiAnomaly>> = _wifi.asStateFlow()
    fun publishWifi(anomalies: List<WifiAnomaly>) { _wifi.value = anomalies }

    /** The connected network and everything in range — what the WIFI tab shows. */
    private val _wifiStatus = MutableStateFlow(WifiStatus())
    val wifiStatus: StateFlow<WifiStatus> = _wifiStatus.asStateFlow()
    fun publishWifiStatus(status: WifiStatus) { _wifiStatus.value = status }

    /** Whether the Karma trap is set, for the WIFI tab; see [app.clairix.detect.KarmaCanary]. Off until the scanner says otherwise. */
    private val _karmaTrap = MutableStateFlow(app.clairix.detect.KarmaTrap())
    val karmaTrap: StateFlow<app.clairix.detect.KarmaTrap> = _karmaTrap.asStateFlow()
    fun publishKarmaTrap(trap: app.clairix.detect.KarmaTrap) { _karmaTrap.value = trap }

    /** Read-modify-write of the cell status; the service and the activity both write it. */
    fun updateCell(block: (CellStatus) -> CellStatus) { _cell.update(block) }

    // --- Trusted devices -----------------------------------------------------
    //
    // Kept as devices, each a set of Bluetooth addresses ([TrustBook]); [trusted] is
    // every address of every device, which is what the screens and the scanner check.
    // An address is what the owner sees when marking a device, and the scanner carries
    // that trust on to a new address only when it can prove it is the same unit
    // ([extendTrust], [app.clairix.analysis.TrustedIdentities]), so earbuds that rotate
    // their address stay the owner's for the day the trust lasts, while a tracker's new
    // address, which another unit of the product could share, is asked about again. (It used to be keyed on the identity id instead, a counter handed
    // out fresh on every service start, so marking something safe silenced nothing.)

    private const val TRUSTED_KEY = "trusted_addresses"
    private const val TRUSTED_DEVICES_KEY = "trusted_devices"

    private val trustLock = Any()

    private val _trustBook = MutableStateFlow(TrustBook.EMPTY)
    internal val trustBook: StateFlow<TrustBook> = _trustBook.asStateFlow()

    private val _trusted = MutableStateFlow<Set<String>>(emptySet())
    val trusted: StateFlow<Set<String>> = _trusted.asStateFlow()

    /** Application context, held so trust survives the process being killed. */
    @Volatile
    private var appContext: Context? = null

    /** Call once per process, from both the activity and the service. */
    fun bindTrustStore(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val book = TrustBook.from(
            prefs.getString(TRUSTED_DEVICES_KEY, null),
            prefs.getStringSet(TRUSTED_KEY, emptySet())?.toSet() ?: emptySet()
        )
        synchronized(trustLock) {
            _trustBook.value = book
            _trusted.value = book.addresses
        }
        // Trust that lapsed while nothing was running goes now, on disk too.
        expireTrust()
    }

    /**
     * Marks [address] as the owner's. When the device [rotating]ly changes its address
     * ([TrustBook.rotating]) the trust holds for a day, carried across the changes the
     * scanner links, and then lapses so the owner is asked again; otherwise for good.
     */
    fun trust(address: String, label: String? = null, rotating: Boolean = false) =
        setTrust { it.trust(address, label, rotating, System.currentTimeMillis()) }

    /** Takes trust back from the device [address] belongs to, every address of it included. */
    fun untrust(address: String) = setTrust { it.untrust(address) }

    /**
     * The scanner linked [address] to a trusted device it knows by [knownAddress]: the
     * owner's device under a new address. Keeps that device's expiry.
     */
    fun extendTrust(address: String, knownAddress: String) = setTrust { it.extend(address, knownAddress) }

    /** Drops trust that has lapsed by [now]; the scanner calls this as it publishes. */
    fun expireTrust(now: Long = System.currentTimeMillis()) = setTrust { it.expire(now) }

    private fun setTrust(change: (TrustBook) -> TrustBook) {
        val next: TrustBook
        synchronized(trustLock) {
            val current = _trustBook.value
            next = change(current)
            if (next === current) return
            _trustBook.value = next
            _trusted.value = next.addresses
        }
        appContext
            ?.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            // A fresh set: SharedPreferences must not be handed one that is later
            // mutated. The plain set stays alongside the devices for older versions.
            ?.putStringSet(TRUSTED_KEY, HashSet(next.addresses))
            ?.putString(TRUSTED_DEVICES_KEY, next.toJson())
            ?.apply()
    }

    // --- Bluetooth exposure ----------------------------------------------------

    /**
     * Formatted addresses of the Bluetooth devices connected to this phone right now,
     * from the ACL connect/disconnect broadcasts the scanner service watches, seeded
     * from the profile proxies when it starts. What a roadside sensor can log along
     * with the phone is exactly this set.
     */
    private val _connectedBluetooth = MutableStateFlow<Set<String>>(emptySet())
    val connectedBluetooth: StateFlow<Set<String>> = _connectedBluetooth.asStateFlow()
    fun setBluetoothConnected(address: String, connected: Boolean) {
        _connectedBluetooth.update { if (connected) it + address else it - address }
    }
    fun replaceBluetoothConnected(addresses: Set<String>) { _connectedBluetooth.value = addresses }

    private const val INFRASTRUCTURE_CAP = 50

    /** Surveillance hardware the radios have heard this run, most recent first, one row per address. */
    private val _infrastructure = MutableStateFlow<List<InfrastructureSighting>>(emptyList())
    val infrastructure: StateFlow<List<InfrastructureSighting>> = _infrastructure.asStateFlow()

    /** Adds or refreshes a sighting; returns the row as kept, with its running count. */
    fun publishInfrastructure(sighting: InfrastructureSighting): InfrastructureSighting {
        var kept = sighting
        _infrastructure.update { current ->
            val previous = current.firstOrNull { it.address == sighting.address }
            kept = if (previous == null) sighting else sighting.copy(
                firstSeen = previous.firstSeen, sightings = previous.sightings + 1,
                // Keep the better of the two fixes: a sighting with no position does not erase one with.
                lat = sighting.lat ?: previous.lat, lon = sighting.lon ?: previous.lon,
                nearestKnownM = sighting.nearestKnownM ?: previous.nearestKnownM,
                nearestKnownLabel = sighting.nearestKnownLabel ?: previous.nearestKnownLabel
            )
            (listOf(kept) + current.filter { it.address != sighting.address }).take(INFRASTRUCTURE_CAP)
        }
        return kept
    }

    /** What the satellite receiver hears and whether it looks like satellites; see [app.clairix.analysis.GnssMonitor]. */
    private val _gnss = MutableStateFlow(app.clairix.analysis.GnssAssessment())
    val gnss: StateFlow<app.clairix.analysis.GnssAssessment> = _gnss.asStateFlow()
    fun publishGnss(a: app.clairix.analysis.GnssAssessment) { _gnss.value = a }

    /**
     * Where position fixes come from, whether the GPS check can hold the satellites against
     * a Wi-Fi/cell position, and whether an app is faking positions; see
     * [app.clairix.analysis.Positioning]. Written by the location clients, the GPS check and
     * the scanner, so read-modify-write.
     */
    private val _positioning = MutableStateFlow(app.clairix.analysis.PositionState())
    val positioning: StateFlow<app.clairix.analysis.PositionState> = _positioning.asStateFlow()
    fun updatePositioning(block: (app.clairix.analysis.PositionState) -> app.clairix.analysis.PositionState) {
        _positioning.update(block)
    }

    private val _phoneHealth = MutableStateFlow(PhoneHealth())
    val phoneHealth: StateFlow<PhoneHealth> = _phoneHealth.asStateFlow()
    fun publishPhoneHealth(h: PhoneHealth) { _phoneHealth.value = h }
    fun updatePhoneHealth(block: (PhoneHealth) -> PhoneHealth) { _phoneHealth.update(block) }

    // --- Card vault ----------------------------------------------------------

    //
    // Decrypted cards exist here only between a successful unlock and the activity
    // going to the background; the rest of the time this holds Locked (or the
    // reason the vault could not be read), never the cards.

    private val _vault = MutableStateFlow<VaultState>(VaultState.Locked)
    val vault: StateFlow<VaultState> = _vault.asStateFlow()
    fun publishVault(state: VaultState) { _vault.value = state }
    fun lockVault() {
        _vault.value = VaultState.Locked
        // A re-tap in progress and any pairs captured but not yet saved do not
        // outlive the vault closing: locking happens when the app leaves the
        // screen or the app lock engages.
        _vaultCaptureArm.value = null
        _pendingVaultCapture.value = null
    }

    /**
     * Whether the owner has acknowledged the vault-ownership notice. Seeded from
     * [app.clairix.analysis.CardVault.hasOwnershipAck] at launch so the NFC tab can
     * gate the first save on it without reading preferences on the main thread.
     */
    private val _ownershipAck = MutableStateFlow(false)
    val ownershipAck: StateFlow<Boolean> = _ownershipAck.asStateFlow()
    fun setOwnershipAck(acknowledged: Boolean) { _ownershipAck.value = acknowledged }

    /**
     * The UID of the tag the owner asked to record for the vault, or null. While it
     * is set the NFC read path runs a full [app.clairix.analysis.NfcScanner.capture]
     * on the next tap instead of an ordinary identify-only scan.
     */
    private val _vaultCaptureArm = MutableStateFlow<String?>(null)
    val vaultCaptureArm: StateFlow<String?> = _vaultCaptureArm.asStateFlow()
    fun armVaultCapture(uid: String) { _vaultCaptureArm.value = uid }
    fun cancelVaultCapture() { _vaultCaptureArm.value = null }

    /**
     * A card recorded by a re-tap, waiting for the owner to name and save it. The
     * recorded APDU pairs live only here (and, once saved, in the encrypted card),
     * never in the scanned-tag list.
     */
    private val _pendingVaultCapture = MutableStateFlow<PendingVaultCapture?>(null)
    val pendingVaultCapture: StateFlow<PendingVaultCapture?> = _pendingVaultCapture.asStateFlow()
    fun setPendingVaultCapture(capture: PendingVaultCapture?) { _pendingVaultCapture.value = capture }

    /**
     * The vault card currently being emulated via HCE, or null. Holds the label as
     * well as the id so the banner and STOP keep working after the vault re-locks —
     * emulation is typically used with Clairix in the background.
     */
    private val _emulating = MutableStateFlow<ArmedCard?>(null)
    val emulating: StateFlow<ArmedCard?> = _emulating.asStateFlow()
    fun setEmulating(card: ArmedCard?) { _emulating.value = card }

    /**
     * The result of the last BiometricPrompt, waiting to be acted on. The prompt's
     * callback publishes here instead of calling back into the Activity that showed
     * it: if that Activity has been recreated meanwhile, the new instance picks the
     * result up and finishes the operation the purpose describes.
     */
    private val _vaultAuth = MutableStateFlow<VaultAuthResult?>(null)
    val vaultAuth: StateFlow<VaultAuthResult?> = _vaultAuth.asStateFlow()
    fun publishVaultAuth(result: VaultAuthResult) { _vaultAuth.value = result }
    /** Takes the pending result, so it runs exactly once. */
    fun takeVaultAuth(): VaultAuthResult? = _vaultAuth.getAndUpdate { null }

    /** A save the vault refused because the UID is already stored; the UI asks. */
    private val _replacePrompt = MutableStateFlow<ReplacePrompt?>(null)
    val replacePrompt: StateFlow<ReplacePrompt?> = _replacePrompt.asStateFlow()
    fun setReplacePrompt(prompt: ReplacePrompt?) { _replacePrompt.value = prompt }

    /**
     * A tab the UI should switch to — set when a tag arrives through a system NFC
     * intent, which the user expects to land on the NFC tab. Consumed by the UI.
     */
    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest.asStateFlow()
    fun requestTab(index: Int) { _tabRequest.value = index }
    fun takeTabRequest(): Int? = _tabRequest.getAndUpdate { null }

    /** A conversation the COMMS tab should open, set from a message notification. */
    private val _threadRequest = MutableStateFlow<String?>(null)
    val threadRequest: StateFlow<String?> = _threadRequest.asStateFlow()
    fun requestThread(peer: String) { _threadRequest.value = peer }
    fun takeThreadRequest(): String? = _threadRequest.getAndUpdate { null }

    /** An invite opened from a link (clairix://invite), for the COMMS tab to take. */
    private val _inviteRequest = MutableStateFlow<String?>(null)
    val inviteRequest: StateFlow<String?> = _inviteRequest.asStateFlow()
    fun requestInvite(text: String) { _inviteRequest.value = text }
    fun takeInviteRequest(): String? = _inviteRequest.getAndUpdate { null }

    /**
     * The Get help page, asked for from inside a screen that cannot reach the
     * shell: a safety card, the sheet shown before a flagged app's settings.
     * The shell opens the page over whatever is showing and consumes the request.
     */
    private val _helpRequest = MutableStateFlow(false)
    val helpRequest: StateFlow<Boolean> = _helpRequest.asStateFlow()
    fun requestHelp() { _helpRequest.value = true }
    fun takeHelpRequest(): Boolean = _helpRequest.getAndUpdate { false }

    // --- Organisation / community -------------------------------------------

    /** Shared community threats received from org members; expired ones are dropped on publish. */
    private val _sharedThreats = MutableStateFlow<List<SharedThreat>>(emptyList())
    val sharedThreats: StateFlow<List<SharedThreat>> = _sharedThreats.asStateFlow()

    fun publishSharedThreats(threats: List<SharedThreat>) {
        _sharedThreats.value = threats.filter { it.expiresAt > System.currentTimeMillis() }
    }

    /** A group conversation the COMMS tab should open (parallel to [requestThread]). */
    private val _groupRequest = MutableStateFlow<String?>(null)
    val groupRequest: StateFlow<String?> = _groupRequest.asStateFlow()
    fun requestGroup(gid: String) { _groupRequest.value = gid }
    fun takeGroupRequest(): String? = _groupRequest.getAndUpdate { null }

    /** True from the moment an SOS is sent until it is cleared. */
    private val _panicActive = MutableStateFlow(false)
    val panicActive: StateFlow<Boolean> = _panicActive.asStateFlow()
    /**
     * Whether the SOS being raised is a covert one (the duress phrase): sent all
     * the same, but leaving nothing on this phone that says it was, for whoever
     * makes the owner unlock it afterwards.
     */
    @Volatile var panicCovert: Boolean = false
        private set
    fun setPanicActive(active: Boolean, covert: Boolean = false) {
        if (active) panicCovert = covert
        _panicActive.value = active
    }

    // --- Platform hardening ----------------------------------------------------
    //
    // Each monitor publishes its own typed event so a tab can show exactly what it
    // saw, and every one of them also goes through publishAlert(), which is the
    // single path to the timeline and the notification shade.

    private const val ALERT_CAP = 100
    private const val EVENT_CAP = 50

    /** Timeline and notification work is disk and binder I/O; it runs off the caller's thread. */
    private val alertScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Alerts this process has raised, newest first. */
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    /**
     * Raises [alert]: onto [alerts], onto the timeline as an event of [Alert.kind]
     * (deduplicated by [Alert.dedupeKey] within [dedupeWindowMs]), and as a
     * notification when it is MEDIUM or worse and not [Alert.silent]. Safe to
     * call from any thread; the timeline and the notification need
     * [bindTrustStore] to have run in this process, which the activity and the
     * service both do on create.
     */
    fun publishAlert(alert: Alert, dedupeWindowMs: Long = 30 * 60_000L) {
        _alerts.update { current -> (listOf(alert) + current.filter { it.id != alert.id }).take(ALERT_CAP) }
        val ctx = appContext ?: return
        alertScope.launch {
            val event = TimelineEvent(
                id = "${alert.dedupeKey ?: alert.kind.name.lowercase()}@${alert.ts}",
                kind = alert.kind, ts = alert.ts, title = alert.title,
                detail = alert.detail, severity = alert.severity,
                lat = alert.lat, lon = alert.lon
            )
            val recorded = runCatching {
                val log = TimelineLog.get(ctx)
                val ok = log.record(event, alert.dedupeKey, dedupeWindowMs)
                if (ok) publishTimeline(log.snapshot())
                ok
            }.getOrDefault(true)
            if (recorded && !alert.silent && alert.severity.ordinal >= Severity.MEDIUM.ordinal) {
                runCatching { PlatformAlerts.notify(ctx, alert) }
            }
        }
    }

    /** The most recent biometric enrolment detected, or null when none has been. */
    private val _biometricTripwire = MutableStateFlow<BiometricTripwireEvent?>(null)
    val biometricTripwire: StateFlow<BiometricTripwireEvent?> = _biometricTripwire.asStateFlow()
    fun publishBiometricTripwire(event: BiometricTripwireEvent) { _biometricTripwire.value = event }
    /** The user has seen the warning; clears the banner. */
    fun clearBiometricTripwire() { _biometricTripwire.value = null }

    /** Screen captures of Clairix this process has seen, newest first. */
    private val _screenRecordings = MutableStateFlow<List<ScreenRecordingEvent>>(emptyList())
    val screenRecordings: StateFlow<List<ScreenRecordingEvent>> = _screenRecordings.asStateFlow()
    /** True while the platform says Clairix's window is being recorded right now. */
    private val _screenRecordingActive = MutableStateFlow(false)
    val screenRecordingActive: StateFlow<Boolean> = _screenRecordingActive.asStateFlow()
    fun publishScreenRecording(event: ScreenRecordingEvent) {
        _screenRecordings.update { current -> (listOf(event) + current).take(EVENT_CAP) }
    }
    fun setScreenRecordingActive(active: Boolean) { _screenRecordingActive.value = active }

    /** Suspicious audio inputs this process has seen, newest first. */
    private val _audioRoutes = MutableStateFlow<List<AudioRouteEvent>>(emptyList())
    val audioRoutes: StateFlow<List<AudioRouteEvent>> = _audioRoutes.asStateFlow()
    fun publishAudioRoute(event: AudioRouteEvent) {
        _audioRoutes.update { current -> (listOf(event) + current).take(EVENT_CAP) }
    }

    /** The most recent unlock-ledger row, mirrored from [app.clairix.security.UnlockLedger.latest]. */
    private val _lastUnlock = MutableStateFlow<UnlockEvent?>(null)
    val lastUnlock: StateFlow<UnlockEvent?> = _lastUnlock.asStateFlow()
    fun publishUnlock(event: UnlockEvent) {
        // Rows arrive from the admin receiver and from a backfill in either order;
        // only a newer one moves the pointer.
        _lastUnlock.update { current -> if (current == null || event.ts >= current.ts) event else current }
    }

    fun reset() {
        _detections.value = emptyList()
        _status.value = ScanStatus()
        _cell.value = CellStatus()
        _map.value = MapData()
        _wifiStatus.value = WifiStatus()
        _alerts.value = emptyList()
        _screenRecordings.value = emptyList()
        _audioRoutes.value = emptyList()
        _infrastructure.value = emptyList()
        _gnss.value = app.clairix.analysis.GnssAssessment()
    }
}
