package app.clairix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clairix.BuildConfig
import app.clairix.Detection
import app.clairix.EventKind
import app.clairix.EvidenceExport
import app.clairix.Registry
import app.clairix.analysis.StalkwareScanner
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone

/*
 * Safety first. Every screen that can lead someone to remove a tracker, an app
 * or a fingerprint someone else added says the same thing before they do: the
 * person who placed it may notice, removing it can tell them it was found, and
 * the evidence goes with it. Every tech-abuse safety guide leads with that; an
 * app that finds stalkerware and then points straight at Uninstall can bring on
 * the escalation it exists to prevent. The card carries the same fixed
 * sentences everywhere, a way to export the evidence first and a way to the
 * Get help page. Which screens show it is decided here, so it can be tested.
 */

internal object SafetyFirst {

    const val NOTICE = "The person who placed this may notice when it stops reporting or when the phone changes. " +
        "Removing it can tell them you found it."
    const val ADVOCATE = "If this is someone you live with or know, consider talking to an advocate or the police " +
        "before you remove it, and do not do it where they can see."
    const val EXPORT = "Export the evidence first"
    const val GET_HELP = "Get help"

    /** The card's heading on a tracker or a device travelling with you. */
    const val TITLE_REMOVE = "Before you remove it"
    /** On the stalkerware findings. */
    const val TITLE_APP = "Before you remove an app"
    /** On an unlock while you slept, a changed PIN or an added fingerprint or face. */
    const val TITLE_PHONE = "Before you change your PIN or remove a fingerprint"
    /** At the top of the Wipe page, where the evidence Clairix holds is about to go. */
    const val TITLE_WIPE = "If you found a tracker or spyware"

    /** Owners of the phone or its profile the owner has not said she enrolled herself. */
    private fun unconfirmedOwners(r: StalkwareScanner.Result): List<String> =
        r.owners.filter { r.status(it.kind, it.packageName) != StalkwareScanner.GrantStatus.YOURS }.map { it.packageName }

    /**
     * Whether the explainer for [d] carries the card: a tracker on you or a
     * device that travelled with you (both [Detection.following]), or a
     * recognised tracker that keeps showing up. An unidentified device that
     * merely stays nearby is most likely part of the surroundings.
     */
    fun forDetection(d: Detection): Boolean = d.following || (d.persistent && d.identified)

    /**
     * Whether the stalkerware findings lead with the card: any app scored high
     * or medium, any enabled keyboard the scan does not vouch for, or any
     * active device admin it does not vouch for, or a device or profile owner
     * the owner has not confirmed (Family Link runs as Google Play services, a
     * vouched admin). Each of those comes with a way to its settings, where it
     * can be removed.
     */
    fun forStalkerware(r: StalkwareScanner.Result): Boolean =
        r.high.isNotEmpty() || r.medium.isNotEmpty() || r.suspiciousImes.isNotEmpty() ||
            r.activeDeviceAdmins.any { it !in r.vouchedAdmins } || unconfirmedOwners(r).isNotEmpty()

    /** Whether a timeline entry's details carry the card: an unlock-ledger anomaly or a biometric enrolment. */
    fun forEvent(kind: EventKind): Boolean = kind == EventKind.UNLOCK_ANOMALY || kind == EventKind.BIOMETRIC_ENROLLED

    /** Whether the unlock ledger shows the card: the tripwire fired, or an unlock fell in the sleep window. */
    fun forLedger(tripwireTriggered: Boolean, sleepUnlocks: Int): Boolean = tripwireTriggered || sleepUnlocks > 0

    /**
     * Whether opening an app's settings page goes through the sheet first: for
     * an app the scan flagged, and for a keyboard or device admin it does not
     * vouch for. A known app the scan vouches for opens straight away.
     */
    fun gatesSettings(flagged: Boolean, vouched: Boolean): Boolean = flagged || !vouched

    /**
     * For a list page Settings shares between apps (accessibility, notification
     * access, keyboards), the app the sheet should be about when any row's
     * button is tapped: the first of [packages] the scan flagged, in the scan's
     * order, else the first holding a [kind] grant that needs a look (not a
     * known app, not one the owner marked as hers, not one preloaded on the
     * phone); null when none does. A known app's button opens the same page
     * the flagged one can be switched off on, so it asks first too.
     */
    fun sharedPageSubject(r: StalkwareScanner.Result, kind: StalkwareScanner.GrantKind, packages: List<String>): String? =
        r.apps.firstOrNull { it.packageName in packages }?.packageName
            ?: packages.firstOrNull { r.status(kind, it) !in SETTLED }

    private val SETTLED = setOf(
        StalkwareScanner.GrantStatus.KNOWN,
        StalkwareScanner.GrantStatus.YOURS,
        StalkwareScanner.GrantStatus.PRELOADED
    )

    /**
     * The apps whose evidence is saved before the stalkerware page exports the
     * report: every flagged app, highest score first as the scan ranks them,
     * then every keyboard and device admin the scan does not vouch for, then
     * every device or profile owner not confirmed, each once.
     */
    fun evidencePackages(r: StalkwareScanner.Result): List<String> {
        val flagged = r.apps.map { it.packageName }
        val unvouched = r.suspiciousImes + r.activeDeviceAdmins.filter { it !in r.vouchedAdmins }
        return (flagged + unvouched + unconfirmedOwners(r)).distinct()
    }
}

/**
 * The safety card. [title] says what is about to be removed; the two
 * sentences under it are the same on every screen. "Export the evidence first"
 * asks for the passcode when one is set (in [EvidenceExport]'s host), then runs
 * [onExport] when the screen has evidence of its own to add first, and otherwise
 * asks for the evidence report as it stands, which shows the period and the
 * warning before the share sheet; the comms-only edition holds no evidence, so it
 * has no such button there. "Get help" opens the Get help page over whatever is showing.
 */
@Composable
internal fun SafetyFirstCard(
    modifier: Modifier = Modifier,
    title: String = SafetyFirst.TITLE_REMOVE,
    onExport: (() -> Unit)? = null
) {
    Notice(
        Tone.Caution,
        text = SafetyFirst.NOTICE + "\n\n" + SafetyFirst.ADVOCATE,
        modifier = modifier,
        title = title,
        action = {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (BuildConfig.FULL_ACCESS) {
                    SecondaryButton(
                        SafetyFirst.EXPORT,
                        onClick = { if (onExport != null) onExport() else EvidenceExport.request() },
                        tone = Tone.Accent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                GhostButton(SafetyFirst.GET_HELP, onClick = { Registry.requestHelp() }, modifier = Modifier.fillMaxWidth())
            }
        }
    )
}
