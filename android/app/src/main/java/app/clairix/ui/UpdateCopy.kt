package app.clairix.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clairix.BuildConfig
import app.clairix.comms.AppUpdate
import app.clairix.comms.LatestRelease
import app.clairix.comms.UpdateNotice
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.Tone

/*
 * Updates. Clairix Full is installed from a download page and does not update
 * itself, so once a day the app reads a signed note of the newest version and
 * of the oldest one still safe (comms/AppUpdate.kt). It shows in two places:
 * an offer in Settings > About, which the owner can put off until a newer
 * version comes; and, while this version is below the oldest safe one, a
 * banner on the Scan and Comms screens that cannot be put off, and that says
 * why the app has stopped sending. The words are here, so they can be tested.
 */

internal object UpdateCopy {

    const val GET_IT = "Get the update"
    const val LATER = "Not now"

    /** The banner's heading while this version is below the oldest safe one. */
    const val UNSAFE_TITLE = "Update Clairix: this version has a known security flaw"

    fun offerTitle(newer: LatestRelease) = "Update available: ${newer.versionName}"

    /** The banner: what is wrong, what the app does about it until the update, and what the update brings. */
    fun unsafeBody(current: String, newer: LatestRelease) =
        "Clairix $current has a security flaw that ${newer.versionName} fixes. Until it is updated, Clairix sends " +
            "nothing but SOS alerts, no messages and no calls: what this version sends may not be safe. ${newer.notes}"

    /** The offer in Settings > About; below the oldest safe version it says what the banner says. */
    fun offerBody(notice: UpdateNotice, newer: LatestRelease, fromPlay: Boolean): String {
        val how = if (fromPlay) "Google Play has it." else "Clairix Full does not update itself: the download page has it, with its checksum."
        val unsafe = if (notice.unsafe) " This version has a known security flaw: until it is updated, Clairix sends nothing but SOS alerts." else ""
        return "${newer.notes}$unsafe $how"
    }
}

/** The Play edition is updated through Google Play; every other edition from the download page. */
internal fun updatesFromPlay(flavor: String = BuildConfig.FLAVOR): Boolean = flavor.startsWith("play")

internal fun updateUrl(fromPlay: Boolean = updatesFromPlay()): String =
    if (fromPlay) "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}" else "https://clairix.app/download"

/** Opens where this build is updated from. */
fun openUpdate(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The caution on the Scan and Comms screens while this version is below the
 * oldest safe one ([UpdateNotice.unsafe]); nothing otherwise. It stays until
 * the app is updated.
 */
@Composable
fun UnsafeVersionBanner(notice: UpdateNotice, modifier: Modifier = Modifier) {
    val newer = notice.newer
    if (!notice.unsafe || newer == null) return
    val context = LocalContext.current
    Notice(
        Tone.Caution,
        text = UpdateCopy.unsafeBody(BuildConfig.VERSION_NAME, newer),
        modifier = modifier,
        title = UpdateCopy.UNSAFE_TITLE,
        action = { GhostButton(UpdateCopy.GET_IT, onClick = { openUpdate(context) }) }
    )
}

/**
 * The offer to update in Settings > About ([UpdateNotice.offer]); nothing
 * otherwise. "Not now" puts it off until a newer version is published, and is
 * not offered below the oldest safe version.
 */
@Composable
fun UpdateOffer(notice: UpdateNotice, modifier: Modifier = Modifier) {
    val newer = notice.newer
    if (!notice.offer || newer == null) return
    val context = LocalContext.current
    Notice(
        if (notice.unsafe) Tone.Caution else Tone.Accent,
        text = UpdateCopy.offerBody(notice, newer, updatesFromPlay()),
        modifier = modifier,
        title = UpdateCopy.offerTitle(newer),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(UpdateCopy.GET_IT, onClick = { openUpdate(context) })
                if (!notice.unsafe) {
                    GhostButton(UpdateCopy.LATER, onClick = { AppUpdate.dismiss(context, newer.versionCode) }, tone = Tone.Neutral)
                }
            }
        }
    )
}
