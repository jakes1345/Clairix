package app.clairix

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import app.clairix.analysis.Ultrasonic
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.ScreenList
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusHero
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

// The Ultrasound category: listens for the inaudible tones ad-tracking kits use to
// tie a phone to a TV, a shop or another device, and proves first that this phone's
// microphone can hear that band at all.

/** What one listening session has found. Kept in memory while the page is open; audio itself is never kept. */
private data class ListenState(
    val listening: Boolean = false,
    val seconds: Int = 0,
    val sampleRate: Int = 0,
    val source: String = "",
    val beacons: List<Ultrasonic.Beacon> = emptyList(),
    /** Every beacon heard this session, by 50 Hz bucket, so one that stopped is still listed. */
    val heard: Map<Int, Ultrasonic.Beacon> = emptyMap(),
    val broadbandShare: Double = 0.0,
    val error: String? = null,
    val selfTest: SelfTest = SelfTest.NOT_RUN
)

private enum class SelfTest(val label: String) {
    NOT_RUN("Not run"),
    RUNNING("Playing a 19 kHz tone…"),
    PASSED("Heard its own 19 kHz tone"),
    FAILED("Did not hear its own 19 kHz tone"),
    MUTED("Media volume is off")
}

private const val TEST_TONE_HZ = 19_000.0
private const val TEST_SECONDS = 4.0

@Composable
internal fun UltrasoundScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ListenState()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    fun start(selfTest: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            listen(context, selfTest) { update -> state = update(state) }
        }
    }
    fun stop() {
        job?.cancel()
        job = null
        state = state.copy(listening = false, selfTest = if (state.selfTest == SelfTest.RUNNING) SelfTest.NOT_RUN else state.selfTest)
    }

    // The microphone is never left open behind the page, nor while the app is in
    // the background: leaving the app stops the session, as the Stop button would.
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LifecycleStartEffect(Unit) { onStopOrDispose { if (job?.isActive == true) stop() } }

    val found = state.heard.values.sortedByDescending { it.snrDb }
    ScreenList {
        item(key = "§hero") {
            StatusHero(
                eyebrow = if (state.listening) stringResource(R.string.scan_listening_s, state.seconds) else stringResource(R.string.scan_ultrasonic_beacons),
                headline = when {
                    found.isNotEmpty() -> stringResource(R.string.scan_beacon)
                    state.listening -> stringResource(R.string.comms_listening)
                    state.seconds > 0 -> stringResource(R.string.scan_quiet)
                    else -> stringResource(R.string.scan_idle)
                },
                body = when {
                    found.isNotEmpty() -> stringResource(R.string.scan_something_nearby_is_playing_a_tone)
                    state.listening -> stringResource(R.string.scan_hold_the_phone_in_the_open)
                    state.seconds > 0 -> stringResource(R.string.scan_nothing_in_the_beacon_band_during, state.seconds)
                    else -> stringResource(R.string.scan_listens_to_17_5_to_21)
                },
                tone = when {
                    found.isNotEmpty() -> Tone.Caution
                    state.seconds > 0 && !state.listening && state.selfTest == SelfTest.PASSED -> Tone.Safe
                    else -> Tone.Neutral
                }
            )
        }

        if (!hasMic) {
            item(key = "§perm") {
                Notice(Tone.Caution, stringResource(R.string.scan_listening_needs_the_microphone_clairix_reads), title = stringResource(R.string.scan_microphone_permission),
                    action = { SecondaryButton(stringResource(R.string.scan_allow_microphone), onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) })
            }
        }

        item(key = "§actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.listening) {
                    PrimaryButton(stringResource(R.string.comms_stop), onClick = { stop() }, tone = Tone.Danger)
                } else {
                    PrimaryButton(stringResource(R.string.scan_listen), onClick = { start(selfTest = false) }, enabled = hasMic)
                    SecondaryButton(stringResource(R.string.scan_self_test), onClick = { start(selfTest = true) }, enabled = hasMic)
                }
            }
        }

        state.error?.let { e -> item(key = "§err") { Notice(Tone.Caution, e, title = stringResource(R.string.scan_could_not_listen)) } }

        item(key = "§mic-hdr") { SectionHeader(stringResource(R.string.scan_this_phones_microphone)) }
        item(key = "§mic") {
            ClairixCard {
                KeyValueRow(stringResource(R.string.scan_self_test), state.selfTest.label,
                    valueColor = when (state.selfTest) {
                        SelfTest.PASSED -> ClairixTheme.colors.safe
                        SelfTest.FAILED, SelfTest.MUTED -> ClairixTheme.colors.caution
                        else -> null
                    })
                if (state.sampleRate > 0) {
                    KeyValueRow(stringResource(R.string.scan_sample_rate), stringResource(R.string.scan_hz, state.sampleRate))
                    KeyValueRow(stringResource(R.string.scan_audio_path), state.source)
                }
                Text(
                    when (state.selfTest) {
                        SelfTest.PASSED -> stringResource(R.string.scan_the_speaker_played_19_khz_and)
                        SelfTest.FAILED -> stringResource(R.string.scan_either_the_speaker_cannot_play_19)
                        SelfTest.MUTED -> stringResource(R.string.scan_turn_the_media_volume_up_and)
                        else -> stringResource(R.string.scan_the_self_test_plays_a_19)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                )
            }
        }

        if (found.isNotEmpty()) {
            item(key = "§found-hdr") { SectionHeader(stringResource(R.string.scan_heard), supporting = "${found.size}", tone = Tone.Caution) }
            items(found, key = { "b|${(it.freqHz / Ultrasonic.BUCKET_HZ).toInt()}|${it.modulated}" }) { b ->
                ClairixCard(tone = Tone.Caution, spacing = 4.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (b.modulated) "%.1f–%.1f kHz".format(b.lowHz / 1000, b.highHz / 1000) else "%.2f kHz".format(b.freqHz / 1000),
                            style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f)
                        )
                        StatusPill(if (b.modulated) stringResource(R.string.scan_carries_data) else stringResource(R.string.scan_steady_tone), Tone.Caution)
                    }
                    Text(
                        (if (b.modulated) stringResource(R.string.scan_hops_between_tones_the_way_a) else stringResource(R.string.scan_a_single_steady_tone_the_simplest)) +
                            "%.0f dB above the background, present %.0f%% of the time.".format(b.snrDb, b.presence * 100),
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                }
            }
            item(key = "§what-now") {
                Notice(
                    Tone.Info, title = stringResource(R.string.scan_what_to_do),
                    text = stringResource(R.string.scan_find_the_source_by_walking_with)
                )
            }
        }

        item(key = "§limits") {
            Notice(
                Tone.Info, title = stringResource(R.string.comms_limits),
                text = stringResource(R.string.scan_phones_differ_many_hear_up_to)
            )
        }
    }
}

