package app.clairix

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.Tone

private const val ONBOARDING_PREFS = "prefs"
private const val ONBOARDING_DONE_KEY = "onboarding_done"
// The full app walks through the scanner; the comms-only build has three pages
// about encrypted messaging and ends on GET STARTED instead of START SCANNING.
private val TOTAL_PAGES = if (BuildConfig.FULL_ACCESS) 4 else 3

// ── Helpers ────────────────────────────────────────────────────────────────────

fun isOnboardingDone(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(ONBOARDING_DONE_KEY, false)

fun markOnboardingDone(context: Context) =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(ONBOARDING_DONE_KEY, true).apply()

// ── Colour tokens local to this file ──────────────────────────────────────────

// ── OnboardingScreen ───────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(startScanService: () -> Unit, onComplete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pagerState = rememberPagerState(pageCount = { TOTAL_PAGES })
    val scope = rememberCoroutineScope()

    fun finish() {
        markOnboardingDone(context)
        startScanService()
        onComplete()
    }

    Box(Modifier.fillMaxSize().background(ClairixTheme.colors.base)) {
        // Pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (BuildConfig.FULL_ACCESS) {
                when (page) {
                    0 -> OPage1()
                    1 -> OPage2()
                    2 -> OPage3()
                    else -> OPage4(onStart = { finish() })
                }
            } else {
                when (page) {
                    0 -> OCommsPage1()
                    1 -> OCommsPage2()
                    else -> OCommsPage3(onStart = { finish() })
                }
            }
        }

        // Skip button — top right, hidden on last page
        if (pagerState.currentPage < TOTAL_PAGES - 1) {
            GhostButton(
                "Skip",
                onClick = { scope.launch { pagerState.scrollToPage(TOTAL_PAGES - 1) } },
                tone = Tone.Neutral
            )
        }

        // Page indicator dots + NEXT button — pinned at bottom
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Dot indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(TOTAL_PAGES) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        Modifier
                            .size(if (active) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) ClairixTheme.colors.accent else ClairixTheme.colors.textSubtle.copy(alpha = 0.5f)
                            )
                    )
                }
            }

            // NEXT button — hidden on the last page (which has its own Start button)
            if (pagerState.currentPage < TOTAL_PAGES - 1) {
                PrimaryButton(
                    "Next",
                    onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                    modifier = Modifier.width(200.dp)
                )
            }
        }
    }
}

// ── Page 1: App purpose ────────────────────────────────────────────────────────

@Composable
private fun OPage1() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Shield graphic
        Box(
            Modifier
                .size(120.dp)
                .background(ClairixTheme.colors.surface, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("A", style = MaterialTheme.typography.headlineLarge, color = ClairixTheme.colors.accent)
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Clairix",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Counter-surveillance for professionals",
            style = MaterialTheme.typography.titleMedium,
            color = ClairixTheme.colors.accent,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Clairix continuously scans for hidden surveillance devices — " +
            "Bluetooth trackers, IMSI catchers (fake cell towers), rogue WiFi access " +
            "points, and NFC tags — so you can detect and document covert monitoring " +
            "in real time.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Detection data stays on this device. The Map tab downloads map tiles from " +
            "OpenStreetMap, which sees your IP address and the area you're viewing.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(120.dp))
    }
}

// ── Page 2: Permissions ────────────────────────────────────────────────────────

@Composable
private fun OPage2() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 72.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Permissions",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Three permissions are needed for full functionality:",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        OPermissionItem(
            label = "Nearby Devices (Bluetooth)",
            desc = "Scans for BLE advertising packets from trackers " +
                   "(AirTags, Tile, Samsung SmartTag) in the vicinity."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Precise Location",
            desc = "Required to map where detected devices appear alongside you. " +
                   "Confirming that a tracker is following — not just nearby — " +
                   "requires GPS to prove shared movement across distance."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Notifications",
            desc = "Sends an alert the moment a device is confirmed as following, " +
                   "even when the app is in the background."
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "You will be prompted for permissions when you start scanning.",
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OPermissionItem(label: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ClairixTheme.colors.surface, RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(8.dp)
                .background(ClairixTheme.colors.accent, CircleShape)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        }
    }
}

