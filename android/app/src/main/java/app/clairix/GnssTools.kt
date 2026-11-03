package app.clairix

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CheckState
import app.clairix.analysis.Gnss
import app.clairix.analysis.GnssMonitor
import app.clairix.analysis.GnssVerdict
import app.clairix.analysis.PositionText
import app.clairix.analysis.Positioning
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.ListGroup
import app.clairix.ui.components.Notice
import app.clairix.ui.components.ScreenList
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusHero
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.components.color
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall

// The GPS category: whether the satellite signals the phone receives come from
// satellites, check by check, with the sky the receiver hears.

@Composable
internal fun GnssScreen() {
    val context = LocalContext.current
    val a by Registry.gnss.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val positioning by Registry.positioning.collectAsStateWithLifecycle()
    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    // While the screen is open and in front the receiver is switched on, so the
    // checks have data whether or not anything else is using it. Leaving the app
    // switches it off again: a page left open in the background kept GPS running.
    // Where positions come from is looked up afresh each time, scanner or not.
    LifecycleStartEffect(hasLocation) {
        Positioning.refresh(context)
        val handle = if (hasLocation) GnssMonitor.acquire(context, foreground = true) else null
        onStopOrDispose { handle?.let { GnssMonitor.release(it) } }
    }

    val tone = when (a.verdict) {
        GnssVerdict.SPOOFED, GnssVerdict.JAMMED -> Tone.Danger
        GnssVerdict.SUSPECT -> Tone.Caution
        GnssVerdict.GENUINE -> Tone.Safe
        else -> Tone.Neutral
    }

    ScreenList {
        item(key = "§verdict") {
            StatusHero(
                eyebrow = stringResource(R.string.scan_satellite_signals),
                headline = when (a.verdict) {
                    GnssVerdict.SPOOFED -> stringResource(R.string.scan_spoofed)
                    GnssVerdict.JAMMED -> stringResource(R.string.scan_jammed)
                    GnssVerdict.SUSPECT -> stringResource(R.string.scan_unusual)
                    GnssVerdict.GENUINE -> stringResource(R.string.scan_genuine)
                    GnssVerdict.NO_FIX -> stringResource(R.string.scan_no_fix)
                    GnssVerdict.NO_DATA -> stringResource(R.string.scan_waiting)
                },
                body = when (a.verdict) {
                    GnssVerdict.SPOOFED -> stringResource(R.string.scan_the_position_the_phone_is_being)
                    GnssVerdict.JAMMED -> stringResource(R.string.scan_something_is_drowning_out_the_satellites)
                    GnssVerdict.SUSPECT -> stringResource(R.string.scan_one_check_failed_on_its_own)
                    GnssVerdict.GENUINE -> "${a.usedCount} satellites in the fix from ${a.constellationsUsed.joinToString(", ").ifEmpty { "one system" }}, behaving like satellites."
                    GnssVerdict.NO_FIX -> stringResource(R.string.scan_satellites_are_not_in_the_fix)
                    GnssVerdict.NO_DATA -> if (!hasLocation) stringResource(R.string.scan_precise_location_permission_is_needed_to)
                        else stringResource(R.string.scan_starting_the_receiver_a_first_fix)
                },
                tone = tone
            )
        }

        if (!hasLocation) {
            item(key = "§perm") {
                Notice(Tone.Caution, stringResource(R.string.scan_grant_precise_location_to_clairix_in), title = stringResource(R.string.scan_no_location_permission))
            }
        }

        // Where the phone's positions come from, when that is not Google Play services.
        // Why the Wi-Fi/cell comparison cannot run, when it cannot, is said on its row below.
        PositionText.source(positioning.source)?.let { line ->
            item(key = "§source") {
                Notice(
                    if (PositionText.sourceIsProblem(positioning.source)) Tone.Caution else Tone.Info,
                    line,
                    title = stringResource(R.string.scan_position_source)
                )
            }
        }

        // The network position is offered as the real one only when a recent one agrees
        // with the satellites: when the two disagree nothing here knows which is wrong
        // (a confidently wrong Wi-Fi fix looks exactly like a spoofer), and an old one
        // says nothing about now.
        val vouched = a.vouchedNetwork
        if (a.verdict == GnssVerdict.SPOOFED && vouched != null) {
            item(key = "§real") {
                Notice(
                    Tone.Danger,
                    title = stringResource(R.string.scan_where_wi_fi_and_cell_towers),
                    text = "%.5f, %.5f. A spoofer can move the satellite position; it cannot move the towers and access points around you.".format(vouched.first, vouched.second)
                )
            }
        }
        if ((a.verdict == GnssVerdict.SPOOFED || a.verdict == GnssVerdict.SUSPECT) && a.networkDisputed) {
            item(key = "§disputed") {
                Notice(
                    if (a.verdict == GnssVerdict.SPOOFED) Tone.Danger else Tone.Caution,
                    title = stringResource(R.string.scan_two_positions_disagree),
                    text = stringResource(R.string.scan_the_satellites_and_the_wi_fi)
                )
            }
        }

        if (!status.scanning) {
            item(key = "§off") {
                Notice(Tone.Info, stringResource(R.string.scan_the_checks_run_while_this_screen), title = stringResource(R.string.scan_background_checks_off))
            }
        }

        item(key = "§checks-hdr") {
            val bad = a.failing.size + a.warning.size
            SectionHeader(stringResource(R.string.scan_checks), supporting = if (bad == 0) null else "$bad flagged", tone = if (a.failing.isNotEmpty()) Tone.Danger else Tone.Accent)
        }
        item(key = "§checks") {
            ListGroup {
                a.checks.forEachIndexed { i, ch ->
                    if (i > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ch.label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                            when (ch.state) {
                                CheckState.PASS -> StatusPill(stringResource(R.string.scan_pass), Tone.Safe)
                                CheckState.WARN -> StatusPill(stringResource(R.string.scan_unusual), Tone.Caution)
                                CheckState.FAIL -> StatusPill(stringResource(R.string.scan_fail), Tone.Danger)
                                CheckState.NO_DATA -> StatusPill(stringResource(R.string.scan_no_data), Tone.Neutral)
                            }
                        }
                        Text(ch.detail, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    }
                }
                if (a.checks.isEmpty()) {
                    Text(stringResource(R.string.scan_no_reading_yet), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted, modifier = Modifier.padding(16.dp))
                }
            }
        }

        item(key = "§sky-hdr") {
            SectionHeader(stringResource(R.string.scan_sky), supporting = if (a.visibleCount == 0) null else stringResource(R.string.scan_of_in_the_fix, a.usedCount, a.visibleCount))
        }
        item(key = "§sky-sum") {
            ClairixCard {
                KeyValueRow(stringResource(R.string.scan_mean_strength), a.meanCn0?.let { "%.1f dB-Hz".format(it) } ?: "—")
                KeyValueRow(stringResource(R.string.scan_spread), a.spreadCn0?.let { "%.1f dB-Hz".format(it) } ?: "—")
                KeyValueRow(stringResource(R.string.scan_satellite_time_vs_network), a.timeOffsetMs?.let { "%+.1f s".format(it / 1000.0) } ?: "—")
                KeyValueRow(stringResource(R.string.scan_gps_band_gain_vs_normal), a.agcDeviationDb?.let { "%+.1f dB".format(it) } ?: stringResource(R.string.scan_not_reported))
            }
        }
        if (a.sats.isNotEmpty()) {
            items(a.sats.take(40), key = { "sat|${it.constellation}|${it.svid}|${it.carrierHz.toString()}" }) { s -> SatelliteRow(s) }
        }

        item(key = "§limits") {
            Notice(
                Tone.Info,
                title = stringResource(R.string.scan_what_this_can_and_cannot_catch),
                text = stringResource(R.string.scan_it_catches_the_spoofers_that_are)
            )
        }
    }
}

@Composable
private fun SatelliteRow(s: app.clairix.analysis.GnssSat) {
    val c = ClairixTheme.colors
    val band = s.carrierHz?.let { if (it < 1_300e6f) " L5" else "" } ?: ""
    ClairixCard(spacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${Gnss.name(s.constellation)} ${s.svid}$band", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.width(120.dp))
            Box(Modifier.weight(1f).height(8.dp).background(c.surfaceOverlay, ClairixShapes.Tag)) {
                Box(
                    Modifier.fillMaxWidth((s.cn0 / 60f).coerceIn(0f, 1f)).height(8.dp)
                        .background(if (s.used) Tone.Safe.color() else c.textSubtle, ClairixShapes.Tag)
                )
            }
            Text("%.0f".format(s.cn0), style = MaterialTheme.typography.monoSmall, color = c.textMuted, modifier = Modifier.width(28.dp))
            if (s.used) StatusPill(stringResource(R.string.comms_fix), Tone.Safe)
        }
    }
}
