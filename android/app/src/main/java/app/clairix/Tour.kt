package app.clairix

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.IconWell
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.launch

/*
 * The tour: one page for every place in the app, so nothing that could help someone
 * stays hidden. The pages are built from the app's own lists of tabs, Scan categories,
 * tracker tools and Comms pages, and every one of those lists is matched with an
 * exhaustive `when`, so adding a feature without tour text is a compile error.
 */

private const val TOUR_PREFS = "prefs"
private const val TOUR_SEEN_KEY = "tour_seen"

fun isTourSeen(context: Context): Boolean =
    context.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE).getBoolean(TOUR_SEEN_KEY, false)

fun markTourSeen(context: Context) =
    context.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE).edit().putBoolean(TOUR_SEEN_KEY, true).apply()

/** Where a tour page's "Show me" goes. */
internal sealed interface TourTarget {
    data class Tab(val tab: HomeTab) : TourTarget
    data class Category(val category: ScanCategory, val sub: Int = 0) : TourTarget
    data class TrackerTool(val action: TrackerAction) : TourTarget
    data class CommsPage(val action: CommsAction) : TourTarget
    data class ActivityPage(val sub: Int) : TourTarget
    data object Settings : TourTarget
}

internal data class TourStop(
    val id: String,
    /** The path to it, as the screens name it: "Scan → GPS". */
    val where: String,
    val title: String,
    val what: String,
    val how: String,
    val icon: ImageVector,
    val target: TourTarget? = null
)

internal fun categoryTour(c: ScanCategory): Pair<String, String> = when (c) {
    ScanCategory.TRACKERS -> "Bluetooth trackers travelling with you: AirTags, Tiles, SmartTags and the GPS boxes hidden on cars." to
        "Nearby lists the devices around you. One is marked FOLLOWING only after it has been heard at places far apart while you " +
        "moved, about 10 minutes and 300 m by default. Map shows where each was heard. Tap a device to see what it is, or " +
        "\"Mark as mine\" for your own headphones, watch and car. The buttons above the list open Find a tracker, the " +
        "Self-test beacon and Bluetooth exposure."
    ScanCategory.CAMERAS -> "A sweep for hidden cameras in a room." to
        "Six methods: lens glint with the torch, infrared with the front camera in the dark, a network scan for camera ports, " +
        "camera setup hotspots, Bluetooth setup beacons and a magnetic gauge. The checklist walks you round the room."
    ScanCategory.CELL -> "Fake cell towers (IMSI catchers) and forced downgrades." to
        "It learns the towers you normally use, then flags a forced drop to 2G or 3G, unknown towers where known ones stood, " +
        "impossible signal strengths and other catcher signs. It needs a few days of ordinary use to learn what normal is. " +
        "Tap a finding for what it means."
    ScanCategory.GPS -> "Whether the GPS position comes from satellites at all." to
        "It checks satellite strengths, satellite time against network time, the GPS position against Wi-Fi and cell " +
        "towers, impossible jumps, mock-location apps and jamming. While the page is open the GPS runs; with the scanner on, " +
        "the checks run whenever anything uses the GPS, such as navigation, which warns you on the map."
    ScanCategory.ULTRASOUND -> "Inaudible beacon tones that tie your phone to a TV, a shop or another device." to
        "Run the self-test first: the phone plays a 19 kHz tone and listens for it, to prove it can hear the band. Then Listen " +
        "near the TV or speaker you are wondering about. Only the spectrum is read; no audio is kept."
    ScanCategory.WIFI -> "The network you are on, and every hotspot in range." to
        "Flags open networks, evil twins of networks you use, carrier names on open hotspots and the default names of " +
        "interception kit, and checks the network you are on: encryption, captive portals, where DNS goes. It also sets " +
        "a trap: a made-up network name only this phone knows, which the phone asks for in every scan. A box that " +
        "answers to any name (a Karma or Pineapple interception kit) answers that one too, and is caught. Adding the " +
        "name is why Android may ask, the first time you start scanning, whether Clairix can suggest Wi-Fi networks. " +
        "Nothing joins it: no such network exists. Say no and the trap stays off; everything else still works."
    ScanCategory.NFC -> "NFC tags and cards, and skimmers." to
        "Hold a card or tag to the back of the phone to read what it carries and flag tags that look planted. In the full " +
        "edition a card can be kept in the vault, locked by your screen lock, and presented from the phone."
    ScanCategory.PHONE -> "This phone's own security, and spyware on it." to
        "Health is a checklist: patch level, USB debugging, screen lock, Advanced Protection, the Bluetooth name, 2G, VPN " +
        "leaks and more, each with a tap to the setting that fixes it. Stalkerware checks every app for what spyware needs. " +
        "Certificates lists the ones that could read your encrypted traffic, and the relay's key pin."
}