// ── Page 3: How it works ───────────────────────────────────────────────────────

@Composable
private fun OPage3() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 72.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "How It Works",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        OHowItWorksItem(
            color = ClairixTheme.colors.accent,
            title = "BLE Tracker Detection",
            text = "Bluetooth trackers broadcast advertising packets continuously. Clairix " +
                   "matches known signatures (AirTag, Tile, SmartTag) and monitors whether a device " +
                   "reappears across multiple locations — confirming it is following rather than " +
                   "just passing by."
        )
        Spacer(Modifier.height(10.dp))
        OHowItWorksItem(
            color = ClairixTheme.colors.info,
            title = "IMSI Catcher Detection",
            text = "Clairix builds a local baseline of your usual cell towers, then watches " +
                   "for anomalies — forced technology downgrades, unknown cells at familiar " +
                   "locations, abnormally strong signals — that indicate a portable surveillance " +
                   "device impersonating a legitimate tower."
        )
        Spacer(Modifier.height(10.dp))
        OHowItWorksItem(
            color = ClairixTheme.colors.textSubtle,
            title = "WiFi & NFC",
            text = "Rogue access points mimic carrier networks to intercept traffic. NFC tags " +
                   "hidden in objects can track location. Clairix flags suspicious SSIDs " +
                   "and known covert-tracking NFC chip types in real time."
        )
    }
}

@Composable
private fun OHowItWorksItem(color: Color, title: String, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ClairixTheme.colors.surface, RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(60.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        }
    }
}

// ── Comms-only build: page 1, what this is ────────────────────────────────────

@Composable
private fun OCommsPage1() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(120.dp)
                .background(ClairixTheme.colors.surface, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("A", style = MaterialTheme.typography.headlineLarge, color = ClairixTheme.colors.accent)
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Clairix Comms",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Private messages and calls for your circle",
            style = MaterialTheme.typography.titleMedium,
            color = ClairixTheme.colors.accent,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Everything you send is encrypted on this phone and only the person you send it to " +
            "can read it. The relay that carries it sees neither what a message says nor who " +
            "sent it. Calls are encrypted the same way. No phone number, no Google account and " +
            "no carrier are involved.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Your keys, contacts and message history stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(120.dp))
    }
}

// ── Comms-only build: page 2, permissions ─────────────────────────────────────

@Composable
private fun OCommsPage2() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 72.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Permissions",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Each one is asked for when it is first needed:",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        OPermissionItem(
            label = "Notifications",
            desc = "So a new message shows and an incoming call rings while the app is closed."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Microphone",
            desc = "For calls. Asked once you have a number, so the first call is not also the first question."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Camera",
            desc = "To scan a contact's pairing code. Asked when you tap SCAN; nothing is recorded."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Location",
            desc = "Attached to an SOS so your group knows where you are. Read at the moment you press SOS and at no other time."
        )
    }
}

// ── Comms-only build: page 3, ready ───────────────────────────────────────────

@Composable
private fun OCommsPage3(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(120.dp)
                .background(ClairixTheme.colors.surface, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = ClairixTheme.colors.safe, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "You need an invite",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "The person who gave you this app sends you an invite link or shows you a QR code. " +
            "Opening it registers you with their relay and gives you a Clairix number; it also adds " +
            "them as your first contact.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "A Clairix number is not a phone number. It reaches other Clairix apps on the same relay " +
            "and nothing else. Keep COMMS set to Online so calls ring while the app is closed.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(44.dp))
        PrimaryButton("Get started", onClick = onStart, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(140.dp))
    }
}

// ── Page 4: Ready ──────────────────────────────────────────────────────────────

@Composable
private fun OPage4(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(120.dp)
                .background(ClairixTheme.colors.surface, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = ClairixTheme.colors.safe, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Ready to Scan",
            style = MaterialTheme.typography.headlineLarge,
            color = ClairixTheme.colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Clairix is ready. Start scanning now to begin monitoring for surveillance devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "For best results: keep the app running while travelling. Confirming a tracker " +
            "is following typically requires 10 minutes and 300 metres of shared movement.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(44.dp))
        PrimaryButton("Start scanning", onClick = onStart, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(140.dp))
    }
}
