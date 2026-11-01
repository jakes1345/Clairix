package app.clairix

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.clairix.analysis.AudioRouteMonitor
import app.clairix.analysis.CellMonitor
import app.clairix.analysis.DebugPace
import app.clairix.analysis.EventLog
import app.clairix.analysis.Fingerprint
import app.clairix.analysis.FollowAlertPolicy
import app.clairix.analysis.GnssMonitor
import app.clairix.analysis.Fix
import app.clairix.analysis.IMSICatcher
import app.clairix.analysis.IdentityResolver
import app.clairix.analysis.LocationClient
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.LocationPolicy
import app.clairix.analysis.LocationTrack
import app.clairix.analysis.LowPower
import app.clairix.analysis.LowPowerMode
import app.clairix.analysis.MockFixes
import app.clairix.analysis.MotionPolicy
import app.clairix.analysis.Tracker
import app.clairix.analysis.PhoneHealthMonitor
import app.clairix.analysis.ScanPacing
import app.clairix.analysis.ScanPower
import app.clairix.analysis.ScanRecord
import app.clairix.analysis.ScannerHealth
import app.clairix.analysis.StalkwareWatch
import app.clairix.analysis.TlsCanary
import app.clairix.analysis.TrustedIdentities
import app.clairix.analysis.WifiScanner
import app.clairix.analysis.haversine
import app.clairix.analysis.toFix
import app.clairix.detect.Advert
import app.clairix.detect.BleCanary
import app.clairix.detect.BleNames
import app.clairix.detect.CadenceFingerprinter
import app.clairix.detect.DultInterrogator
import app.clairix.detect.DultResult
import app.clairix.detect.DultSchedule
import app.clairix.detect.KarmaCanary
import app.clairix.detect.ScreenOffWatch
import app.clairix.detect.Signatures
import app.clairix.detect.SurveillanceHardware
import app.clairix.detect.VehicleTrips
import app.clairix.nav.CameraFeed
import app.clairix.nav.KnownCamera
import app.clairix.security.AppLock
import app.clairix.security.BluetoothExposure
import app.clairix.security.HistoryRetention
import app.clairix.security.NotificationPresentation
import app.clairix.security.NotificationText
import app.clairix.security.PlatformAlerts
import app.clairix.security.UnlockLedger
import app.clairix.security.Wiper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class ScanService : LifecycleService() {

    private var scanner: BluetoothLeScanner? = null
    /** The scanner's position fixes, from Google Play services or Android's own location service; see [LocationClient]. */
    private lateinit var location: LocationClient
    private var running = false

    private lateinit var tracker: Tracker
    private val identities = IdentityResolver()
    private val track = LocationTrack()
    /** Links address rotations the payload cannot prove, by the rhythm of the radio. */
    private val cadence = CadenceFingerprinter()
    private lateinit var vehicleTrips: VehicleTrips
    /** When history older than the owner's retention was last deleted; see [HistoryRetention] and [ScanPacing.retentionDue]. */
    private var lastRetentionAt = 0L

    /** When each logical device is asked over DULT: on qualifying, on separating, every 15 min, on request. */
    private val dultSchedule = DultSchedule()
    /** One GATT exchange at a time; the stack handles parallel connections badly. */
    private val dultGate = Mutex()

    /** The logical devices behind the owner's trusted addresses, this run; see [TrustedIdentities]. */
    private val trustedIdentities = TrustedIdentities()

    /** The addresses the screen-off scan was last registered with a filter for, and when; see [ScreenOffWatch]. */
    @Volatile private var watchedFilterAddresses: Set<String> = emptySet()
    @Volatile private var watchedFilterAt = 0L

    /** Wall time until which the scan runs at low latency: the first minutes of a drive. */
    @Volatile
    private var intensiveUntil = 0L
    private var intensiveJob: Job? = null
    /** Consecutive fixes at driving speed, and when the last of them arrived. */
    private var drivingFixes = 0
    @Volatile
    private var lastDrivingAt = 0L

    private lateinit var cellMonitor: CellMonitor
    private lateinit var imsiCatcher: IMSICatcher
    private lateinit var eventLog: EventLog
    private lateinit var cellStore: Store

    private val alerted: MutableSet<String> = Collections.synchronizedSet(HashSet())
    /** When a confirmed follower wakes the shade: a recognised tracker at once, anything unidentified once per journey. */
    private val followAlerts = FollowAlertPolicy()
    private val gpsTrail = ArrayList<LatLon>(500)
    private var publishCycle = 0
    private lateinit var phoneHealthMonitor: PhoneHealthMonitor

    /** Whether a scan is currently registered with the adapter. */
    @Volatile
    private var scanActive = false

    /** The mode and batch delay of the scan registered last; see [blePlan]. Main thread. */
    private var activeBleScan: ScanPacing.BleScan? = null

    /** Screen state decides which filter strategy the scan can use — see [startScanning]. */
    @Volatile
    private var screenOn = true

    /** Last ongoing-notification text, so it is only re-posted when it actually changes. */
    private var lastOngoingText: String? = null
    /** Whether that notification carried the Stop button; it goes and comes back with the app lock. */
    private var lastOngoingStop: Boolean? = null

    /**
     * The plate readers on the map lists this phone holds, so hardware heard over radio
     * can be matched to a mapped camera or reported as one the map lacks. The signed
     * caches are looked at every [KNOWN_CAMERAS_REFRESH_MS] and re-read only when one
     * of them changed on disk ([CameraFeed.cacheStamp]).
     */
    @Volatile private var knownCameras: List<KnownCamera> = emptyList()
    private var knownCamerasAt = 0L
    private var knownCamerasStamp: String? = null

    /** Running while the phone is discoverable; fires an alert if it stays so. */
    private var discoverableJob: Job? = null

    /** The satellite spoofing and jamming checks, held for as long as the scanner runs. */
    private var gnssHandle: GnssMonitor.Handle? = null

    /** When each piece of surveillance hardware was last written up, by address; a battery advertises several times a second. */
    private val infrastructureAlertedAt = HashMap<String, Long>()

    /**
     * Where each packet is worked on. The scanner delivers its callbacks on the main
     * thread, and resolving identities, cadence and the tracker for every advert there
     * kept the UI thread (and with it the CPU) busy with every phone and earbud nearby.
     * One thread, so packets are still handled in the order they arrived.
     */
    private val bleExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ble-packets").apply { isDaemon = true } }

    private fun post(results: List<ScanResult>) {
        try {
            bleExecutor.execute {
                for (r in results) {
                    runCatching { handle(r) }.onFailure { e ->
                        // Counted and shown on the Trackers details card, and logged
                        // sparingly: a stack handing over a malformed record does it
                        // for every advert, not once.
                        val n = packetErrors.incrementAndGet()
                        if (n == 1 || n % 100 == 0) Log.w(TAG, "packet handling failed ($n so far)", e)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The service is gone; a packet that arrived on the way out is dropped.
        }
    }

    /** When the last advertisement reached [handle], and when the current scan was registered; see [ScannerHealth]. */
    @Volatile private var lastPacketAt = 0L
    @Volatile private var scanStartedAt = 0L

    /** Packets whose handling threw, counted rather than swallowed. */
    private val packetErrors = AtomicInteger()

    /** The health message in force, so the owner is told once per loss of hearing, not every cycle. */
    private var deafCause: String? = null
    private var lastHeartbeatAt = 0L
    private val loopFailures = AtomicInteger()

    /** The Location switch as last seen, so its coming back on restarts the scan once, not on every provider change. */
    @Volatile private var locationWasOn = true

    /** Whatever still escapes [guarded] is logged rather than taking the process down. */
    private val loopGuard = CoroutineExceptionHandler { _, e -> Log.e(TAG, "scanner loop failed", e) }

    /**
     * Runs one iteration of a polling loop. An iteration that throws is logged,
     * [onFailure] is told, and the loop pauses [pauseMs] before the next, instead of
     * the throw ending the loop and crashing the process: the cell poll and the
     * publish cycle each call half a dozen things that can throw (the modem, the
     * Wi-Fi scanner, the disk), and one bad poll used to end the check for the rest
     * of the run. Only cancellation, which is how the loop is meant to end, passes.
     */
    private suspend fun guarded(
        loop: String,
        pauseMs: Long,
        onFailure: (Throwable) -> Unit = {},
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val n = loopFailures.incrementAndGet()
            if (n <= 3 || n % 50 == 0) Log.w(TAG, "$loop loop: iteration failed ($n so far)", e)
            runCatching { onFailure(e) }
            delay(pauseMs)
        }
    }

    /** The system Location switch; unknown reads as on, so a missing service never cries wolf. */
    private fun locationEnabled(): Boolean =
        runCatching { getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() != false

    /** How hard the scanner works for its position; see [applyPace]. */
    private val motion = MotionPolicy()

    /** The significant-motion sensor, a one-shot hardware trigger re-armed each time the phone settles. */
    private val motionSensor: Sensor? by lazy {
        getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }
    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            motion.onMotion(System.currentTimeMillis())?.let { applyPace(it) }
        }
    }

    /** Cuts the publish loop's wait short, so the screen coming on shows live data at once. */
    private val publishKick = Channel<Unit>(Channel.CONFLATED)
    private var lastHealthAt = 0L
    private var lastWifiAt = 0L

    /** The nearest mapped plate reader per address, so a battery advertising several times a second is not looked up against every camera each time. */
    private val nearestCameraCache = HashMap<String, Triple<Long, Double?, String?>>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { post(listOf(result)) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { post(ArrayList(results)) }
        override fun onScanFailed(errorCode: Int) {
            // A scan that is already running is not an error worth showing.
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return
            scanActive = false
            Registry.update { it.copy(scanning = false, error = "Bluetooth scan failed ($errorCode)") }
            // The service stays alive after a failure, so without a retry it would sit
            // there forever with nothing registered. Transient stack errors are retried
            // after a pause; the throttle error gets a longer one.
            val retryMs = when (errorCode) {
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> RETRY_DELAY_MS
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> RETRY_THROTTLED_DELAY_MS
                else -> return
            }
            scheduleScanRetry(retryMs)
        }
    }

    /** Pending scan retry after an [onScanFailed]; only one is ever in flight. */
    private var retryJob: Job? = null

    private fun scheduleScanRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(delayMs)
            retryJob = null
            if (running && !scanActive) startScanning()
        }
    }

    /**
     * Every position fix, on the main thread, from whichever source serves the scanner.
     *
     * A fix Android marks as mock is an app's invention ([MockFixes]): it places no
     * sighting, moves no odometer, sets no pace and goes into no history, trail or trip,
     * and the phone's position is unknown until a real fix comes. The status says there
     * is no fix and keeps the last real position, which an SOS still sends; the Trackers
     * screen says positions are being faked ([Registry.positioning]).
     */
    private fun onFix(loc: Location) {
        val fix = loc.toFix()
        val now = System.currentTimeMillis()
        val real = MockFixes.take(track, fix, mock = loc.isMock)
        Registry.updatePositioning { it.copy(mockFixAt = if (real) 0L else now) }
        if (!real) {
            Registry.update { it.copy(hasFix = false) }
            return
        }
        motion.onFix(fix)?.let { applyPace(it) }
        LocationHistory.record(this, fix)
        updateVehicle(fix)
        synchronized(gpsTrail) {
            gpsTrail.add(LatLon(fix.lat, fix.lon))
            if (gpsTrail.size > 500) gpsTrail.removeAt(0)
        }
        Registry.update {
            it.copy(
                hasFix = true, moving = track.isMoving(),
                lat = fix.lat, lon = fix.lon, travelledM = track.travelledM
            )
        }
    }

    /**
     * Two things outside this service change whether it can see anything: the screen
     * turning off (which changes what filters the scan needs) and Bluetooth being
     * switched on or off. Without watching for the latter, turning Bluetooth on after
     * starting a scan left the app sitting on "Bluetooth is off" forever.
     */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; restartScan(); publishKick.trySend(Unit) }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; restartScan() }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> restartScan()
                        BluetoothAdapter.STATE_OFF -> {
                            scanActive = false
                            Registry.replaceBluetoothConnected(emptySet())
                            Registry.update {
                                it.copy(scanning = false, bluetoothOn = false, error = "Bluetooth is off")
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> onScanModeChanged(
                    intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)
                )
                LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {
                    // Android withholds scan results while Location is off. When it comes
                    // back the scan is registered afresh rather than trusted to resume:
                    // once per return, since a toggle sends several of these and Android
                    // throttles an app that starts scans in quick succession. Either way
                    // the health check runs at once instead of at the next cycle.
                    val on = locationEnabled()
                    if (on && !locationWasOn) restartScan()
                    locationWasOn = on
                    publishKick.trySend(Unit)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val raw = device.address ?: return
                    val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    val address = BleNames.formatMac(raw)
                    Registry.setBluetoothConnected(address, connected)
                    // A connection made mid-drive rides along too; the name is read off the
                    // bonded record, which needs the Nearby devices permission. A car kit
                    // shows the journey is in the owner's car.
                    if (connected && vehicleTrips.inTrip && granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                        BluetoothExposure.accessory(this@ScanService, device, setOf(address))
                            ?.let { vehicleTrips.noteAccessory(it.address, it.name, car = it.car) }
                    }
                }
            }
        }
    }

    /**
     * Discoverable is the one state in which the phone answers any scanner with its
     * fixed address and its name. Android enters it only while the Bluetooth settings
     * page is open, so a phone that stays discoverable has been left on that page: say
     * so once, after [DISCOVERABLE_ALERT_MS], rather than on every poll.
     */
    private fun onScanModeChanged(mode: Int) {
        if (mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            if (discoverableJob?.isActive == true) return
            discoverableJob = lifecycleScope.launch {
                delay(DISCOVERABLE_ALERT_MS)
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return@launch
                val still = granted(Manifest.permission.BLUETOOTH_SCAN) &&
                    runCatching { adapter.scanMode }.getOrNull() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                if (!still) return@launch
                val now = System.currentTimeMillis()
                Registry.publishAlert(
                    Alert(
                        id = "bt_discoverable@$now", ts = now, severity = Severity.MEDIUM,
                        title = "Phone has been discoverable for ${DISCOVERABLE_ALERT_MS / 60_000} minutes",
                        detail = "The phone is answering Bluetooth discovery with its fixed address and its name, so any " +
                            "scanner nearby can list it. Android does this only while the Bluetooth settings page is open: " +
                            "leave that page, or turn Bluetooth off and on.",
                        kind = EventKind.PLATFORM, dedupeKey = "bt_discoverable"
                    )
                )
            }
        } else {
            discoverableJob?.cancel()
            discoverableJob = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The service can be started at boot without the activity ever running, so it
        // loads settings and the trusted list itself rather than assuming they are up.
        AppSettings.load(this)
        Registry.bindTrustStore(this)

        tracker = Tracker(
            persistMs = { AppSettings.persistenceThresholdMs },
            followM = { AppSettings.followThresholdM.toDouble() }
        )

        createChannels()
        getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner.also { scanner = it }
        // A batch of fixes is taken by its newest, as one fix.
        location = LocationClient(this) { fixes -> fixes.lastOrNull()?.let { onFix(it) } }
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true

        cellStore = Store(this, CELL_FILE)
        cellMonitor = CellMonitor(this)
        imsiCatcher = IMSICatcher(cellStore)
        liveImsi = imsiCatcher
        vehicleTrips = VehicleTrips(Store(this, TRIPS_FILE))
        liveTrips = vehicleTrips
        // Shared with the activity, which records NFC scans into the same log.
        eventLog = TimelineLog.get(this)
        Registry.publishTimeline(eventLog.snapshot())
        phoneHealthMonitor = PhoneHealthMonitor(this)
        phoneHealthMonitor.start()
        // A microphone attaching itself over Bluetooth or USB while nothing is
        // playing is watched for as long as the scanner runs; the unlock ledger
        // opens here too so the periodic refresh below has it.
        AudioRouteMonitor.start(this)
        UnlockLedger.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                AppSettings.setScanEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLEAR -> {
                // Wiping the files on disk is not enough while this service is alive:
                // it holds the baseline and the event log in memory and would write
                // them straight back out on the next flush.
                clearAllData()
                if (!running) { stopSelf(); return START_NOT_STICKY }
                return START_STICKY
            }
            // The tracker lives in this service, so the screens' actions on one device
            // come here. With no scan running there is no tracker to act on, and the
            // request must not start one.
            ACTION_RESOLVE, ACTION_DULT_ASK -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                val key = intent.getStringExtra(EXTRA_KEY) ?: return START_STICKY
                if (intent.action == ACTION_RESOLVE) markFound(key, intent.getBooleanExtra(EXTRA_FOUND, true))
                else intent.getStringExtra(EXTRA_ADDRESS)?.let { askDult(key, it) }
                return START_STICKY
            }
        }

        // Already scanning: nothing to do. Running but not scanning means the last scan
        // failed, and a start from the UI is the user asking for another go.
        if (running && scanActive) return START_STICKY
        if (running) {
            retryJob?.cancel()
            retryJob = null
            startScanning()
            return START_STICKY
        }
        running = true

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildOngoing(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // Each refusal has its own remedy, so each gets its own message. They all
            // used to read "Location permission required", which sent someone whose
            // permissions were fine off to the settings page after a background start
            // was simply refused.
            val message = when (e) {
                // A location-typed foreground service is refused without location access.
                is SecurityException -> "Location permission required to scan"
                // Android 12+ refuses to start a foreground service from the background
                // (boot, a stale notification tap); the user has to bring Clairix to the front.
                is ForegroundServiceStartNotAllowedException ->
                    "Android blocked starting the scanner in the background — open Clairix and tap Start"
                else -> "The scanner could not start (${e.javaClass.simpleName})"
            }
            running = false
            Registry.update { it.copy(scanning = false, error = message) }
            stopSelf()
            return START_NOT_STICKY
        }

        // A previous run that ended without reaching onDestroy (killed outright, or by an
        // update) left a heartbeat newer than any stop on record. Its end goes on the
        // timeline, dated by that heartbeat, before this run's first heartbeat replaces it;
        // whether it was on is read before this start turns scanning on.
        val unrecordedStop = ScanRecord.claimSystemStop(this, AppSettings.scanEnabled)
        AppSettings.setScanEnabled(this, true)
        // Running again: whatever ended the last run is history now (the timeline keeps it).
        Registry.update { it.copy(stoppedBySystem = false, stoppedAt = 0L) }
        registerSystemReceiver()
        // An app installed or changed while the scanner runs is checked for stalkerware a few
        // seconds later; Android only tells a receiver registered at run time.
        StalkwareWatch.watchPackages(this)
        // What is connected at this moment, before the ACL broadcasts take over; the
        // profile proxies answer asynchronously, so this runs off the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val live = runCatching { BluetoothExposure.connectedDevices(this@ScanService) }.getOrDefault(emptySet())
            Registry.replaceBluetoothConnected(live)
        }
        // The Karma canary: a made-up network name the platform probes for, registered
        // afresh on every start (whatever an earlier run left is withdrawn first) and
        // withdrawn in onDestroy. A refusal only means no canary; the Wi-Fi tab says so.
        runCatching { KarmaCanary.start(this) }
        // The TLS canary runs while the scanner does, so a phone without comms still
        // learns of a proxy on its network; with comms it also watches the relay's key.
        runCatching { TlsCanary.start(this) }

        startScanning()
        startLocation()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) gnssHandle = GnssMonitor.acquire(this, foreground = false)
        startCellPolling()
        startPublishing()

        val startedAt = System.currentTimeMillis()
        // One coroutine for both rows, so the earlier stop is written before this start.
        lifecycleScope.launch(Dispatchers.IO) {
            unrecordedStop?.let { eventLog.record(ScannerHealth.systemStopEvent(it)) }
            eventLog.record(
                TimelineEvent(
                    id = "scan_start@$startedAt", kind = EventKind.SCAN_START,
                    ts = startedAt, title = "Scanning started", detail = "",
                    severity = Severity.LOW
                )
            )
            Registry.publishTimeline(eventLog.snapshot())
        }

        return START_STICKY
    }

    /**
     * Settings > Battery counts from the scan's start ([ScanPower]): the battery now and
     * the scanner's own counters. Low power ([LowPower]) coming on or going off: the
     * settle threshold, the Bluetooth scan and the ongoing notification follow at once;
     * the cell and Wi-Fi loops read it on their next cycle. Debug builds also listen for
     * [DebugPace]'s request to settle now, for the emulator's power checks.
     */
    private fun watchPower() {
        val now = System.currentTimeMillis()
        ScanPower.start(now, LowPowerMode.sample(this, now))
        lifecycleScope.launch {
            LowPowerMode.state.map { it.active }.distinctUntilChanged().collect { low ->
                motion.stillAfterMs = LowPower.pacing(low).stillAfterMs
                if (running && scanActive && blePlan(getSystemService(BluetoothManager::class.java)?.adapter) != activeBleScan) {
                    restartScan()
                }
                updateOngoing()
                publishKick.trySend(Unit)
            }
        }
        if (BuildConfig.DEBUG) lifecycleScope.launch {
            DebugPace.requests.collect { motion.forceStill()?.let { applyPace(it) } }
        }
    }

    private fun registerSystemReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        locationWasOn = locationEnabled()
        ContextCompat.registerReceiver(
            this, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // The battery and Android's battery saver, which come by broadcast too (LowPowerMode).
        watchPower()
    }

    private fun restartScan() {
        if (!running) return
        stopScanning()
        startScanning()
    }

    private fun stopScanning() {
        if (!scanActive) return
        try {
            if (granted(Manifest.permission.BLUETOOTH_SCAN)) scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
            // Adapter turned off underneath us; nothing left to stop.
        }
        scanActive = false
        ScanPower.effort?.ble(null, batched = false, System.currentTimeMillis())
    }

    private fun startScanning() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Registry.update { it.copy(scanning = false, bluetoothOn = false, error = "Bluetooth is off") }
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
            Registry.update { it.copy(scanning = false, error = "Nearby devices permission not granted") }
            return
        }
        // The first minutes of a drive get the intensive sweep — see [beginTrip]. With
        // the screen off nobody is watching the list, so the controller may hold
        // results and hand them over in batches: the CPU sleeps between them instead of
        // waking for every advert. Not during a drive, where a few seconds' delay puts
        // a roadside camera hundreds of metres from where it was heard. Settled with
        // the screen off, the scan drops to low power with a batch a minute; see
        // [ScanPacing.bleScan].
        val plan = blePlan(adapter)
        val settings = ScanSettings.Builder()
            .setScanMode(plan.mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false).setReportDelay(plan.reportDelayMs).build()

        // Android 8.1+ suspends an *unfiltered* scan once the screen goes off, so a
        // filter list is the price of scanning with the phone in a pocket. But any
        // filter list is also a whitelist, and the whole point of this app is the
        // hardwired GPS/LTE box that advertises nothing anyone has catalogued —
        // scanning only for Apple, Samsung and Tile made the headline case
        // undetectable. So: no filters while the screen is on, which is when unknown
        // devices can be discovered, and while it is off, when the scan would otherwise
        // stop entirely, the known-tracker filters plus one for each device already
        // under watch that they would miss ([ScreenOffWatch]).
        val filters = if (screenOn) emptyList() else buildScanFilters()
        try {
            scanner?.startScan(filters, settings, scanCallback)
            scanActive = true
            activeBleScan = plan
            scanStartedAt = System.currentTimeMillis()
            ScanPower.effort?.ble(plan.mode, batched = plan.reportDelayMs > 0L, scanStartedAt)
            Registry.update { it.copy(scanning = true, bluetoothOn = true, error = null, stoppedBySystem = false, stoppedAt = 0L) }
        } catch (e: SecurityException) {
            Registry.update { it.copy(scanning = false, error = "Scan denied: ${e.message}") }
        } catch (e: IllegalStateException) {
            Registry.update { it.copy(scanning = false, error = "Bluetooth unavailable: ${e.message}") }
        }
    }

    /** The scan [startScanning] registers now, from the drive, the screen and the pace; see [ScanPacing.bleScan]. */
    private fun blePlan(adapter: BluetoothAdapter?): ScanPacing.BleScan = ScanPacing.bleScan(
        configuredMode = AppSettings.scanMode,
        intensive = System.currentTimeMillis() < intensiveUntil,
        screenOn = screenOn,
        still = motion.pace == MotionPolicy.Pace.STILL,
        inTrip = vehicleTrips.inTrip,
        batchingSupported = adapter != null && runCatching { adapter.isOffloadedScanBatchingSupported }.getOrDefault(false),
        lowPower = LowPowerMode.active
    )

    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Registry.update { it.copy(hasFix = false) }
            return
        }
        motion.start(System.currentTimeMillis())
        applyPace(MotionPolicy.Pace.MOVING)
    }

    /**
     * Moving: high accuracy, not balanced. Balanced power returns Wi-Fi/cell fixes of
     * ~100 m or worse, and ObservationArea discards anything over 50 m, so places never
     * accumulated and nothing could ever be confirmed as following.
     *
     * Settled ([MotionPolicy]): a phone that is going nowhere makes no new places, and
     * GPS every fifteen seconds was most of the scanner's battery use. A Wi-Fi/cell fix
     * every few minutes keeps the position fresh for the cell checks that need one,
     * and with no distance filter they get it. The significant-motion sensor, or the
     * next fix that has moved, brings GPS back.
     *
     * The same request goes to whichever source serves it ([LocationClient]): Google
     * Play services where the phone has it, Android's own location service where it
     * does not or where Google's fails the request, the satellites alone with
     * Satellite-only positioning on. See [LocationPolicy.scannerFixes].
     */
    private fun applyPace(pace: MotionPolicy.Pace) {
        // The phone settling ends a journey; see [summariseRiders].
        if (pace == MotionPolicy.Pace.STILL) summariseRiders(System.currentTimeMillis())
        // Settings > Battery: how long the phone was moving (precise fixes) and settled.
        ScanPower.effort?.pace(
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) pace == MotionPolicy.Pace.MOVING else null,
            System.currentTimeMillis()
        )
        // The Bluetooth scan follows the pace while the screen is off: settled, it drops
        // to low power with a batch a minute, and moving brings the usual scan back.
        // Registered afresh only when its settings change; see [ScanPacing.bleScan].
        if (running && scanActive && !screenOn &&
            blePlan(getSystemService(BluetoothManager::class.java)?.adapter) != activeBleScan
        ) restartScan()
        // Moving ends an idle spell: the publish loop leaves its minute-long wait now.
        if (pace == MotionPolicy.Pace.MOVING) publishKick.trySend(Unit)
        if (!running || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            // A request again replaces the earlier one.
            location.request(LocationPolicy.scannerFixes(pace, SETTLED_FIX_MS))
        } catch (e: SecurityException) {
            Registry.update { it.copy(hasFix = false, error = "Location denied: ${e.message}") }
            return
        }
        val sensors = getSystemService(SensorManager::class.java)
        val sensor = motionSensor
        if (sensors != null && sensor != null) {
            if (pace == MotionPolicy.Pace.STILL) sensors.requestTriggerSensor(motionTrigger, sensor)
            else sensors.cancelTriggerSensor(motionTrigger, sensor)
        }
        GnssMonitor.setSettled(pace == MotionPolicy.Pace.STILL)
    }

    // Cell sampling reads the modem and writes the baseline to disk. Both belong off
    // the main thread — on it, every poll janked the UI of whatever was on screen.
    private fun startCellPolling() {
        lifecycleScope.launch(Dispatchers.IO + loopGuard) {
            // A poll that failed leaves no reading standing as if it had been judged:
            // the Cell tab and its hub tile say there is none until a poll succeeds.
            val onFailure: (Throwable) -> Unit = { e ->
                Registry.publishCell(
                    CellStatus(available = false, reason = "The cell check failed (${e.javaClass.simpleName}); trying again", analysing = true)
                )
            }
            while (isActive) guarded("cell", CELL_FAILURE_PAUSE_MS, onFailure) {
                val reason = cellMonitor.unavailableReason()
                if (reason != null) {
                    Registry.publishCell(CellStatus(available = false, reason = reason))
                } else {
                    val snapshot = cellMonitor.sampleFull()
                    ScanPower.effort?.cellReading()
                    if (snapshot == null) {
                        Registry.publishCell(
                            CellStatus(
                                available = false,
                                reason = "Waiting for cell data — the modem has not reported a serving cell yet",
                                radio = runCatching { cellMonitor.radioInfo() }.getOrNull(),
                                analysing = true
                            )
                        )
                    } else {
                        val cell = snapshot.cell
                        runCatching { app.clairix.analysis.CellularExposure.noteServing(this@ScanService, cell) }
                        val fix = track.current
                        val findings = imsiCatcher.recordAndAnalyze(cell, fix, settled = motion.confirmedStill(System.currentTimeMillis()))
                        val (score, level) = imsiCatcher.scoreAndLevel(findings)
                        val (knownCells, visits, maturity) = imsiCatcher.stats()
                        Registry.publishCell(
                            CellStatus(
                                available = true, cell = cell, findings = findings,
                                score = score, level = level,
                                mature = maturity >= 1f, maturity = maturity,
                                knownCells = knownCells, visits = visits,
                                radio = runCatching { cellMonitor.radioInfo() }.getOrNull(),
                                neighbors = snapshot.neighbors,
                                analysing = true
                            )
                        )
                        if (level.ordinal >= Threat.MEDIUM.ordinal && findings.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            val evt = TimelineEvent(
                                id = "catcher@${cell.tac ?: cell.cellId}@$now", kind = EventKind.CATCHER,
                                ts = now, title = findings.first().title,
                                detail = findings.joinToString("; ") { it.id },
                                severity = when (level) {
                                    Threat.CRITICAL -> Severity.CRITICAL
                                    Threat.HIGH -> Severity.HIGH
                                    else -> Severity.MEDIUM
                                },
                                lat = fix?.lat, lon = fix?.lon
                            )
                            if (eventLog.record(evt, "catcher@${cell.tac ?: cell.cellId}")) {
                                Registry.publishTimeline(eventLog.snapshot())
                                notifyCatcher(findings.first().title)
                            }
                        }
                    }
                }
                // The modem is asked for a fresh reading each time. While the phone is
                // in use or on the move cells change quickly; screen off, every thirty
                // seconds; settled with a recent fix to show it, once a minute; in low
                // power every two minutes at most. See [ScanPacing.cellPollMs].
                delay(
                    ScanPacing.cellPollMs(
                        screenOn = screenOn, moving = motion.pace == MotionPolicy.Pace.MOVING,
                        confirmedStill = motion.confirmedStill(System.currentTimeMillis()),
                        lowPower = LowPowerMode.active
                    )
                )
            }
        }
    }

    private fun startPublishing() {
        lifecycleScope.launch(Dispatchers.Default + loopGuard) {
            // The latest packet the last published list is known to hold, or null to
            // publish on the next cycle; see [ScanPacing.publishDue].
            var publishedHeard: Long? = null
            while (isActive) guarded("publish", PUBLISH_FAILURE_PAUSE_MS) {
                val now = System.currentTimeMillis()
                val pruned = tracker.prune(now)
                // Screen off, settled and no drive: see [ScanPacing].
                val idle = ScanPacing.idle(screenOn, still = motion.pace == MotionPolicy.Pace.STILL, inTrip = vehicleTrips.inTrip)
                // Whether the scan can hear at all: Location off hides every result, and
                // an unfiltered scan that has heard nothing for five minutes is not
                // listening. The cause goes on the status (the hub reads it as "Scanner
                // cannot hear") and, once per loss, to the shade; the heartbeat says the
                // service was alive this minute, for the hub to read once it is gone.
                val cause = ScannerHealth.deafCause(
                    scanActive = scanActive, locationOn = locationEnabled(), unfiltered = screenOn,
                    lastPacketAt = lastPacketAt, scanStartedAt = scanStartedAt, now = now
                )
                val heard = lastPacketAt
                val errors = packetErrors.get()
                Registry.update {
                    it.copy(
                        error = ScannerHealth.reconcileError(it.error, cause),
                        lastPacketAt = ScannerHealth.publishedHeard(it.lastPacketAt, heard),
                        packetErrors = errors
                    )
                }
                if (cause != null && cause != deafCause) notifyCannotHear(cause, now)
                deafCause = cause
                if (ScannerHealth.heartbeatDue(lastHeartbeatAt, now)) {
                    lastHeartbeatAt = now
                    ScanRecord.heartbeat(this@ScanService, now)
                }
                if (ScanPacing.retentionDue(lastRetentionAt, now)) {
                    lastRetentionAt = now
                    withContext(Dispatchers.IO) { runCatching { HistoryRetention.apply(this@ScanService) } }
                }
                if (now - knownCamerasAt > KNOWN_CAMERAS_REFRESH_MS) {
                    knownCamerasAt = now
                    withContext(Dispatchers.IO) {
                        // Re-read, and gunzip, only when a list or its signature record
                        // changed on disk: the lists are refreshed once a day at most.
                        val stamp = runCatching { CameraFeed.cacheStamp(this@ScanService) }.getOrNull()
                        if (stamp == null || stamp != knownCamerasStamp) {
                            val loaded = runCatching { CameraFeed.loadCached(this@ScanService) }
                            knownCameras = loaded.getOrDefault(emptyList())
                            knownCamerasStamp = if (loaded.isSuccess) stamp else null
                        }
                    }
                }
                // Fused location goes quiet once the car is parked (25 m minimum
                // displacement), so the end of a trip is noticed here, not in the
                // location callback.
                if (vehicleTrips.inTrip && now - lastDrivingAt > TRIP_END_MS) endTrip(now)
                // An identity behind a confirmed follower must outlive the identity
                // window, or the same device comes back as a stranger and the original
                // row is orphaned as a permanent "following" ghost.
                identities.prune(now, keep = tracker.followingKeys())
                // Trust in a device that rotates its address lasts a day; then it is asked about again.
                Registry.expireTrust(now)
                val trusted = Registry.trusted.value
                // Idle, the list and the map are rebuilt only when a packet was handled or
                // a device aged out since the last publish: nothing else changes them, and
                // the screen coming on ends the idle spell. See [ScanPacing.publishDue].
                val list: List<Detection>
                if (ScanPacing.publishDue(idle, heard, publishedHeard, pruned)) {
                    publishedHeard = ScanPacing.publishedMark(heard, System.currentTimeMillis())
                    val all = tracker.snapshot(now)
                    // Only surface devices worth the user's attention; transient single-sighting
                    // signals from people walking by are tracked internally but not listed.
                    list = all.filter { d ->
                        d.following || d.persistent ||
                            (d.tracker != null && d.sightings >= 3) ||
                            d.sightings >= 5
                    }.sortedWith(
                        compareByDescending<Detection> { it.following }
                            .thenByDescending { it.persistent }
                            .thenByDescending { it.identified }
                            .thenByDescending { it.sightings }
                    )
                    Registry.update { it.copy(nearbyCount = all.size) }
                    Registry.publish(list)

                    val trail = synchronized(gpsTrail) { ArrayList(gpsTrail) }
                    // Ordered by key, not by the sighting-sorted `list`: the map only cares
                    // where each device is, and sightings change on nearly every advert, so
                    // the sighting order made every 1.5 s MapData compare unequal. Each one
                    // then re-ran the map's overlay effect and invalidated the MapView, and
                    // every redraw re-requests any expired tile from the tile server.
                    // With a stable order the StateFlow drops publishes that change nothing.
                    // A device the owner marked as theirs, or as found, is no longer drawn
                    // (or counted on the map's banner) as moving with them.
                    val deviceTrails = list.filter { it.points.isNotEmpty() }.map {
                        val withYou = it.following && !it.resolved && it.address !in trusted
                        DeviceTrail(it.key, it.name, it.threat, withYou, it.points, it.lastHeardAt)
                    }.sortedBy { it.key }
                    val cellNow = Registry.cell.value
                    val cellMarkers = if (cellNow.level.ordinal >= Threat.MEDIUM.ordinal && cellNow.cell != null) {
                        val fix = track.current
                        if (fix != null) listOf(
                            CellMarker("${cellNow.cell.rat.label} ${cellNow.cell.cellId}",
                                LatLon(fix.lat, fix.lon), cellNow.level)
                        ) else emptyList()
                    } else emptyList()
                    Registry.publishMap(MapData(trail, deviceTrails, cellMarkers))
                    // Screen off: a device that has become worth watching gets a filter of its own.
                    if (!screenOn) refreshWatchedFilters(all, trusted, now)
                } else {
                    // Unchanged since it was published; the correlation check below reads it.
                    list = Registry.detections.value
                }
                // The ongoing notification says only that the scanner runs (the counts
                // are on the Scan hub); this re-posts it only when that text changes.
                updateOngoing()

                // Phone health: at once, then every 30 s while the screen is on, every
                // five minutes while it is off: what it reads changes when someone
                // is using the phone, and the microphone and camera are watched
                // through callbacks in between.
                publishCycle++
                val healthEvery = if (screenOn) 30_000L else 5 * 60_000L
                if (publishCycle == 1 || now - lastHealthAt >= healthEvery) {
                    lastHealthAt = now
                    // The stalkerware check, once a day: every installed app scored and matched
                    // against the list, compared with the last check, and what is new alerted. Off
                    // this loop, which it would hold up for a second; it publishes its own result.
                    lifecycleScope.launch(Dispatchers.IO) { runCatching { StalkwareWatch.checkIfDue(this@ScanService, foreground = false) } }
                    phoneHealthMonitor.scanAndPublish()
                    // Unlock ledger: pull the platform's keyguard events and judge
                    // the sleep window, so a night-time unlock is reported while
                    // the owner is still asleep rather than when they next open Clairix.
                    withContext(Dispatchers.IO) { runCatching { UnlockLedger.refresh(this@ScanService) } }
                }

                // Wi-Fi anomaly scan: at once, then every two minutes while the screen is
                // on or the phone moves, and every ten settled with the screen off, where
                // the networks around do not change; in low power every fifteen at most.
                // The Wi-Fi tab shows these results while the scanner runs and asks for no
                // scans of its own; see [ScanPacing.wifiScanEveryMs].
                val wifiEvery = ScanPacing.wifiScanEveryMs(
                    screenOn, moving = motion.pace == MotionPolicy.Pace.MOVING, lowPower = LowPowerMode.active
                )
                if (publishCycle == 1 || now - lastWifiAt >= wifiEvery) {
                    lastWifiAt = now
                    ScanPower.effort?.wifiScan()
                    // A new canary name every few hours, before the scan below asks for it,
                    // and another try at one Android refused; see [KarmaCanary.rotationDue].
                    runCatching { KarmaCanary.rotateIfDue(this@ScanService, now) }
                    // The picture the WIFI tab shows, then the judgement on it. The
                    // snapshot requests a fresh scan and waits for its results, so both
                    // it and the anomaly scan below read this cycle's beacons rather than
                    // whatever the platform cached last time.
                    val wifiStatus = runCatching { WifiScanner.snapshot(this@ScanService) }.getOrNull()
                    wifiStatus?.let { Registry.publishWifiStatus(it) }
                    // A Flock unit's own Wi-Fi, when it is running it: the setup hotspot or
                    // an address in Flock's own hardware block.
                    wifiStatus?.nearby?.forEach { n ->
                        SurveillanceHardware.matchWifi(n.ssid, n.bssid)?.let { hit ->
                            onInfrastructure(hit, BleNames.formatMac(n.bssid), n.rssi, System.currentTimeMillis())
                        }
                    }
                    val wifiAnomalies = WifiScanner.scan(this@ScanService)
                    // The same scan cache, checked for the one network name that exists
                    // nowhere but inside this phone. An answer has no benign explanation.
                    val karmaHits = KarmaCanary.scan(this@ScanService)
                    Registry.publishWifi(wifiAnomalies + karmaHits)
                    for (hit in karmaHits) {
                        Registry.publishAlert(
                            Alert(
                                id = "karma@${hit.bssid}@$now", ts = now, severity = Severity.CRITICAL,
                                title = "A fake Wi-Fi hotspot is nearby",
                                detail = KarmaCanary.describe(hit),
                                kind = EventKind.WIFI_ANOMALY, dedupeKey = "karma@${hit.bssid}"
                            )
                        )
                    }
                    // The LOW flags are information for the Wi-Fi tab (an open carrier
                    // hotspot, which is what xfinitywifi and attwifi are by design):
                    // no timeline event and no part in the correlation below. The old
                    // code wrote a HIGH event per open hotspot every half hour.
                    val wifiBait = wifiAnomalies.filter { it.threat.ordinal > Threat.LOW.ordinal }
                    if (wifiBait.isNotEmpty()) {
                        val top = wifiBait.maxByOrNull { it.threat.ordinal }!!
                        val evt = TimelineEvent(
                            id = "wifi@${top.bssid}@$now",
                            kind = EventKind.WIFI_ANOMALY,
                            ts = now, title = "Wi-Fi anomaly: ${top.ssid}",
                            detail = "${wifiBait.size} suspicious network(s) — ${top.reason}",
                            severity = when (top.threat) {
                                Threat.CRITICAL -> Severity.CRITICAL
                                Threat.HIGH -> Severity.HIGH
                                else -> Severity.MEDIUM
                            },
                            lat = track.current?.lat, lon = track.current?.lon
                        )
                        if (eventLog.record(evt, "wifi@${top.bssid}")) {
                            Registry.publishTimeline(eventLog.snapshot())
                        }
                    }

                    // Correlated surveillance: persistent BLE tracker + active cell
                    // anomaly + Wi-Fi bait. Bait is a HIGH anomaly (evil twin, Wi-Fi
                    // interception kit) or the canary being answered — the one Wi-Fi
                    // signal with no benign explanation, which used to be left out
                    // while an open xfinitywifi counted.
                    if (Registry.cell.value.level.ordinal >= Threat.HIGH.ordinal) {
                        val correlatedBle = list.filter {
                            it.persistent && !it.following && it.address !in trusted
                        }
                        if (correlatedBle.isNotEmpty() && (wifiBait.isNotEmpty() || karmaHits.isNotEmpty())) {
                            val wifiPart = if (karmaHits.isNotEmpty()) "a fake Wi-Fi hotspot" else "Wi-Fi bait"
                            val evt2 = TimelineEvent(
                                id = "corr@${correlatedBle.first().key}@$now",
                                kind = EventKind.CORRELATED_SURVEILLANCE,
                                ts = now,
                                title = "Correlated surveillance indicators",
                                detail = "${correlatedBle.size} persistent BLE device(s) + active cell anomaly + $wifiPart",
                                severity = Severity.CRITICAL,
                                lat = track.current?.lat, lon = track.current?.lon
                            )
                            if (eventLog.record(evt2, "corr@${correlatedBle.first().key}")) {
                                Registry.publishTimeline(eventLog.snapshot())
                            }
                        }
                    }
                }

                motion.tick(now)?.let { pace -> withContext(Dispatchers.Main) { applyPace(pace) } }

                // The list and the map are for someone looking at them: every 1.5 s
                // while the screen is on, every 10 s while it is off, and once a minute
                // at most once the phone has settled. Alerts do not wait for this; they
                // are raised as packets arrive. See [ScanPacing.publishWaitMs].
                val idleNext = ScanPacing.idle(screenOn, still = motion.pace == MotionPolicy.Pace.STILL, inTrip = vehicleTrips.inTrip)
                withTimeoutOrNull(ScanPacing.publishWaitMs(screenOn, idleNext)) { publishKick.receive() }
            }
        }
    }

    private fun handle(result: ScanResult) {
        lastPacketAt = System.currentTimeMillis()
        // Every packet, unfiltered, for the finder and the self-test that follow the
        // stream; neither needs a scan of its own.
        Registry.publishScan(result)
        val record = result.scanRecord
        // Our own fake AirTag: the canary has just been told (and runs the record through
        // the tracker signatures itself); it must not become a detection or get interrogated.
        if (BleCanary.isCanary(record)) return
        // Read off the record once, for the benign check here and the signatures below.
        val advert = record?.let { Advert.of(it) }
        if (advert != null && Signatures.isBenign(advert)) return

        val now = System.currentTimeMillis()
        val device = result.device
        val address = device.address?.let { BleNames.formatMac(it) } ?: return
        // Fixed surveillance hardware announcing itself: a plate reader's battery, a
        // gunshot detector. Reported in its own right, before the trust check (marking
        // it "mine" makes no sense) and without leaving the tracker pipeline: a camera
        // on a trailer that keeps turning up is still a device following the car.
        SurveillanceHardware.match(record, device, address)?.let { onInfrastructure(it, address, result.rssi, now) }
        val fingerprint = Fingerprint.of(record)
        val resolution = identities.resolve(address, result.rssi, fingerprint, now)
        // The identity resolver links a rotation when the payload matches. When it does
        // not — a Find My key change rotates the whole payload — the radio's cadence can
        // still prove the new address is the same device; its sightings then move under
        // the id the device already had.
        val stitched = cadence.stitch(result)
        val certain = stitched?.certain == true
        // Two units of one product that only their cadence tells apart: the stitch may
        // have swapped them, and a verdict the old address earned must not carry over.
        val indistinguishable = stitched?.indistinguishable == true
        val key = if (stitched != null && stitched.logicalId != resolution.identity.id) {
            tracker.merge(resolution.identity.id, stitched.logicalId, certain, indistinguishable)
            stitched.logicalId
        } else {
            cadence.assign(address, resolution.identity.id)
            resolution.identity.id
        }
        cadence.supersededKey(address)?.let { tracker.merge(it, key, certain, indistinguishable) }
        // Skip the owner's own devices: by the address they marked, or by the logical
        // device it belongs to, so an address change the scanner links stays theirs.
        // Checked after the resolvers on purpose: a trusted device's packets keep its
        // identity and cadence current, which is what links its next address to it.
        val trust = trustedIdentities.check(key, address, Registry.trusted.value)
        if (trust.trusted) {
            val known = trust.knownAddress
            trust.learned?.let { if (known != null) Registry.extendTrust(it, known) }
            return
        }
        val tracked = advert?.let { Signatures.match(it) }
        val txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }

        // Identification is display-only and resolved here, once per packet, from the
        // SIG assigned numbers: who made it, what it calls itself, what it advertises.
        // The tracker keeps the best of these across packets and derives the row name.
        val observation = tracker.observe(
            key = key, address = address,
            rssi = result.rssi, tracker = tracked,
            approxMetres = Signatures.approximateMetres(result.rssi, txPower),
            rotations = resolution.identity.rotations,
            addresses = resolution.identity.addresses.size,
            fix = track.current, hasPosition = track.hasFix(), now = now, travelledM = track.travelledM,
            advertisedName = BleNames.advertisedName(this, record, device),
            manufacturer = BleNames.manufacturer(record),
            services = BleNames.services(record),
            radio = BleNames.radio(this, device),
            addressKind = BleNames.addressKind(device, address)
        )

        // A recognised tracker is announced the moment it is confirmed; an unidentified
        // co-traveller is held for one notice when the journey ends (FollowAlertPolicy).
        // The timeline records every confirmation as it happens either way.
        val verdict = observation.becameFollowing || observation.escalated
        if (verdict && followAlerts.onVerdict(observation.detection) == FollowAlertPolicy.Decision.ANNOUNCE) {
            notifyFollowing(observation.detection)
        }
        if (observation.escalated) {
            val fix = track.current
            recordEvent(
                TimelineEvent(
                    id = "separated@${observation.detection.key}@$now",
                    kind = EventKind.FOLLOWING,
                    ts = now,
                    title = followingTitle(observation.detection),
                    detail = "Now advertising as ${observation.detection.tracker?.label ?: "a tracker"} away from its owner; " +
                        "confirmed over ${observation.detection.displacementM.toInt()} m, ${observation.detection.sightings} sightings",
                    severity = Severity.CRITICAL,
                    lat = fix?.lat, lon = fix?.lon
                ),
                dedupeKey = "separated@${observation.detection.key}"
            )
        }
        if (observation.becameFollowing && alerted.add(observation.detection.key)) {
            val fix = track.current
            recordEvent(
                TimelineEvent(
                    id = "following@${observation.detection.key}@$now",
                    kind = EventKind.FOLLOWING,
                    ts = now,
                    title = followingTitle(observation.detection),
                    detail = "Confirmed over ${observation.detection.displacementM.toInt()} m, ${observation.detection.sightings} sightings",
                    severity = if (observation.detection.threat == Threat.CRITICAL) Severity.CRITICAL else Severity.HIGH,
                    lat = fix?.lat, lon = fix?.lon
                ),
                dedupeKey = "following@${observation.detection.key}"
            )
        }

        val detection = observation.detection
        if (vehicleTrips.inTrip) {
            // Drive mode on is one of the signs the journey is in the owner's car.
            vehicleTrips.sight(key, address, detection.name, detection.tracker, fingerprint, now, driveMode = DriveService.active)
                ?.let(::publishVehicleFinding)
        }
        maybeInterrogate(key, device, detection)
    }

    /**
     * Surveillance hardware heard over Bluetooth or Wi-Fi. Kept on the Bluetooth page
     * for this run, written to the timeline with the phone's position, and raised as a
     * notification for a certain match when the owner wants one. One record per
     * address per [INFRASTRUCTURE_DEDUPE_MS]: a commute passes the same camera twice a
     * day, and twice a day is enough. A possible match is remembered nowhere.
     */
    private fun onInfrastructure(hit: SurveillanceHardware.Hit, address: String, rssi: Int, now: Long) {
        if (hit.confidence == SurveillanceHardware.Confidence.POSSIBLE) return
        val fix = track.current
        var nearestM: Double? = null
        var nearestLabel: String? = null
        val cached = synchronized(nearestCameraCache) { nearestCameraCache[address] }
        if (cached != null && now - cached.first < NEAREST_CAMERA_REUSE_MS) {
            nearestM = cached.second
            nearestLabel = cached.third
        } else if (fix != null) {
            val cameras = knownCameras
            if (cameras.isNotEmpty()) {
                val here = Fix(fix.lat, fix.lon, null, now)
                var best: KnownCamera? = null
                var bestM = Double.MAX_VALUE
                for (c in cameras) {
                    // Cheap bounding box before the trigonometry: tens of thousands of cameras.
                    if (Math.abs(c.lat - fix.lat) > 0.5 || Math.abs(c.lon - fix.lon) > 0.5) continue
                    val d = haversine(here, Fix(c.lat, c.lon, null, now))
                    if (d < bestM) { bestM = d; best = c }
                }
                if (best != null) { nearestM = bestM; nearestLabel = best.summary }
            }
            synchronized(nearestCameraCache) { nearestCameraCache[address] = Triple(now, nearestM, nearestLabel) }
        }
        val kept = Registry.publishInfrastructure(
            InfrastructureSighting(
                address = address, kind = hit.kind, confidence = hit.confidence, rssi = rssi,
                firstSeen = now, lastSeen = now, sightings = 1, evidence = hit.evidence,
                lat = fix?.lat, lon = fix?.lon, nearestKnownM = nearestM, nearestKnownLabel = nearestLabel
            )
        )
        val mapNote = when {
            nearestM != null && nearestM <= KNOWN_CAMERA_MATCH_M ->
                "It matches the plate reader mapped ${nearestM.toInt()} m away" + (nearestLabel?.let { " ($it)" } ?: "") + "."
            nearestM != null ->
                "No plate reader is mapped within ${KNOWN_CAMERA_MATCH_M.toInt()} m of here (nearest ${"%.1f".format(nearestM / 1000)} km): " +
                    "this may be one OpenStreetMap does not have yet."
            fix == null -> "No position fix, so it cannot be compared with the map."
            else -> "No plate-reader list is on this phone to compare it with."
        }
        // The page row above is refreshed on every packet; the timeline and the shade
        // hear about each address once per window, judged here rather than left to the
        // timeline's dedupe, which runs after the alert list has already grown by one.
        synchronized(infrastructureAlertedAt) {
            val last = infrastructureAlertedAt[address] ?: 0L
            if (now - last < INFRASTRUCTURE_DEDUPE_MS) return
            infrastructureAlertedAt[address] = now
        }
        val certain = hit.confidence == SurveillanceHardware.Confidence.CERTAIN
        val severity = if (certain && AppSettings.infrastructureAlerts) Severity.MEDIUM else Severity.LOW
        Registry.publishAlert(
            Alert(
                id = "infra@$address@$now", ts = now, severity = severity,
                title = if (certain) hit.kind.headline else "Possible ${hit.kind.label.lowercase()} nearby",
                detail = "${hit.kind.label} heard at ${BleNames.signalLabel(rssi)} signal ($rssi dBm): " +
                    hit.evidence.joinToString("; ") + ". " + mapNote +
                    (if (kept.sightings > 1) " Heard ${kept.sightings} times since ${android.text.format.DateFormat.format("HH:mm", kept.firstSeen)}." else ""),
                kind = EventKind.INFRASTRUCTURE, dedupeKey = "infra@$address",
                lat = fix?.lat, lon = fix?.lon
            ),
            dedupeWindowMs = INFRASTRUCTURE_DEDUPE_MS
        )
    }

    /**
     * Asks a tracker who it is over DULT when [DultSchedule] says so: once it qualifies
     * (a type whose maker ships the protocol that has stayed around, or anything
     * confirmed following), again as soon as it separates from its owner, and every
     * fifteen minutes while it still qualifies and has not answered, six times at
     * most. Making the tracker sound is left to the user — [DultInterrogator.sound].
     */
    private fun maybeInterrogate(key: String, device: BluetoothDevice, detection: Detection) {
        val qualifies = DultSchedule.qualifies(detection)
        // Every packet passes here: nothing to decide for a device that neither
        // qualifies nor has separated.
        if (!qualifies && detection.separatedAt == 0L) return
        val claim = dultSchedule.claim(key, qualifies, detection.separatedAt, System.currentTimeMillis()) ?: return
        if (!canAskDult()) {
            dultSchedule.abandoned(key)
            return
        }
        interrogate(key, device, claim)
    }

    /** Bluetooth is on and the Nearby devices permission allows a connection. */
    private fun canAskDult(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        return adapter != null && adapter.isEnabled && granted(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * Runs one DULT exchange with [device] for [key] on the service scope, never on the
     * scan callback, one GATT exchange at a time. Every result is stamped with when the
     * exchange began and which it was, so the locator can say "Asked 12:04 — refused";
     * the exchange is recorded with the schedule only once it has ended. It has its own
     * budget, and the collector a slightly longer one in case the stack never answers.
     */
    private fun interrogate(key: String, device: BluetoothDevice, claim: DultSchedule.Claim) {
        lifecycleScope.launch(Dispatchers.IO) {
            dultGate.withLock {
                val startedAt = System.currentTimeMillis()
                var last = DultResult(BleNames.formatMac(device.address ?: ""), askedAt = startedAt, attempt = claim.attempt)
                try {
                    withTimeoutOrNull(DultInterrogator.TIMEOUT_MS + DULT_GRACE_MS) {
                        DultInterrogator.interrogate(device, this@ScanService).collect { r ->
                            last = r.copy(askedAt = startedAt, attempt = claim.attempt)
                            if (!r.complete) tracker.attachDult(key, last)
                        }
                    }
                } finally {
                    val ended = if (last.complete) last
                    else last.copy(complete = true, error = last.error ?: "No answer within ${(DultInterrogator.TIMEOUT_MS + DULT_GRACE_MS) / 1000} s")
                    val retryAt = dultSchedule.finished(key, claim, startedAt, ended.answered)
                    tracker.attachDult(key, ended.copy(retryAt = retryAt))
                }
            }
        }
    }

    /**
     * "Ask it who it is" from the locator: one exchange now, whatever the schedule has
     * done so far, unless one with this device is already under way.
     */
    private fun askDult(key: String, address: String) {
        val now = System.currentTimeMillis()
        if (!canAskDult()) {
            tracker.attachDult(
                key,
                DultResult(address, complete = true, askedAt = now, error = "Bluetooth is off or the Nearby devices permission is not granted")
            )
            return
        }
        val device = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val separatedAt = Registry.detections.value.firstOrNull { it.key == key }?.separatedAt ?: 0L
        val claim = dultSchedule.claimManual(key, separatedAt) ?: return
        interrogate(key, device, claim)
        publishKick.trySend(Unit)
    }

    /**
     * "I found it" (or taking that back) from the device's row. A found device stops
     * counting as on you; it is not announced again unless it starts over, which it
     * does when it is heard after an hour away ([Tracker.observe]). The timeline keeps
     * a row saying when it was marked.
     */
    private fun markFound(key: String, found: Boolean) {
        val now = System.currentTimeMillis()
        val d = tracker.resolve(key, found, now) ?: return
        if (found) {
            followAlerts.forget(key)
            alerted.remove(key)
            // Its alert has been dealt with; the shade need not keep it.
            runCatching { getSystemService(NotificationManager::class.java)?.cancel(key.hashCode()) }
        }
        val fix = track.current
        recordEvent(
            TimelineEvent(
                id = "found@$key@$now",
                kind = EventKind.FOLLOWING,
                ts = now,
                title = if (found) "Marked found: ${d.name}" else "No longer marked found: ${d.name}",
                detail = if (found) {
                    "Marked found on this phone (${d.address}). It no longer counts as on you; if it is heard " +
                        "again after an hour away, Clairix watches it afresh."
                } else "Counted again as ${if (d.following) "moving with you" else "nearby"} (${d.address}).",
                severity = Severity.LOW,
                lat = fix?.lat, lon = fix?.lon
            )
        )
        publishKick.trySend(Unit)
    }

    /**
     * Driving is two consecutive fixes at road speed; the trip then runs until no fix
     * has shown that speed for [TRIP_END_MS]. Walking and cycling never reach it, and a
     * single spurious speed reading on its own starts nothing.
     */
    private fun updateVehicle(fix: Fix) {
        val speed = fix.speed ?: return
        if (speed < DRIVING_SPEED_MS) { drivingFixes = 0; return }
        lastDrivingAt = fix.time
        drivingFixes++
        if (drivingFixes >= 2 && !vehicleTrips.inTrip) beginTrip(fix)
    }

    /**
     * A tracker on a vehicle advertises from inches away, often only when moving. The
     * first minutes of a drive are therefore scanned at low latency — a full sweep at
     * the cost of some battery — before the scan drops back to the configured mode.
     */
    private fun beginTrip(fix: Fix) {
        vehicleTrips.beginTrip(fix.time, fix.lat, fix.lon)
        noteConnectedAccessories()
        intensiveUntil = fix.time + INTENSIVE_SWEEP_MS
        restartScan()
        intensiveJob?.cancel()
        intensiveJob = lifecycleScope.launch {
            delay(INTENSIVE_SWEEP_MS)
            intensiveJob = null
            intensiveUntil = 0L
            if (running) restartScan()
        }
    }

    /**
     * The owner's accessories connected as a drive begins, remembered on the trip so
     * the Bluetooth page can say which fixed address announces the car, and so a car
     * kit among them shows the trip is in the owner's car. Bonded records need the
     * Nearby devices permission; without it the trip simply records none.
     */
    private fun noteConnectedAccessories() {
        if (!vehicleTrips.inTrip || !granted(Manifest.permission.BLUETOOTH_CONNECT)) return
        val connected = Registry.connectedBluetooth.value
        if (connected.isEmpty()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return
        for (d in bonded) {
            val a = BluetoothExposure.accessory(this, d, connected) ?: continue
            if (a.connected) vehicleTrips.noteAccessory(a.address, a.name, car = a.car)
        }
    }

    private fun endTrip(now: Long) {
        val findings = vehicleTrips.endTrip(now)
        drivingFixes = 0
        if (intensiveUntil != 0L) {
            intensiveUntil = 0L
            intensiveJob?.cancel()
            intensiveJob = null
        }
        // Back to the configured mode, and to batching if the screen is off, on the
        // main thread, where the scan is managed.
        lifecycleScope.launch { if (running) restartScan() }
        findings.forEach(::publishVehicleFinding)
        // Parking ends a journey too; see [summariseRiders].
        summariseRiders(now)
    }

    /**
     * A device that has ridden along on enough separate journeys, through the shared
     * alert path, at the severity trip memory gave it (an address in the owner's car is
     * critical, a shared signature or a journey nothing ties to the car is not) and with
     * its own dedupe window (a day for a shared signature).
     */
    private fun publishVehicleFinding(finding: VehicleTrips.Finding) {
        Registry.publishAlert(
            Alert(
                id = finding.id, ts = System.currentTimeMillis(), severity = finding.severity,
                title = finding.title, detail = finding.detail,
                kind = EventKind.FOLLOWING, dedupeKey = finding.dedupeKey
            ),
            dedupeWindowMs = finding.dedupeWindowMs
        )
    }

    /**
     * The owner is told once each time the scan stops hearing; the hub and the
     * Trackers hero carry the cause for as long as it lasts. Through the shared
     * alert path, so the loss is on the timeline too, as a gap in cover.
     */
    private fun notifyCannotHear(cause: String, now: Long) {
        Registry.publishAlert(
            Alert(
                id = "scanner_deaf@$now", ts = now, severity = Severity.MEDIUM,
                title = "Scanner cannot hear", detail = cause,
                kind = EventKind.PLATFORM, dedupeKey = "scanner_deaf"
            )
        )
    }

    /**
     * One notice, through the shared alert path, for the unidentified devices that
     * travelled with you on the journey that has just ended — see [FollowAlertPolicy].
     * Called when the phone settles and when a drive ends; does nothing when nothing
     * was held, so the two can overlap.
     */
    private fun summariseRiders(now: Long) {
        val summary = followAlerts.onJourneyEnd(now, Registry.trusted.value) { tracker.snapshot(now) } ?: return
        val fix = track.current
        Registry.publishAlert(
            Alert(
                id = "riders@$now", ts = now, severity = Severity.MEDIUM,
                title = summary.title, detail = summary.detail,
                kind = EventKind.FOLLOWING, dedupeKey = "riders@$now",
                lat = fix?.lat, lon = fix?.lon
            )
        )
    }

    /** Records an event off the calling thread — [EventLog.record] writes to disk. */
    private fun recordEvent(event: TimelineEvent, dedupeKey: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (eventLog.record(event, dedupeKey)) Registry.publishTimeline(eventLog.snapshot())
        }
    }

    /**
     * Runs synchronously and deliberately so: when the service is not otherwise
     * running this is followed immediately by stopSelf(), and work posted to
     * lifecycleScope would be cancelled before it reached the disk. It is two small
     * JSON writes on an explicit, one-off user action.
     */
    private fun clearAllData() {
        eventLog.clear()
        imsiCatcher.resetBaseline()
        tracker.clear()
        identities.clear()
        cadence.clear()
        vehicleTrips.clear()
        dultSchedule.clear()
        trustedIdentities.clear()
        synchronized(infrastructureAlertedAt) { infrastructureAlertedAt.clear() }
        synchronized(nearestCameraCache) { nearestCameraCache.clear() }
        GnssMonitor.clear()
        runCatching { app.clairix.analysis.CellularExposure.clear(this) }
        runCatching { StalkwareWatch.clear(this) }
        // The camera sweep's findings, checklist and self-test, kept in their own store.
        runCatching { app.clairix.analysis.HiddenCameraScanner.forget(this) }
        // When each paired accessory was first seen connected (the owner's marks stay).
        runCatching { app.clairix.analysis.AudioRouteMonitor.forget(this) }
        // The radio scanner's recordings, and any plain ones an earlier version left.
        runCatching { ScannerRecordings.deleteEverything(this) }
        LocationHistory.clear(this)
        alerted.clear()
        followAlerts.clear()
        synchronized(gpsTrail) { gpsTrail.clear() }
        publishCycle = 0
        lastOngoingText = null

        Registry.reset()
        Registry.clearNfc()
        Registry.publishTimeline(emptyList())
        Registry.publishWifi(emptyList())
        // Registry.reset() puts the status back to its defaults, which would otherwise
        // leave a live scan reporting itself as idle.
        Registry.update { it.copy(scanning = running && scanActive, bluetoothOn = true) }
    }

    /**
     * Filters covering the tracker manufacturer IDs and service UUIDs worth keeping a
     * scan alive for while the screen is off, and the addresses of the devices already
     * under watch that those would miss ([ScreenOffWatch]). These are a whitelist —
     * anything not listed is invisible — which is why they are only used in that one
     * case, and the screen-on scan runs unfiltered.
     */
    private fun buildScanFilters(): List<ScanFilter> {
        // Apple's ID alone matches every iPhone, Watch and pair of AirPods in range,
        // each advertising several times a second, and every one woke the phone to
        // be thrown away by Signatures.isBenign. Only Find My (message type 0x12) and
        // iBeacon (0x02, the format cheap anti-lost tags use) can be a tracker, so the
        // filters mask to those. Samsung's 0x0075 and the two IDs
        // once listed for Tile matched phones, watches and earbuds and nothing the
        // detector uses: SmartTags and Tiles are recognised by their service UUIDs,
        // which are below.
        val manufacturerIds = listOf(
            SurveillanceHardware.COMPANY_XUNTONG, // Flock Safety camera battery packs
        )
        // 0xFE9F (Google, on nearly every Android phone) is not listed: nothing matches
        // on it, so it only woke the phone.
        val serviceUuids = listOf(
            // Checked against the Bluetooth SIG member UUID registry: Tile 0xFEED/0xFEEC/
            // 0xFD84, Samsung SmartTag 0xFD5A, Chipolo 0xFE33/0xFE65, Pebblebee 0xFA25,
            // the DULT separated-tracker advertisement 0xFCB2, Nut 0xAA01/0xAA02,
            // Google 0xFEAA (Find Hub tags and Eddystone), 0xFE85 (Invoxia),
            // Orbit/KeySmart 0xFFE0/0xFFF3 (generic HM-10 UART, paired check in Signatures).
            "0000feed-0000-1000-8000-00805f9b34fb",
            "0000feec-0000-1000-8000-00805f9b34fb",
            "0000fd84-0000-1000-8000-00805f9b34fb",
            "0000fd5a-0000-1000-8000-00805f9b34fb",
            "0000fe33-0000-1000-8000-00805f9b34fb",
            "0000fe65-0000-1000-8000-00805f9b34fb",
            "0000fa25-0000-1000-8000-00805f9b34fb",
            "0000fcb2-0000-1000-8000-00805f9b34fb",
            "0000aa01-0000-1000-8000-00805f9b34fb",
            "0000aa02-0000-1000-8000-00805f9b34fb",
            "0000feaa-0000-1000-8000-00805f9b34fb",
            "0000fe85-0000-1000-8000-00805f9b34fb",
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000fff3-0000-1000-8000-00805f9b34fb",
            // Flock Safety accessory service, so a camera's hardware is heard with the
            // screen off — which is how a phone rides in a car.
            SurveillanceHardware.ACCESSORY_SERVICE,
        )
        val filters = ArrayList<ScanFilter>()
        for (type in listOf(FIND_MY_TYPE, IBEACON_TYPE)) {
            filters.add(
                ScanFilter.Builder()
                    .setManufacturerData(APPLE_COMPANY, byteArrayOf(type), byteArrayOf(0xFF.toByte()))
                    .build()
            )
        }
        manufacturerIds.forEach { id ->
            filters.add(ScanFilter.Builder().setManufacturerData(id, byteArrayOf()).build())
        }
        // A filter on the service UUID list does not see service data, and Tile,
        // SmartTag, DULT and Find Hub tags put their UUID only in service data, so
        // with the screen off they went unheard. Those get a service-data filter.
        serviceUuids.forEach { uuid ->
            val parcel = android.os.ParcelUuid.fromString(uuid)
            val builder = ScanFilter.Builder()
            if (uuid in SERVICE_DATA_UUIDS) builder.setServiceData(parcel, ByteArray(0)) else builder.setServiceUuid(parcel)
            filters.add(builder.build())
        }
        // The devices already under watch that none of the above hears: a GPS box found
        // while the screen was on would otherwise go unheard the moment it went off.
        val now = System.currentTimeMillis()
        val watched = ScreenOffWatch.watched(tracker.snapshot(now), Registry.trusted.value)
        val registered = HashSet<String>()
        for (w in watched) {
            val filter = runCatching { addressFilter(w) }.getOrNull() ?: continue
            filters.add(filter)
            registered.add(w.address)
        }
        watchedFilterAddresses = registered
        watchedFilterAt = now
        return filters
    }

    /**
     * A filter on one device's address. The public SDK takes the address alone (the
     * overload that also takes its type is not public at compileSdk 35), so whether a
     * controller filtering in hardware matches a device with a random address this
     * way is for a device to show; the type filters still catch every recognised
     * tracker family whatever its address.
     */
    private fun addressFilter(w: ScreenOffWatch.Watched): ScanFilter =
        ScanFilter.Builder().setDeviceAddress(w.address).build()

    /**
     * With the screen off, registers the scan afresh when the devices under watch are
     * no longer the ones it has address filters for ([ScreenOffWatch.refreshDue]), so a
     * device that became worth watching gets one: at most once a minute, since Android
     * throttles an app that starts scans often. [all] is this cycle's snapshot.
     */
    private suspend fun refreshWatchedFilters(all: List<Detection>, trusted: Set<String>, now: Long) {
        if (!scanActive) return
        val watched = ScreenOffWatch.watched(all, trusted).mapTo(HashSet()) { it.address }
        if (!ScreenOffWatch.refreshDue(watched, watchedFilterAddresses, watchedFilterAt, now)) return
        withContext(Dispatchers.Main) { if (running && !screenOn) restartScan() }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // The alert channels are secret on the lock screen. A channel's lock-screen
        // visibility is fixed once it exists, so these are new channels; the old
        // ones are deleted, and their notifications with them.
        for (old in LEGACY_CHANNELS) runCatching { manager.deleteNotificationChannel(old) }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Scanning", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the detector is running" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Tracker alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "A device has been confirmed as following you"
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CELL, "Phone network alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "A fake cell tower may be nearby, or your phone was pushed onto an older, weaker network"
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
        )
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The notification's Stop button: the same stop as the in-app toggle, so it stays off. */
    private fun stopIntent() = PendingIntent.getService(
        this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * The ongoing notification says that the scanner runs, or that it is retrying,
     * never what it has heard: how many devices are watched and whether one is
     * following are on the Scan hub, behind the lock. See [NotificationPresentation.ongoing].
     */
    private fun ongoing(): NotificationText =
        NotificationPresentation.ongoing(paused = running && !scanActive, lowPower = LowPowerMode.active)

    /** Whether the ongoing notification offers "Stop scanning": only with no app passcode set. */
    private fun ongoingStop(): Boolean = NotificationPresentation.ongoingStopAction(lockArmed = AppLock.isEnabled(this))

    private fun buildOngoing(stop: Boolean = ongoingStop()): Notification {
        val shown = ongoing()
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle(shown.title).setContentText(shown.text)
            .setSmallIcon(R.drawable.ic_stat_clairix)
            .setOngoing(true).setContentIntent(contentIntent())
            .apply {
                // With a passcode set, switching the scanner off takes it: the button would do it from
                // the shade without. Pressed on the lock screen, it waits for the phone to be unlocked.
                if (stop) addAction(
                    NotificationCompat.Action.Builder(0, "Stop scanning", stopIntent())
                        .setAuthenticationRequired(true)
                        .build()
                )
            }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // On the lock screen: the app's name and that it scans.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(PlatformAlerts.publicVersion(this, CHANNEL_ONGOING, NotificationPresentation.SCANNING))
            .build()
    }

    /**
     * The publish loop runs every 1.5 s; re-posting the notification that often gets
     * the app rate-limited by the system and costs battery for no visible change. Post
     * only when the text actually differs, which it does only as the scan pauses and resumes
     * or low power comes and goes, or when the Stop button comes or goes with the app passcode.
     */
    private fun updateOngoing() {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val text = ongoing().text
        val stop = ongoingStop()
        if (text == lastOngoingText && stop == lastOngoingStop) return
        lastOngoingText = text
        lastOngoingStop = stop
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildOngoing(stop))
    }

    /** A recognised tracker "is following you"; anything else is only known to be travelling with you. */
    private fun followingTitle(detection: Detection): String =
        if (detection.threat == Threat.CRITICAL) "${detection.name} is following you"
        else "${detection.name} is travelling with you"

    private fun notifyFollowing(detection: Detection) {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val metres = detection.displacementM.toInt()
        val tracker = detection.threat == Threat.CRITICAL
        val short = if (tracker) "Heard $metres m apart. It is on you or your vehicle."
        else "Heard $metres m apart. Nothing identifies it as a tracker."
        val long = if (tracker) {
            "${detection.name} present at locations $metres m apart over ${detection.sightings} sightings. " +
                "Check wheel wells, bumper covers, OBD-II port, under seats, bag linings."
        } else {
            "${detection.name} has been heard at places $metres m apart over ${detection.sightings} sightings. " +
                "It does not identify itself as a tracker: it may be the phone, earbuds or watch of someone " +
                "travelling with you, or your vehicle's own electronics. A hardwired GPS tracker looks the same, " +
                "so if you are alone, check the car: OBD-II port, under the dash, wheel wells, bumpers. " +
                "Mark it as yours in Clairix if you recognise it."
        }
        // Named only while the app is unlocked and the owner asked for details in
        // the shade; otherwise "Unlock to read", and the finding is on the Scan hub.
        val shown = alertText(followingTitle(detection), short, long) ?: return
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .apply { shown.bigText?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setSmallIcon(if (shown.detailed) android.R.drawable.stat_notify_error else R.drawable.ic_stat_clairix)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (tracker) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true).setContentIntent(contentIntent())
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(PlatformAlerts.publicVersion(this, CHANNEL_ALERT))
            .build()
        getSystemService(NotificationManager::class.java).notify(detection.key.hashCode(), n)
    }

    /** What an alert notification may say right now; see [NotificationPresentation.alert]. */
    private fun alertText(title: String, text: String, bigText: String? = null): NotificationText? =
        NotificationPresentation.alert(
            title = title, text = text, bigText = bigText,
            locked = AppLock.isLocked(this), showDetails = AppSettings.showAlertDetails
        )

    private fun notifyCatcher(title: String) {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val shown = alertText("Suspicious cell tower nearby", title) ?: return
        val n = NotificationCompat.Builder(this, CHANNEL_CELL)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .setSmallIcon(if (shown.detailed) android.R.drawable.stat_sys_warning else R.drawable.ic_stat_clairix)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(contentIntent())
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(PlatformAlerts.publicVersion(this, CHANNEL_CELL))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CELL, n)
    }

    override fun onDestroy() {
        liveTrips = null
        liveImsi = null
        stopScanning()
        ScanPower.stop()
        StalkwareWatch.stopWatchingPackages(this)
        val now = System.currentTimeMillis()
        // Stopping from the app (the toggle, the notification's button) turns scanning
        // off before the service goes; a scanner that ends with it still on was ended
        // by something else — an update, a kill, a battery manager — and the record
        // says so rather than "Scanning stopped" as if the owner had done it. A wipe
        // records nothing: it is erasing the file this would go in.
        val bySystem = running && AppSettings.scanEnabled && !Wiper.isActive
        if (running) {
            try { unregisterReceiver(systemReceiver) } catch (_: IllegalArgumentException) {}
            location.stop()
            motionSensor?.let { getSystemService(SensorManager::class.java)?.cancelTriggerSensor(motionTrigger, it) }
            GnssMonitor.setSettled(false)
            gnssHandle?.let { GnssMonitor.release(it) }
            gnssHandle = null
            // A drive still under way is closed and stored, or the trip count loses it.
            if (vehicleTrips.inTrip) vehicleTrips.endTrip(now).forEach(::publishVehicleFinding)
            // Recorded synchronously: the process may not outlive this callback.
            if (!Wiper.isActive) {
                val reason = if (bySystem) ScannerHealth.StopReason.SYSTEM else ScannerHealth.StopReason.USER
                ScanRecord.recordStop(this, reason, now, sync = true)
                eventLog.record(if (bySystem) ScannerHealth.systemStopEvent(now) else ScannerHealth.userStopEvent(now))
            }
        }
        // The owner turned the scanner off: the TLS canary stops too, unless comms keep
        // it. A scanner the system ended keeps it, as the owner still wants the cover.
        if (!AppSettings.scanEnabled) TlsCanary.stopIfUnwanted(this)
        // The Karma canary's name is withdrawn however the scanner ends: with nothing
        // reading the answers, the phone should not go on asking for it. The next start
        // registers a new one.
        runCatching { KarmaCanary.stop(this) }
        Registry.update {
            if (running) it.copy(
                scanning = false,
                // A stop clears whatever the scan was saying; the flag says how it ended.
                error = null, stoppedBySystem = bySystem, stoppedAt = if (bySystem) now else 0L
            ) else it.copy(
                // A start that failed: its reason stays up, and so does an earlier system stop.
                scanning = false
            )
        }
        // The last reading stays on the Cell tab, but nothing is judging it any more;
        // the tab says so instead of showing a frozen "RISK: NONE" as if it were live.
        Registry.updateCell { it.copy(analysing = false, findings = emptyList(), score = 0, level = Threat.NONE) }
        // Each owner flushes under the lock it mutates its JSON under; see Store.
        imsiCatcher.flush()
        TimelineLog.flush()
        LocationHistory.flush()
        phoneHealthMonitor.stop()
        AudioRouteMonitor.stop()
        bleExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    companion object {
        const val ACTION_STOP = "app.clairix.STOP"
        const val ACTION_CLEAR = "app.clairix.CLEAR"
        /** "I found it" on a device's row ([EXTRA_KEY]; [EXTRA_FOUND] false takes it back). */
        private const val ACTION_RESOLVE = "app.clairix.RESOLVE"
        /** "Ask it who it is" in the locator ([EXTRA_KEY], [EXTRA_ADDRESS]). */
        private const val ACTION_DULT_ASK = "app.clairix.DULT_ASK"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_FOUND = "found"
        private const val TRIPS_FILE = "vehicle_trips.json"

        /**
         * Marks the device the running scanner knows as [key] found ([found]) or takes
         * that back. Plain startService, as the screen asking is in the foreground; with
         * no scan running the service does nothing and stops again.
         */
        fun sendFound(context: Context, key: String, found: Boolean) = command(context, ACTION_RESOLVE) {
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_FOUND, found)
        }

        /** Asks the device the running scanner knows as [key], now at [address], who it is over DULT. */
        fun sendDultAsk(context: Context, key: String, address: String) = command(context, ACTION_DULT_ASK) {
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_ADDRESS, address)
        }

        private fun command(context: Context, action: String, extras: Intent.() -> Unit) {
            runCatching {
                context.startService(Intent(context, ScanService::class.java).setAction(action).apply(extras))
            }
        }

        /**
         * The live service's trips, so [HistoryRetention] can prune them in
         * place; null while no service is alive, when the file is opened fresh.
         */
        @Volatile private var liveTrips: VehicleTrips? = null

        /** Forgets vehicle trips that ended before [cutoff]; returns how many. File I/O; call off the main thread. */
        fun pruneTripsOlderThan(context: Context, cutoff: Long): Int =
            (liveTrips ?: VehicleTrips(Store(context.applicationContext, TRIPS_FILE))).pruneOlderThan(cutoff)

        private const val CELL_FILE = "imsi_baseline.json"

        /** The live service's cell baseline, for [applyCellRetention] to work on in place; null while no service is alive. */
        @Volatile private var liveImsi: IMSICatcher? = null

        /**
         * The history retention for the cell-tower baseline ([IMSICatcher.applyRetention]);
         * [cutoff] null keeps every tower. Returns how many values changed. File I/O and
         * Keystore work when no service is alive; call off the main thread.
         */
        fun applyCellRetention(context: Context, cutoff: Long?, now: Long): Int =
            (liveImsi ?: IMSICatcher(Store(context.applicationContext, CELL_FILE))).applyRetention(cutoff, now)

        /** Which of the owner's accessories rode along on the remembered drives. File I/O when no service is alive. */
        fun accessoryUse(context: Context): List<VehicleTrips.AccessoryUse> =
            (liveTrips ?: VehicleTrips(Store(context.applicationContext, TRIPS_FILE))).accessoryUse()

        /** A mapped plate reader within this of where hardware was heard is taken to be that hardware. */
        const val KNOWN_CAMERA_MATCH_M = 400.0
        /** How often the signed camera caches are re-read for the match above. */
        private const val KNOWN_CAMERAS_REFRESH_MS = 30 * 60_000L
        /** One timeline row and one notification per piece of hardware in this window. */
        private const val INFRASTRUCTURE_DEDUPE_MS = 12 * 3_600_000L
        /** Discoverable for this long and the owner is told the settings page is still open. */
        private const val DISCOVERABLE_ALERT_MS = 3 * 60_000L
        private const val CHANNEL_ONGOING = "scanning"
        /** Secret on the lock screen (see [createChannels]); the ids they replace are in [LEGACY_CHANNELS]. */
        const val CHANNEL_ALERT = "alerts_secret"
        const val CHANNEL_CELL = "cell_alerts_secret"
        private val LEGACY_CHANNELS = listOf("alerts", "cell_alerts")
        private const val NOTIFICATION_ID = 1
        private const val NOTIF_CELL = 2
        private const val APPLE_COMPANY = 0x004C
        /** Trackers that carry their service UUID in service data rather than the UUID list. */
        private val SERVICE_DATA_UUIDS = setOf(
            "0000feed-0000-1000-8000-00805f9b34fb",
            "0000feec-0000-1000-8000-00805f9b34fb",
            "0000fd84-0000-1000-8000-00805f9b34fb",
            "0000fd5a-0000-1000-8000-00805f9b34fb",
            "0000fcb2-0000-1000-8000-00805f9b34fb",
            "0000feaa-0000-1000-8000-00805f9b34fb",
        )
        /** Apple's Find My message type, the first byte after the company ID. */
        private const val FIND_MY_TYPE: Byte = 0x12
        /** Apple's iBeacon message type, in the same place. */
        private const val IBEACON_TYPE: Byte = 0x02

        // Not exposed as constants on ScanCallback before API 33 / 34, so spelled out.
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
        private const val RETRY_DELAY_MS = 30_000L
        private const val RETRY_THROTTLED_DELAY_MS = 60_000L

        /** Road speed: about 29 km/h, above anything on foot or a bicycle sustains. */
        private const val DRIVING_SPEED_MS = 8f
        /** No fix at road speed for this long and the car is parked. */
        private const val TRIP_END_MS = 5 * 60_000L
        /** How long the start of a drive is scanned at low latency. */
        private const val INTENSIVE_SWEEP_MS = 5 * 60_000L
        /** Allowance past the interrogator's own budget before its collector is cancelled. */
        private const val DULT_GRACE_MS = 5_000L
        /** A coarse fix this often once the phone has settled: Wi-Fi/cell, or the satellites with Satellite-only positioning on. */
        private const val SETTLED_FIX_MS = 3 * 60_000L
        /** A hardware sighting reuses its nearest-camera lookup for this long. */
        private const val NEAREST_CAMERA_REUSE_MS = 30_000L
        /** How long each polling loop waits after an iteration threw, about its own slowest cadence; see [guarded]. */
        private const val CELL_FAILURE_PAUSE_MS = 30_000L
        private const val PUBLISH_FAILURE_PAUSE_MS = 10_000L
        private const val TAG = "ScanService"
    }
}
