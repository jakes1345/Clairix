package app.clairix

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.clairix.nav.DrivePower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ScannerChannel(val name: String, val freqHz: Long, val active: Boolean = true)

class ScannerMonitor(private val context: Context, private val driver: SdrUsbDriver) {
    companion object {
        private const val TAG = "ScannerMonitor"
        private const val PREFS = "scanner"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_RADIO_NOTICE = "radio_notice_ack"
        private const val SAMPLE_RATE = FmDemodulator.OUTPUT_RATE
        private const val DWELL_MS = 250L
    }

    val demodulator = FmDemodulator()

    /** Where recordings go, sealed; see [ScannerRecordings]. */
    val recordings = ScannerRecordings(ScannerRecordings.dir(context))

    /**
     * Whether a transmission that opens the squelch is recorded. Off unless the owner
     * turns it on ([DriveViewModel.setRecording]); turning it off ends the recording under way.
     */
    @Volatile var recordingEnabled = false

    private val _channels = MutableStateFlow<List<ScannerChannel>>(emptyList())
    val channels: StateFlow<List<ScannerChannel>> = _channels.asStateFlow()

    private val _activeChannel = MutableStateFlow<ScannerChannel?>(null)
    val activeChannel: StateFlow<ScannerChannel?> = _activeChannel.asStateFlow()

    val squelchOpen: StateFlow<Boolean> = demodulator.squelchOpen

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingsChanged = MutableStateFlow(0)
    /** Goes up each time a recording is finished, for the list to be read again. */
    val recordingsChanged: StateFlow<Int> = _recordingsChanged.asStateFlow()

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var audioJob: Job? = null
    private var track: AudioTrack? = null

    /** Loads the saved channel list so the UI can show it before scanning starts. */
    suspend fun loadSaved() {
        _channels.value = withContext(Dispatchers.IO) { loadChannels(context) }
    }

    suspend fun start() {
        stop()
        loadSaved()
        // A failure inside one loop must not take the process down through the default handler.
        val handler = CoroutineExceptionHandler { _, _ -> _isRecording.value = false }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        scope = newScope
        val audioTrack = buildTrack()
        track = audioTrack
        audioTrack.play()
        scanJob = newScope.launch { scanLoop() }
        audioJob = newScope.launch { audioLoop(audioTrack) }
    }

    fun stop() {
        scanJob?.cancel()
        audioJob?.cancel()
        scope?.cancel()
        scanJob = null
        audioJob = null
        scope = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        _activeChannel.value = null
        _isRecording.value = false
    }

    fun addChannel(name: String, freqHz: Long) {
        if (!SdrUsbDriver.isTunable(freqHz)) return
        val next = _channels.value.filterNot { it.freqHz == freqHz } + ScannerChannel(name, freqHz)
        _channels.value = next
        persistChannels(context)
    }

    fun setChannelActive(freqHz: Long, active: Boolean) {
        _channels.value = _channels.value.map { if (it.freqHz == freqHz) it.copy(active = active) else it }
        persistChannels(context)
    }

    fun removeChannel(freqHz: Long) {
        _channels.value = _channels.value.filterNot { it.freqHz == freqHz }
        persistChannels(context)
    }

    /** Whether the owner has read the one-time notice on receiving and recording radio traffic. */
    fun radioNoticeAcked(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RADIO_NOTICE, false)

    fun ackRadioNotice() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RADIO_NOTICE, true).apply()
    }

    /** [level] 0..1, higher = tighter (needs a cleaner carrier to open). */
    fun setSquelch(level: Float) {
        demodulator.squelchThreshold = FmDemodulator.noiseThresholdFor(level)
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4)
            .build()
    }

    private suspend fun scanLoop() {
        var index = 0
        while (currentCoroutineIsActive()) {
            val list = _channels.value.filter { it.active && SdrUsbDriver.isTunable(it.freqHz) }
            if (list.isEmpty()) {
                _activeChannel.value = null
                delay(DWELL_MS)
                continue
            }
            index %= list.size
            val ch = list[index]
            if (!driver.setFrequency(ch.freqHz)) {
                if (driver.state.value !is SdrState.Ready) {
                    // The dongle is not streaming (closed, failed, pulled out): wait for it
                    // instead of spinning through the channels. Drive mode stops the scanner
                    // when the stick goes and starts it again when it is back.
                    _activeChannel.value = null
                    driver.state.first { it is SdrState.Ready }
                    continue
                }
                // The PLL did not lock: move on instead of listening to the previous channel
                // under this one's name.
                index++
                delay(DrivePower.RETUNE_PAUSE_MS)
                continue
            }
            _activeChannel.value = ch
            delay(DWELL_MS)
            // Stay while the channel talks: wait for its squelch to close, without polling.
            demodulator.squelchOpen.first { !it }
            index++
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        currentCoroutineContext().isActive

    private suspend fun audioLoop(audioTrack: AudioTrack) {
        var writer: ScannerRecordings.Writer? = null
        // A recording that reached its ten minutes is not followed by another until the
        // squelch closes: a stuck carrier must not fill the phone a file at a time.
        var heldOpen = false
        try {
            demodulator.demodulate(driver.sampleFlow())
                .flowOn(Dispatchers.Default)
                .collect { pcm ->
                    audioTrack.write(pcm, 0, pcm.size)
                    val open = demodulator.squelchOpen.value
                    if (!open) heldOpen = false
                    if (open && writer == null && recordingEnabled && !heldOpen) {
                        val ch = _activeChannel.value
                        writer = runCatching {
                            recordings.begin(
                                ScannerRecordings.Header(System.currentTimeMillis(), ch?.freqHz ?: 0L, ch?.name.orEmpty(), SAMPLE_RATE)
                            )
                        }.onFailure { Log.w(TAG, "Recording not started", it) }.getOrNull()
                        _isRecording.value = writer != null
                    }
                    writer?.let { w ->
                        val failed = runCatching { w.write(pcm) }
                            .onFailure { Log.w(TAG, "Recording stopped", it) }
                            .isFailure
                        if (failed || !open || !recordingEnabled || w.full) {
                            if (w.full) heldOpen = true
                            finish(w)
                            writer = null
                        }
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // sampleFlow() throws when the SDR is not open; the driver's state already says why.
        } finally {
            writer?.let { finish(it) }
            // Nothing more is coming: stop the speaker instead of leaving it playing silence.
            runCatching { audioTrack.pause() }
        }
    }

    /** Seals the end of [w], keeps the recordings within their cap and lets the list know. */
    private fun finish(w: ScannerRecordings.Writer) {
        runCatching { w.close() }.onFailure { Log.w(TAG, "Recording not finished", it) }
        runCatching { recordings.enforceCap() }
        _isRecording.value = false
        _recordingsChanged.value++
    }

    private fun persistChannels(ctx: Context) {
        val arr = JSONArray()
        for (ch in _channels.value) {
            arr.put(
                JSONObject()
                    .put("name", ch.name)
                    .put("freqHz", ch.freqHz)
                    .put("active", ch.active)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHANNELS, arr.toString())
            .apply()
    }

    private fun loadChannels(ctx: Context): List<ScannerChannel> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNELS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScannerChannel(
                    name = o.optString("name", ""),
                    freqHz = o.getLong("freqHz"),
                    active = o.optBoolean("active", true)
                )
            }
        }.getOrDefault(emptyList())
    }
}