/**
 * The listening loop. Reads the microphone in frames, feeds the tracker, publishes
 * twice a second, and for the self-test plays the test tone at the same time. Runs
 * until cancelled, or for the self-test until the tone has played.
 */
@SuppressLint("MissingPermission")
private suspend fun listen(context: Context, selfTest: Boolean, update: ((ListenState) -> ListenState) -> Unit) = withContext(Dispatchers.IO) {
    val am = context.getSystemService(AudioManager::class.java)
    if (selfTest && (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) == 0) {
        update { it.copy(selfTest = SelfTest.MUTED) }
        return@withContext
    }
    val rate = listOf(48_000, 44_100).firstOrNull {
        AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
    } ?: run { update { it.copy(error = "This phone offers no recording rate high enough for the beacon band.") }; return@withContext }
    val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = try {
        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, Ultrasonic.FRAME * 4))
    } catch (e: Exception) {
        update { it.copy(error = "The microphone could not be opened: ${e.message.toString()}") }; return@withContext
    }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        update { it.copy(error = "The microphone is in use by another app, or refused to open.") }
        return@withContext
    }
    val tracker = Ultrasonic.Tracker()
    val frame = ShortArray(Ultrasonic.FRAME)
    var track: AudioTrack? = null
    val started = System.currentTimeMillis()
    var lastPublish = 0L
    var heardTest = false
    try {
        rec.startRecording()
        update { it.copy(listening = true, seconds = 0, sampleRate = rate, source = if (unprocessed) "Unprocessed microphone" else "Voice-recognition microphone",
            error = null, beacons = emptyList(), heard = if (selfTest) it.heard else emptyMap(),
            selfTest = if (selfTest) SelfTest.RUNNING else it.selfTest) }
        if (selfTest) {
            val pcm = Ultrasonic.tone(TEST_TONE_HZ, rate, TEST_SECONDS, amplitude = 0.35)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            delay(300) // let the recorder settle before the tone starts
            track.play()
        }
        while (isActive) {
            var got = 0
            while (got < frame.size && isActive) {
                val n = rec.read(frame, got, frame.size - got)
                if (n < 0) throw IllegalStateException("read failed ($n)")
                got += n
            }
            val now = System.currentTimeMillis()
            tracker.add(now, Ultrasonic.analyze(frame, rate))
            val beacons = tracker.beacons()
            if (selfTest && beacons.any { !it.modulated && abs(it.freqHz - TEST_TONE_HZ) <= 100.0 }) heardTest = true
            if (now - lastPublish >= 500) {
                lastPublish = now
                val record = if (selfTest) emptyList() else beacons
                record.forEach { b -> report(b, now) }
                update { s ->
                    val heard = s.heard.toMutableMap()
                    for (b in record) heard[(b.freqHz / Ultrasonic.BUCKET_HZ).toInt()] = b
                    s.copy(seconds = ((now - started) / 1000).toInt(), beacons = beacons, heard = heard, broadbandShare = tracker.broadbandShare)
                }
            }
            if (selfTest && (heardTest || now - started > (TEST_SECONDS * 1000 + 1_500).toLong())) break
        }
        if (selfTest) update { it.copy(selfTest = if (heardTest) SelfTest.PASSED else SelfTest.FAILED) }
    } catch (e: IllegalStateException) {
        update { it.copy(error = "Recording stopped: ${e.message.toString()}") }
    } finally {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { rec.stop() }
        rec.release()
        update { it.copy(listening = false) }
    }
}

/** Writes a beacon to the timeline once per frequency per half hour, with where the phone was. */
private fun report(b: Ultrasonic.Beacon, now: Long) {
    val bucket = (b.freqHz / 250).toInt() // 250 Hz: the same beacon wobbling a little is one report
    val st = Registry.status.value
    Registry.publishAlert(
        Alert(
            id = "ultrasound@$bucket@$now", ts = now, severity = Severity.LOW,
            title = if (b.modulated) "Ultrasonic beacon carrying data heard" else "Ultrasonic tone heard",
            detail = (if (b.modulated) "Tones hopping between %.1f and %.1f kHz".format(b.lowHz / 1000, b.highHz / 1000)
                else "A steady %.2f kHz tone".format(b.freqHz / 1000)) +
                ", %.0f dB above the background: the band ad-tracking kits use to tie a phone to a TV, a shop or another device.".format(b.snrDb),
            kind = EventKind.ULTRASOUND, dedupeKey = "ultrasound@$bucket",
            lat = st.lat, lon = st.lon
        )
    )
}