internal fun trackerToolTour(a: TrackerAction): Pair<String, String> = when (a) {
    TrackerAction.LOCATE -> "Walk to a tracker by its signal." to
        "Pick a device, hold the phone out and turn slowly: the compass marks the direction the signal is strongest, and the " +
        "warmer-and-colder line shows whether you are getting closer."
    TrackerAction.SELF_TEST -> "Proves the scanner can hear a tracker." to
        "The phone briefly pretends to be an AirTag and checks the scanner picks it up. Some phones cannot hear themselves; " +
        "the test says so instead of passing."
    TrackerAction.BLUETOOTH -> "What your Bluetooth gives a roadside or in-store sensor." to
        "Your phone's name (one tap to swap a personal one for the model name), whether it is discoverable, every paired " +
        "accessory with whether its address is fixed for life, which rode along on your drives, and any Flock camera battery, " +
        "gunshot detector or Flock hotspot the radios have heard."
}

internal fun commsPageTour(a: CommsAction): Pair<String, String> = when (a) {
    CommsAction.FAKE_CALL -> "A pretend incoming call, as a reason to leave." to
        "Set a time and a caller name. The phone rings with its real ringtone and a real-looking call screen, even locked."
    CommsAction.DURESS -> "A sentence that quietly calls for help." to
        "Type it into any chat: it is sent as written, and underneath an SOS with your last position goes to every group you " +
        "are in. In no group it sends nothing, and the page says so."
    CommsAction.MESH -> "Messages with no network: phone to phone over Bluetooth." to
        "Turn it on when the network is down or not to be trusted. Texts, group texts and SOS that cannot reach the relay " +
        "travel from phone to phone, sealed, until one reaches the person they are for. Every phone on the way needs the mesh " +
        "on. Off by default, because a phone running it can be seen to be running Clairix."
    CommsAction.WIPE -> "Erase everything Clairix holds, now." to
        "Type WIPE and press the button: notifications, keys in the secure hardware, the account on the relay and every file " +
        "go. The same wipe runs on the duress passcode or after too many wrong passcodes, if you turn those on in Settings."
}

/** The Activity tab's sub-tabs, in [ACTIVITY_SUB_TABS] order. A test checks every one has text. */
internal val ACTIVITY_TOUR: Map<String, Pair<String, String>> = mapOf(
    "Timeline" to ("Everything Clairix has noticed, newest first." to
        "Every alert, finding and scan start or stop, with where it happened when a position was known. Tap a row for details."),
    "Patterns" to ("How predictable your routine is." to
        "From your own location history: the places you stay, which is home and which is work, how regular your departures " +
        "are and how often you take the same route. Worked out on the phone; nothing is sent."),
    "Unlocks" to ("Every time the phone was unlocked." to
        "Flags unlocks while you sleep and wrong passcodes. Turn on the unlock ledger here (it asks for usage access and a " +
        "device-admin permission that only watches logins).")
)

