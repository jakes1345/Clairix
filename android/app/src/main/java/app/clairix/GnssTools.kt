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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CheckState
import app.clairix.analysis.Gnss
import app.clairix.analysis.GnssMonitor
import app.clairix.analysis.GnssVerdict
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
    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    // While the screen is open and in front the receiver is switched on, so the
    // checks have data whether or not anything else is using it. Leaving the app
    // switches it off again: a page left open in the background kept GPS running.
    LifecycleStartEffect(hasLocation) {
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
                eyebrow = "Satellite signals",
                headline = when (a.verdict) {
                    GnssVerdict.SPOOFED -> "Spoofed"
                    GnssVerdict.JAMMED -> "Jammed"
                    GnssVerdict.SUSPECT -> "Unusual"
                    GnssVerdict.GENUINE -> "Genuine"
                    GnssVerdict.NO_FIX -> "No fix"
                    GnssVerdict.NO_DATA -> "Waiting"
                },
                body = when (a.verdict) {
                    GnssVerdict.SPOOFED -> "The position the phone is being given does not come from satellites. Do not trust the map or any app's location until this clears."
                    GnssVerdict.JAMMED -> "Something is drowning out the satellites here. Navigation, and any tracker that relies on GPS, has lost them."
                    GnssVerdict.SUSPECT -> "Two or more checks look off. Not proof; keep an eye on it."
                    GnssVerdict.GENUINE -> "${a.usedCount} satellites in the fix from ${a.constellationsUsed.joinToString(", ").ifEmpty { "one system" }}, behaving like satellites."
                    GnssVerdict.NO_FIX -> "Satellites are not in the fix right now: indoors, underground or in a deep street canyon. Step outside to check."
                    GnssVerdict.NO_DATA -> if (!hasLocation) "Precise location permission is needed to read the satellites."
                        else "Starting the receiver. A first fix outdoors takes up to a minute."
                },
                tone = tone
            )
        }

        if (!hasLocation) {
            item(key = "§perm") {
                Notice(Tone.Caution, "Grant precise location to Clairix in Android Settings → Apps → Clairix → Permissions.", title = "No location permission")
            }
        }

        if (a.verdict == GnssVerdict.SPOOFED && a.networkLat != null && a.networkLon != null) {
            item(key = "§real") {
                Notice(
                    Tone.Danger,
                    title = "Where Wi-Fi and cell towers put you",
                    text = "%.5f, %.5f. A spoofer can move the satellite position; it cannot move the towers and access points around you.".format(a.networkLat, a.networkLon)
                )
            }
        }

        if (!status.scanning) {
            item(key = "§off") {
                Notice(Tone.Info, "The checks run while this screen is open. Start the scanner to keep them running in the background whenever the GPS is on, as it is when you navigate.", title = "Background checks off")
            }
        }

        item(key = "§checks-hdr") {
            val bad = a.failing.size + a.warning.size
            SectionHeader("Checks", supporting = if (bad == 0) null else "$bad flagged", tone = if (a.failing.isNotEmpty()) Tone.Danger else Tone.Accent)
        }
        item(key = "§checks") {
            ListGroup {
                a.checks.forEachIndexed { i, ch ->
                    if (i > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ch.label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                            when (ch.state) {
                                CheckState.PASS -> StatusPill("Pass", Tone.Safe)
                                CheckState.WARN -> StatusPill("Unusual", Tone.Caution)
                                CheckState.FAIL -> StatusPill("Fail", Tone.Danger)
                                CheckState.NO_DATA -> StatusPill("No data", Tone.Neutral)
                            }
                        }
                        Text(ch.detail, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    }
                }
                if (a.checks.isEmpty()) {
                    Text("No reading yet.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted, modifier = Modifier.padding(16.dp))
                }
            }
        }

        item(key = "§sky-hdr") {
            SectionHeader("Sky", supporting = if (a.visibleCount == 0) null else "${a.usedCount} of ${a.visibleCount} in the fix")
        }
        item(key = "§sky-sum") {
            ClairixCard {
                KeyValueRow("Mean strength", a.meanCn0?.let { "%.1f dB-Hz".format(it) } ?: "—")
                KeyValueRow("Spread", a.spreadCn0?.let { "%.1f dB-Hz".format(it) } ?: "—")
                KeyValueRow("Satellite time vs network", a.timeOffsetMs?.let { "%+.1f s".format(it / 1000.0) } ?: "—")
                KeyValueRow("GPS band gain vs normal", a.agcDeviationDb?.let { "%+.1f dB".format(it) } ?: "Not reported")
            }
        }
        if (a.sats.isNotEmpty()) {
            items(a.sats.take(40), key = { "sat|${it.constellation}|${it.svid}|${it.carrierHz}" }) { s -> SatelliteRow(s) }
        }

        item(key = "§limits") {
            Notice(
                Tone.Info,
                title = "What this can and cannot catch",
                text = "It catches the spoofers that are cheap to build: one transmitter on the ground, a recording replayed, " +
                    "a simulator started at the wrong time, a mock-location app. A careful attacker who simulates a full sky at " +
                    "realistic strengths and the right time passes the signal tests; the Wi-Fi and cell comparison is the one " +
                    "they cannot pass while a tower or access point is in range. Not every phone reports raw measurements or gain, " +
                    "and those checks say so instead of passing."
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
            if (s.used) StatusPill("Fix", Tone.Safe)
        }
    }
}
