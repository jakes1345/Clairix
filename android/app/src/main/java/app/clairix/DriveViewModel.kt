package app.clairix

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clairix.nav.BatteryWatch
import app.clairix.nav.DrivePower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drive mode: one RTL-SDR dongle plus an ELM327 OBD-II adapter.
 *
 * A single dongle can only be tuned to one place, so the SDR runs in one of two modes:
 * the voice scanner while at least one scanner channel is enabled, ADS-B on 1090 MHz
 * otherwise. Toggling the last channel on or off switches the mode live.
 *
 * What runs is decided by [DriveLink] from the owner's choices, the stick being plugged in
 * or pulled out (the USB attach and detach broadcasts, heard while drive mode is on) and
 * the battery ([DrivePower]); [reconcile] starts and stops the stick, the adapter and the
 * watchers to match, and keeps drive mode's notification saying what is running.
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {

    private val sdr = SdrUsbDriver(app)
    private val adsb = AdsbDecoder()
    private val scanner = ScannerMonitor(app, sdr)
    private val obd = ObdBluetoothManager(app)
    private val link = DriveLink()

    val sdrState: StateFlow<SdrState> = sdr.state
    val aircraft: StateFlow<Map<String, Aircraft>> = adsb.aircraft
    val obdData: StateFlow<ObdData> = obd.data
    val scannerChannels: StateFlow<List<ScannerChannel>> = scanner.channels
    val activeChannel: StateFlow<ScannerChannel?> = scanner.activeChannel
    val squelchOpen: StateFlow<Boolean> = scanner.squelchOpen
    val isRecording: StateFlow<Boolean> = scanner.isRecording

    private val _squelchLevel = MutableStateFlow(DEFAULT_SQUELCH)
    val squelchLevel: StateFlow<Float> = _squelchLevel.asStateFlow()

    private val _driveModeActive = MutableStateFlow(false)
    val driveModeActive: StateFlow<Boolean> = _driveModeActive.asStateFlow()

    private val _radio = MutableStateFlow(DriveLink.Radio.OFF)
    /** What the radio half is doing: on, unplugged, waiting for permission, or paused on a low battery. */
    val radio: StateFlow<DriveLink.Radio> = _radio.asStateFlow()

    private val _lowBattery = MutableStateFlow(false)
    /** Below [DrivePower.CUTOFF_PCT] and not charging: the stick and the adapter are off until the phone charges. */
    val lowBattery: StateFlow<Boolean> = _lowBattery.asStateFlow()

    private val _recordingEnabled = MutableStateFlow(false)
    /** Whether transmissions are recorded. Off until the owner turns it on, and off again when drive mode ends. */
    val recordingEnabled: StateFlow<Boolean> = _recordingEnabled.asStateFlow()

    private val _recordings = MutableStateFlow<List<ScannerRecordings.Info>>(emptyList())
    val recordings: StateFlow<List<ScannerRecordings.Info>> = _recordings.asStateFlow()

    private val _legacy = MutableStateFlow<List<ScannerRecordings.LegacyRecording>>(emptyList())
    /** Recordings from before they were sealed, still plain in the app's storage until the owner moves or deletes them. */
    val legacyRecordings: StateFlow<List<ScannerRecordings.LegacyRecording>> = _legacy.asStateFlow()

    private val _playing = MutableStateFlow<String?>(null)
    /** What is playing: a sealed recording's id, or a plain one's file path. */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    private val _radioNoticeAcked = MutableStateFlow(scanner.radioNoticeAcked())
    /** Whether the owner has read the notice on receiving and recording radio traffic. */
    val radioNoticeAcked: StateFlow<Boolean> = _radioNoticeAcked.asStateFlow()

    private var sdrJob: Job? = null
    private var obdJob: Job? = null
    private var batteryJob: Job? = null
    private var noticeJob: Job? = null
    private var playbackJob: Job? = null
    private var usbRegistered = false

    /** The stick pulled out or plugged in; only dongles the driver knows count. */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (!SdrUsbDriver.isKnownDongle(device.vendorId, device.productId)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onStickDetached()
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onStickAttached(sdr.hasPermission(device))
            }
        }
    }

    init {
        scanner.setSquelch(DEFAULT_SQUELCH)
        viewModelScope.launch { scanner.loadSaved() }
        // Read now, and again each time a recording is finished.
        viewModelScope.launch { scanner.recordingsChanged.collect { refreshRecordings() } }
        // Stop on drive mode's notification ends it here, as the switch on the Drive tab does.
        viewModelScope.launch { DriveService.stopRequests.collect { deactivateDriveMode() } }
    }

    fun activateDriveMode() {
        if (_driveModeActive.value) return
        _driveModeActive.value = true
        DriveService.start(getApplication())
        link.driveModeOn()
        // Read before anything starts, so a phone already low never opens the stick only to close it.
        link.battery(batteryLowNow())
        reconcile()
    }

    fun deactivateDriveMode() {
        if (!_driveModeActive.value) return
        _driveModeActive.value = false
        link.driveModeOff()
        reconcile()
        // Recording is a choice made for one drive.
        setRecording(false)
        sdr.close(clearError = true)
        DriveService.stop(getApplication())
    }

    fun toggleDriveMode() {
        if (_driveModeActive.value) deactivateDriveMode() else activateDriveMode()
    }

    fun setSquelch(level: Float) {
        val l = level.coerceIn(0f, 1f)
        _squelchLevel.value = l
        scanner.setSquelch(l)
    }

    fun addChannel(name: String, freqHz: Long) = scanner.addChannel(name, freqHz)

    fun removeChannel(freqHz: Long) = scanner.removeChannel(freqHz)

    fun setChannelActive(freqHz: Long, active: Boolean) = scanner.setChannelActive(freqHz, active)

    fun connectObd() {
        val watching = link.watchBattery
        link.obdConnect()
        if (!watching) link.battery(batteryLowNow())
        reconcile(restartObd = true)
    }

    fun disconnectObd() {
        link.obdDisconnect()
        reconcile()
    }

    /** Reconnect: try the stick again, asking Android for permission to use it if need be. */
    fun reconnectSdr() {
        link.retry()
        reconcile(restartSdr = true)
    }

    /**
     * The Drive screen came to the front, or left it. The gauges are read faster while
     * they are seen, and a stick plugged in meanwhile may have been allowed through
     * Android's own "open with Clairix" prompt.
     */
    fun setScreenVisible(visible: Boolean) {
        obd.pollIntervalMs = DrivePower.obdPollMs(onScreen = visible)
        if (visible && link.stick == DriveLink.Stick.NEEDS_PERMISSION) {
            val device = sdr.attachedDongle()
            when {
                device == null -> onStickDetached()
                sdr.hasPermission(device) -> onStickAttached(true)
            }
        }
    }

    fun ackRadioNotice() {
        scanner.ackRadioNotice()
        _radioNoticeAcked.value = true
    }

    // ── Recordings ─────────────────────────────────────────────────────────

    fun setRecording(on: Boolean) {
        _recordingEnabled.value = on
        scanner.recordingEnabled = on
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            val (list, legacy) = withContext(Dispatchers.IO) {
                val sealed = runCatching { scanner.recordings.list() }.getOrDefault(emptyList())
                sealed to runCatching { ScannerRecordings.findLegacy(ScannerRecordings.legacyDirs(getApplication())) }
                    .getOrDefault(emptyList())
            }
            _recordings.value = list
            _legacy.value = legacy
        }
    }

    fun deleteRecording(id: String) {
        if (_playing.value == id) stopPlayback()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { scanner.recordings.delete(id) }
            refreshRecordings()
        }
    }

    fun deleteAllRecordings() {
        stopPlayback()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { scanner.recordings.deleteAll() }
            refreshRecordings()
        }
    }

    /** Seals the recordings from before into the list, as many as the space kept for recordings allows; the rest stay where they are. */
    fun importLegacyRecordings() {
        stopPlayback()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                scanner.recordings.importLegacy(ScannerRecordings.findLegacy(ScannerRecordings.legacyDirs(getApplication())))
            }
            refreshRecordings()
        }
    }

    fun deleteLegacyRecording(path: String) {
        if (_playing.value == path) stopPlayback()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ScannerRecordings.findLegacy(ScannerRecordings.legacyDirs(getApplication()))
                    .firstOrNull { it.file.absolutePath == path }?.file?.delete()
            }
            refreshRecordings()
        }
    }

    fun deleteLegacyRecordings() {
        stopPlayback()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ScannerRecordings.findLegacy(ScannerRecordings.legacyDirs(getApplication())).forEach { it.file.delete() }
            }
            refreshRecordings()
        }
    }

    /** Plays a sealed recording, a second at a time, straight from the file. */
    fun playRecording(id: String) = play(id) { scanner.recordings.open(id) }

    /** Plays a recording from before they were sealed. */
    fun playLegacyRecording(path: String) = play(path) { ScannerRecordings.PlainReader(File(path)) }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _playing.value = null
    }

    private fun play(key: String, open: () -> PcmSource) {
        playbackJob?.cancel()
        _playing.value = key
        playbackJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { open().use { playOut(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Recording not played", e)
            } finally {
                if (playbackJob === currentCoroutineContext()[Job]) {
                    playbackJob = null
                    _playing.value = null
                }
            }
        }
    }

    /** Recordings are raw 16-bit mono PCM, which no stock player opens, so they play here. */
    private suspend fun playOut(source: PcmSource) {
        val rate = source.sampleRate
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
            )
            .build()
        try {
            track.play()
            while (currentCoroutineContext().isActive) {
                val piece = source.next() ?: break
                var pos = 0
                while (pos < piece.size && currentCoroutineContext().isActive) {
                    val n = track.write(piece, pos, minOf(PLAYBACK_CHUNK, piece.size - pos))
                    if (n <= 0) return
                    pos += n
                }
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    // ── What runs ──────────────────────────────────────────────────────────

    private fun onStickDetached() {
        if (!link.driveOn) return
        link.detached()
        adsb.clear()
        reconcile()
    }

    private fun onStickAttached(permitted: Boolean) {
        if (!link.driveOn) return
        link.attached(permitted)
        reconcile(restartSdr = permitted)
    }

    private fun batteryLowNow(): Boolean =
        BatteryWatch.now(getApplication())?.let { DrivePower.lowBattery(it, wasLow = false) } ?: false

    /** Starts and stops the stick, the adapter, the battery watch and the USB receiver to match [link]. */
    private fun reconcile(restartSdr: Boolean = false, restartObd: Boolean = false) {
        if (!link.sdrShouldRun) stopSdr() else if (restartSdr || sdrJob == null) startSdr()
        if (!link.obdShouldRun) stopObd() else if (restartObd || obdJob == null) startObd()
        if (link.watchBattery) startBatteryWatch() else stopBatteryWatch()
        if (link.driveOn) {
            registerUsb()
            startNoticeUpdates()
        } else {
            unregisterUsb()
            stopNoticeUpdates()
        }
        _radio.value = link.radio
        _lowBattery.value = link.lowBattery
    }

    private fun startSdr() {
        val previous = sdrJob
        previous?.cancel()
        sdrJob = viewModelScope.launch {
            // The earlier run closes the dongle on its way out; let it finish before opening it again.
            previous?.join()
            scanner.channels
                .map { list -> list.any { it.active && SdrUsbDriver.isTunable(it.freqHz) } }
                .distinctUntilChanged()
                .collectLatest { scanMode -> runSdr(scanMode) }
        }
    }

    private fun stopSdr() {
        val job = sdrJob ?: return
        sdrJob = null
        job.cancel()
        // Cancelling runs runSdr's finally, but do it here too so the dongle is released at
        // once, and even if the run had already ended on an open failure.
        scanner.stop()
        adsb.stop()
        sdr.close()
    }

    private fun startObd() {
        obdJob?.cancel()
        obdJob = viewModelScope.launch { obd.connect() }
    }

    private fun stopObd() {
        val job = obdJob
        obdJob = null
        job?.cancel()
        if (job != null || obd.data.value.connected) obd.disconnect()
    }

    private fun startBatteryWatch() {
        if (batteryJob != null) return
        batteryJob = viewModelScope.launch {
            DrivePower.lowBatteryStates(BatteryWatch.readings(getApplication()), initial = link.lowBattery)
                .collect { low ->
                    if (low != link.lowBattery) {
                        link.battery(low)
                        reconcile()
                    }
                }
        }
    }

    private fun stopBatteryWatch() {
        batteryJob?.cancel()
        batteryJob = null
    }

    private fun registerUsb() {
        if (usbRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Both are sent by the system only.
        ContextCompat.registerReceiver(getApplication(), usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        usbRegistered = true
    }

    private fun unregisterUsb() {
        if (!usbRegistered) return
        usbRegistered = false
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
    }

    /** Keeps drive mode's notification saying what runs; see [DriveLink.ongoing]. */
    private fun startNoticeUpdates() {
        if (noticeJob != null) return
        noticeJob = viewModelScope.launch {
            combine(_radio, sdr.state, obd.data) { radio, state, data -> DriveLink.ongoing(radio, state, data.connected) }
                .distinctUntilChanged()
                .collect { DriveService.show(getApplication(), it) }
        }
    }

    private fun stopNoticeUpdates() {
        noticeJob?.cancel()
        noticeJob = null
    }

    private suspend fun runSdr(scanMode: Boolean) {
        try {
            if (scanMode) {
                val first = scanner.channels.value
                    .firstOrNull { it.active && SdrUsbDriver.isTunable(it.freqHz) } ?: return
                if (!sdr.open(first.freqHz, FmDemodulator.INPUT_RATE)) return
                scanner.start()
                // Until the stream stops: cancelled, or the stick stalled or went away (the driver says why).
                sdr.state.first { it !is SdrState.Ready }
            } else {
                if (!sdr.open(ADSB_FREQ_HZ, ADSB_SAMPLE_RATE)) return
                adsb.start(sdr)
            }
        } finally {
            scanner.stop()
            adsb.stop()
            // With no stream nothing ages the aircraft out; an old list must not stand as if live.
            adsb.clear()
            sdr.close()
        }
    }

    override fun onCleared() {
        stopPlayback()
        deactivateDriveMode()
        // Connect on the Vehicle card, without drive mode.
        if (link.obdWanted) {
            link.obdDisconnect()
            reconcile()
        }
        unregisterUsb()
        super.onCleared()
    }

    private companion object {
        const val TAG = "DriveViewModel"
        const val ADSB_FREQ_HZ = 1_090_000_000L
        // AdsbDecoder's preamble and bit slicer assume exactly 2 samples per microsecond.
        const val ADSB_SAMPLE_RATE = 2_000_000
        const val DEFAULT_SQUELCH = 0.3f
        const val PLAYBACK_CHUNK = 8192
    }
}
