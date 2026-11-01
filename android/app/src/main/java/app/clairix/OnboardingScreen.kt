package app.clairix

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.security.AppLock
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.IconWell
import app.clairix.ui.components.ListGroup
import app.clairix.ui.components.LogoMark
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.components.StencilText
import app.clairix.ui.theme.ClairixMotion
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.launch
import java.util.Locale

private const val ONBOARDING_PREFS = "prefs"
/** Also written by a duress wipe's decoy (security/Wiper.kt), which has done its first run. */
internal const val ONBOARDING_DONE_KEY = "onboarding_done"
private const val LOCK_OFFERED_KEY = "onboarding_lock_offered"
// The full app walks through the scanner; the comms-only build has pages about
// encrypted messaging and ends on Get started instead of Start scanning. Both
// ask, on the page before the last, whether anyone else uses this phone.
private val TOTAL_PAGES = if (BuildConfig.FULL_ACCESS) 5 else 4

// ── Helpers ────────────────────────────────────────────────────────────────────

fun isOnboardingDone(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(ONBOARDING_DONE_KEY, false)

fun markOnboardingDone(context: Context) =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(ONBOARDING_DONE_KEY, true).apply()

/**
 * Whether onboarding showed the page that offers a passcode. Skip passes it by;
 * the tour's Settings stop follows up either way while no passcode is set.
 */
fun isLockOffered(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(LOCK_OFFERED_KEY, false)

fun markLockOffered(context: Context) =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(LOCK_OFFERED_KEY, true).apply()

// ── OnboardingScreen ───────────────────────────────────────────────────────────

/**
 * First run: a few pages on what Clairix does and what it needs, a page that
 * offers a passcode for anyone who shares the phone, then the start button,
 * and beside it "Not now" for someone who wants to look around first: that
 * finishes without asking for a permission or starting the scanner, which is
 * then started from Scan → Trackers whenever they choose. The pages scroll on
 * their own if a small phone or large text does not fit them; the page bars
 * and the buttons stay pinned at the bottom.
 */
@Composable
fun OnboardingScreen(startScanService: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { TOTAL_PAGES })
    val scope = rememberCoroutineScope()
    val c = ClairixTheme.colors
    val last = pagerState.currentPage == TOTAL_PAGES - 1
    val nextPage: () -> Unit = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }

    fun finish(scan: Boolean = true) {
        markOnboardingDone(context)
        if (scan) startScanService()
        onComplete()
    }

    Column(Modifier.fillMaxSize().background(c.base)) {
        // ── Top: the mark, and Skip until the last page ──────────────────────
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 8.dp, top = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoMark(size = 28.dp)
            Spacer(Modifier.weight(1f))
            if (!last) {
                GhostButton(
                    "Skip",
                    onClick = { scope.launch { pagerState.scrollToPage(TOTAL_PAGES - 1) } },
                    tone = Tone.Neutral
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (BuildConfig.FULL_ACCESS) {
                when (page) {
                    0 -> IntroPage(
                        eyebrow = "Counter-surveillance",
                        title = "Nothing hides.",
                        lead = "Clairix finds hidden cameras, trackers following you, fake cell towers, rogue " +
                            "Wi-Fi and spyware, and keeps your calls and messages private.",
                        note = "Detection data stays on this phone. The map downloads tiles from OpenStreetMap, " +
                            "which sees your IP address and the area you're viewing."
                    )
                    1 -> ListPage(
                        eyebrow = "Permissions",
                        title = "What it needs",
                        lead = "Three permissions, asked for when you start scanning.",
                        items = listOf(
                            Triple(ClairixIcons.BluetoothSearching, "Nearby devices", "Listens for Bluetooth trackers such as AirTags, Tile and SmartTags."),
                            Triple(Icons.Filled.LocationOn, "Precise location", "Proves a tracker is moving with you, not just nearby, by checking it shows up in different places."),
                            Triple(Icons.Filled.Notifications, "Notifications", "Tells you the moment something is confirmed on you, even with the app closed.")
                        )
                    )
                    2 -> ListPage(
                        eyebrow = "How it works",
                        title = "Watch the watchers",
                        lead = "Clairix learns what's normal around you, then flags what isn't.",
                        items = listOf(
                            Triple(ClairixIcons.TrackChanges, "Trackers", "Matches known tracker signals and checks whether one keeps turning up as you move."),
                            Triple(ClairixIcons.CellTower, "Fake cell towers", "Learns your usual towers, then watches for forced downgrades, unknown cells and signals that are too strong."),
                            Triple(ClairixIcons.Wifi, "Wi-Fi and NFC", "Flags bait hotspots that copy real networks, and NFC tags hidden where they shouldn't be.")
                        )
                    )
                    3 -> LockOfferPage(
                        lead = "If someone else can pick up this phone, give Clairix a passcode of its own. Without " +
                            "one, anyone who opens Clairix can read everything in it: the timeline, your usual places " +
                            "and times, when the phone was unlocked, and your messages.",
                        note = "A passcode keeps what is in Clairix to you. It does not hide the app: Clairix appears " +
                            "by name in the app list and shows a notification while it scans, and while Comms stays online.",
                        onNotNow = nextPage
                    )
                    else -> ReadyPage(
                        comms = "Messaging, calls, SOS and the duress phrase need a Clairix number: an invite from " +
                            "someone already on Clairix, or a relay you run. Everything else here works without one.",
                        title = "Ready when you are",
                        lead = "Start scanning now and keep the app running while you move, or look around first " +
                            "and start it later from Scan → Trackers.",
                        note = "Confirming a tracker is on you takes about 10 minutes and 300 metres of moving together. " +
                            "Android may ask whether Clairix can suggest Wi-Fi networks: that is the Wi-Fi trap, a made-up " +
                            "network name only interception kit answers to. Nothing ever joins it. The tour explains it."
                    )
                }
            } else {
                when (page) {
                    0 -> IntroPage(
                        eyebrow = "Clairix Comms",
                        title = "Yours only.",
                        lead = "Messages and calls for your circle, encrypted on this phone. Only the person you send " +
                            "to can read them. No phone number, no Google account, no carrier.",
                        note = "Your keys, contacts and message history stay on this phone."
                    )
                    1 -> ListPage(
                        eyebrow = "Permissions",
                        title = "What it needs",
                        lead = "Each one is asked for the first time it's needed.",
                        items = listOf(
                            Triple(Icons.Filled.Notifications, "Notifications", "So a new message shows and a call rings while the app is closed."),
                            Triple(Icons.Filled.Call, "Microphone", "For calls. Asked once you have a number, so the first call isn't also the first question."),
                            Triple(ClairixIcons.Videocam, "Camera", "To scan a contact's pairing code. Nothing is recorded."),
                            Triple(Icons.Filled.LocationOn, "Location", "Attached to an SOS so your group knows where you are. Read only for an SOS: the button, or the duress phrase.")
                        )
                    )
                    2 -> LockOfferPage(
                        lead = "If someone else can pick up this phone, give Clairix a passcode of its own. Without " +
                            "one, anyone who opens Clairix can read your messages and see who you talk to.",
                        note = "A passcode keeps what is in Clairix to you. It does not hide the app: Clairix appears " +
                            "by name in the app list and shows a notification while Comms stays online.",
                        onNotNow = nextPage
                    )
                    else -> ReadyPage(
                        title = "You need an invite",
                        lead = "The person who gave you this app sends an invite link or shows a QR code. Opening it " +
                            "gives you a Clairix number and adds them as your first contact.",
                        note = "A Clairix number isn't a phone number. It only reaches other Clairix apps on the same " +
                            "relay. Keep Comms online so calls ring while the app is closed."
                    )
                }
            }
        }

        // ── Bottom: page bars and the main button ────────────────────────────
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PageBars(current = pagerState.currentPage, count = TOTAL_PAGES)
            if (last) {
                PrimaryButton(
                    if (BuildConfig.FULL_ACCESS) "Start scanning" else "Get started",
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (BuildConfig.FULL_ACCESS) {
                    // Nothing is asked for and nothing starts: the scanner waits in Scan → Trackers.
                    GhostButton(
                        "Not now",
                        onClick = { finish(scan = false) },
                        tone = Tone.Neutral,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                PrimaryButton(
                    "Next",
                    onClick = nextPage,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Which page this is: chamfered bars, the current one long and in the accent. */
@Composable
private fun PageBars(current: Int, count: Int) {
    val c = ClairixTheme.colors
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(
                if (i == current) 32.dp else 12.dp,
                animationSpec = tween(ClairixMotion.base, easing = ClairixMotion.standard),
                label = "page-bar"
            )
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .background(if (i == current) c.accent else c.borderStrong, ClairixShapes.Tag)
            )
        }
    }
}

/** A page's eyebrow and its display title. The title is the brand's loud bit. */
@Composable
private fun PageTitle(eyebrow: String, title: String) {
    val c = ClairixTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(16.dp).height(3.dp).background(c.accent))
            Text(eyebrow.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        }
        Text(
            title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.displayMedium,
            color = c.text,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
private fun IntroPage(eyebrow: String, title: String, lead: String, note: String) {
    val c = ClairixTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .size(136.dp)
                .background(c.surface, ClairixShapes.Octagon),
            contentAlignment = Alignment.Center
        ) {
            LogoMark(size = 88.dp, contentDescription = "Clairix")
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(16.dp).height(3.dp).background(c.accent))
                Text(eyebrow.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            // The tagline carries the stencil slit, as it does on the brand book's cover.
            StencilText(
                title.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.displayLarge,
                color = c.text,
                ground = c.base,
                thickness = 4.dp,
                modifier = Modifier.semantics { heading() }
            )
        }
        Text(lead, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ListPage(eyebrow: String, title: String, lead: String, items: List<Triple<ImageVector, String, String>>) {
    val c = ClairixTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        PageTitle(eyebrow, title)
        Text(lead, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        ListGroup {
            items.forEachIndexed { i, (icon, label, desc) ->
                if (i > 0) Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    IconWell(icon, Tone.Accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text(desc, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * "Does anyone else use this phone?" A passcode offered before the first
 * session instead of waiting in Settings to be found, set up right here, with
 * what it does not do said plainly. Showing the page is recorded, so the tour
 * can follow up while no passcode is set.
 */
@Composable
private fun LockOfferPage(lead: String, note: String, onNotNow: () -> Unit) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val enabled by AppLock.enabled.collectAsStateWithLifecycle()
    // The setup stays up after the save until the wipe-limit question that follows it is answered.
    var setting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { markLockOffered(context) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        PageTitle("Before you start", "Does anyone else use this phone?")
        Text(lead, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        when {
            setting -> PasscodeSetupPanel(
                context,
                isChanging = false,
                onDone = { setting = false },
                onCancel = { setting = false }
            )
            enabled -> Notice(
                Tone.Safe,
                "Clairix asks for it whenever the screen has been off, or after 30 seconds away. Settings → " +
                    "App passcode changes that, and adds biometrics or a duress passcode.",
                title = "Passcode set"
            )
            else -> {
                PrimaryButton(
                    "Set a passcode for Clairix",
                    onClick = { setting = true },
                    icon = Icons.Filled.Lock,
                    modifier = Modifier.fillMaxWidth()
                )
                GhostButton("Not now", onClick = onNotNow, tone = Tone.Neutral, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The last page. [comms], when given, says what Comms needs before it can be used. */
@Composable
private fun ReadyPage(title: String, lead: String, note: String, comms: String? = null) {
    val c = ClairixTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(96.dp).background(c.accentContainer, ClairixShapes.Octagon),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(44.dp)
            )
        }
        PageTitle("Last step", title)
        Text(lead, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        if (comms != null) Text(comms, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        Spacer(Modifier.height(8.dp))
    }
}