internal fun tourStops(fullAccess: Boolean): List<TourStop> {
    val out = ArrayList<TourStop>()
    out += TourStop(
        "bars", "Bottom of the screen", "Find your way",
        if (fullAccess) "Four tabs: Scan, Comms, Drive and Activity. The gear at the top right of every screen is Settings."
        else "Comms is the whole app. The gear at the top right is Settings.",
        "A red dot on a tab means it has something you should see. The back arrow at the top left steps back one screen.",
        Icons.Filled.Settings
    )
    out += TourStop(
        "sos", "Bottom right, every screen", "SOS",
        "An emergency alert with your position to every group you are in, end-to-end encrypted.",
        "Hold the red octagon until it fills, then confirm. Your position is read at that moment and at no other time.",
        Icons.Filled.Warning
    )
    if (fullAccess) {
        out += TourStop(
            "scan", "Scan", "The Scan hub",
            "Every check Clairix runs, as cards. Each card's line says what it last found.",
            "Start scanning from Trackers to keep the background checks running: trackers, towers, Wi-Fi, the phone itself and " +
                "the GPS. Hidden cameras, NFC and ultrasound run when you open them.",
            ClairixIcons.TrackChanges, TourTarget.Tab(HomeTab.SCAN)
        )
        for (c in ScanCategory.entries) {
            val (what, how) = categoryTour(c)
            val subs = if (c.subTabs.isEmpty()) "" else " Sub-tabs: ${c.subTabs.joinToString(", ")}."
            out += TourStop("cat_${c.name}", "Scan → ${c.title}", c.title, what, how + subs, c.icon, TourTarget.Category(c))
            if (c == ScanCategory.TRACKERS) for (a in TrackerAction.entries) {
                val (w, h) = trackerToolTour(a)
                out += TourStop("tool_${a.name}", "Scan → Trackers → ${a.title}", a.title, w, h, a.icon, TourTarget.TrackerTool(a))
            }
        }
    }
    out += TourStop(
        "comms", "Comms", "Messages and calls",
        "End-to-end encrypted messages, calls, voicemail and groups. The relay that carries them cannot read them and is not told who sent them.",
        "Add someone with an invite link or by scanning their QR code. If a contact's security key changes, their messages wait " +
            "in a review box until you check. In any conversation or group, Disappearing sets how long after they are read " +
            "messages are deleted, on both phones. Screenshots are blocked on these screens." +
            if (fullAccess) " The menu (⋮) at the top holds Fake call, Duress phrase, Offline mesh and Wipe Clairix." else "",
        ClairixIcons.Forum, TourTarget.Tab(HomeTab.COMMS)
    )
    if (fullAccess) {
        for (a in CommsAction.entries) {
            val (w, h) = commsPageTour(a)
            out += TourStop("comms_${a.name}", "Comms → ⋮ → ${a.title}", a.title, w, h, a.icon, TourTarget.CommsPage(a))
        }
        out += TourStop(
            "drive", "Drive", "Navigate, Radio, Vehicle",
            "Navigate: turn-by-turn on offline maps you download per region, routed around known plate readers and spoken before " +
                "one. Radio: with an RTL-SDR stick in the USB port, a scanner and FM receiver, and aircraft from their ADS-B broadcasts. " +
                "Vehicle: live engine data from a Bluetooth OBD-II adapter.",
            "Pick the region to download from the map's search bar. Long-press the map to route to a point. Report a hazard from " +
                "the bar at the bottom while navigating; reports are anonymous.",
            ClairixIcons.DirectionsCar, TourTarget.Tab(HomeTab.DRIVE)
        )
        ACTIVITY_SUB_TABS.forEachIndexed { i, name ->
            val (w, h) = ACTIVITY_TOUR.getValue(name)
            out += TourStop("act_$name", "Activity → $name", name, w, h, ClairixIcons.History, TourTarget.ActivityPage(i))
        }
    }
    out += TourStop(
        "settings", "Gear, top right", "Settings",
        "App passcode (with biometrics, wipe after wrong passcodes and a duress passcode), how long history is kept, theme" +
            if (fullAccess) ", scanning options, trusted devices, and Export and Clear for your data." else ", and your data.",
        "This tour is in Settings too, under About, whenever you want it again.",
        Icons.Filled.Settings, TourTarget.Settings
    )
    return out
}

/**
 * The tour itself: a page per [TourStop], swiped or stepped through. "Show me" closes
 * the tour and opens that place; Done or Skip closes it. Either way it is marked seen.
 */
@Composable
internal fun TourScreen(onClose: () -> Unit, onGo: (TourTarget) -> Unit) {
    val stops = remember { tourStops(BuildConfig.FULL_ACCESS) }
    val pages = stops.size + 1
    val pager = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()
    val c = ClairixTheme.colors
    val last = pager.currentPage == pages - 1

    Column(Modifier.fillMaxSize().background(c.base)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 8.dp, top = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tour · ${pager.currentPage + 1} of $pages", style = MaterialTheme.typography.labelLarge, color = c.textMuted, modifier = Modifier.weight(1f))
            GhostButton(if (last) "Close" else "Skip", onClick = onClose, tone = Tone.Neutral)
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (page == 0) {
                    Text("Where everything is", style = MaterialTheme.typography.headlineLarge, color = c.text)
                    Text(
                        "${stops.size} stops, one for each place in Clairix. Swipe, or use Next. \"Show me\" takes you there; " +
                            "you can come back to the tour from Settings → About.",
                        style = MaterialTheme.typography.bodyLarge, color = c.textMuted
                    )
                    stops.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(s.icon, contentDescription = null, tint = c.accent)
                            Text(s.where, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        }
                    }
                } else {
                    val s = stops[page - 1]
                    IconWell(s.icon, Tone.Accent, size = 56.dp)
                    StatusPill(s.where, Tone.Info)
                    Text(s.title, style = MaterialTheme.typography.headlineMedium, color = c.text)
                    Text(s.what, style = MaterialTheme.typography.bodyLarge, color = c.text)
                    Text(s.how, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
                    s.target?.let { t ->
                        Spacer(Modifier.height(4.dp))
                        SecondaryButton("Show me", onClick = { onGo(t) }, tone = Tone.Accent)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (last) PrimaryButton("Done", onClick = onClose, modifier = Modifier.fillMaxWidth())
            else PrimaryButton("Next", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth())
        }
    }
}
