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
import androidx.compose.ui.res.stringResource
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
                    stringResource(R.string.shell_skip),
                    onClick = { scope.launch { pagerState.scrollToPage(TOTAL_PAGES - 1) } },
                    tone = Tone.Neutral
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (BuildConfig.FULL_ACCESS) {
                when (page) {
                    0 -> IntroPage(
                        eyebrow = stringResource(R.string.shell_counter_surveillance),
                        title = stringResource(R.string.settings_nothing_hides),
                        lead = stringResource(R.string.shell_clairix_finds_hidden_cameras_trackers_following),
                        note = stringResource(R.string.shell_detection_data_stays_on_this_phone)
                    )
                    1 -> ListPage(
                        eyebrow = stringResource(R.string.shell_permissions),
                        title = stringResource(R.string.shell_what_it_needs),
                        lead = stringResource(R.string.shell_three_permissions_asked_for_when_you),
                        items = listOf(
                            Triple(ClairixIcons.BluetoothSearching, stringResource(R.string.shell_nearby_devices), stringResource(R.string.shell_listens_for_bluetooth_trackers_such_as)),
                            Triple(Icons.Filled.LocationOn, stringResource(R.string.shell_precise_location), stringResource(R.string.shell_proves_a_tracker_is_moving_with)),
                            Triple(Icons.Filled.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.shell_tells_you_the_moment_something_is))
                        )
                    )
                    2 -> ListPage(
                        eyebrow = stringResource(R.string.shell_how_it_works),
                        title = stringResource(R.string.shell_watch_the_watchers),
                        lead = stringResource(R.string.shell_clairix_learns_whats_normal_around_you),
                        items = listOf(
                            Triple(ClairixIcons.TrackChanges, stringResource(R.string.scan_trackers), stringResource(R.string.shell_matches_known_tracker_signals_and_checks)),
                            Triple(ClairixIcons.CellTower, stringResource(R.string.shell_fake_cell_towers), stringResource(R.string.shell_learns_your_usual_towers_then_watches)),
                            Triple(ClairixIcons.Wifi, stringResource(R.string.shell_wi_fi_and_nfc), stringResource(R.string.shell_flags_bait_hotspots_that_copy_real))
                        )
                    )
                    3 -> LockOfferPage(
                        lead = stringResource(R.string.shell_if_someone_else_can_pick_up),
                        note = stringResource(R.string.shell_a_passcode_keeps_what_is_in),
                        onNotNow = nextPage
                    )
                    else -> ReadyPage(
                        comms = stringResource(R.string.shell_messaging_calls_sos_and_the_duress),
                        title = stringResource(R.string.shell_ready_when_you_are),
                        lead = stringResource(R.string.shell_start_scanning_now_and_keep_the),
                        note = stringResource(R.string.shell_confirming_a_tracker_is_on_you)
                    )
                }
            } else {
                when (page) {
                    0 -> IntroPage(
                        eyebrow = stringResource(R.string.shell_clairix_comms),
                        title = stringResource(R.string.shell_yours_only),
                        lead = stringResource(R.string.shell_messages_and_calls_for_your_circle),
                        note = stringResource(R.string.shell_your_keys_contacts_and_message_history)
                    )
                    1 -> ListPage(
                        eyebrow = stringResource(R.string.shell_permissions),
                        title = stringResource(R.string.shell_what_it_needs),
                        lead = stringResource(R.string.shell_each_one_is_asked_for_the),
                        items = listOf(
                            Triple(Icons.Filled.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.shell_so_a_new_message_shows_and)),
                            Triple(Icons.Filled.Call, stringResource(R.string.shell_microphone), stringResource(R.string.shell_for_calls_asked_once_you_have)),
                            Triple(ClairixIcons.Videocam, stringResource(R.string.shell_camera), stringResource(R.string.shell_to_scan_a_contacts_pairing_code)),
                            Triple(Icons.Filled.LocationOn, stringResource(R.string.settings_location), stringResource(R.string.shell_attached_to_an_sos_so_your))
                        )
                    )
                    2 -> LockOfferPage(
                        lead = stringResource(R.string.shell_if_someone_else_can_pick_up_2),
                        note = stringResource(R.string.shell_a_passcode_keeps_what_is_in_2),
                        onNotNow = nextPage
                    )
                    else -> ReadyPage(
                        title = stringResource(R.string.shell_you_need_an_invite),
                        lead = stringResource(R.string.shell_the_person_who_gave_you_this),
                        note = stringResource(R.string.shell_a_clairix_number_isnt_a_phone)
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
                    if (BuildConfig.FULL_ACCESS) stringResource(R.string.scan_start_scanning) else stringResource(R.string.shell_get_started),
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (BuildConfig.FULL_ACCESS) {
                    // Nothing is asked for and nothing starts: the scanner waits in Scan → Trackers.
                    GhostButton(
                        stringResource(R.string.comms_not_now),
                        onClick = { finish(scan = false) },
                        tone = Tone.Neutral,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                PrimaryButton(
                    stringResource(R.string.settings_next),
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
            LogoMark(size = 88.dp, contentDescription = stringResource(R.string.app_name))
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
        PageTitle(stringResource(R.string.shell_before_you_start), stringResource(R.string.shell_does_anyone_else_use_this_phone))
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
                stringResource(R.string.shell_clairix_asks_for_it_whenever_the),
                title = stringResource(R.string.shell_passcode_set)
            )
            else -> {
                PrimaryButton(
                    stringResource(R.string.shell_set_a_passcode_for_clairix),
                    onClick = { setting = true },
                    icon = Icons.Filled.Lock,
                    modifier = Modifier.fillMaxWidth()
                )
                GhostButton(stringResource(R.string.comms_not_now), onClick = onNotNow, tone = Tone.Neutral, modifier = Modifier.fillMaxWidth())
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
        PageTitle(stringResource(R.string.shell_last_step), title)
        Text(lead, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        if (comms != null) Text(comms, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        Spacer(Modifier.height(8.dp))
    }
}
