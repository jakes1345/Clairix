package app.clairix.screenshots

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.clairix.CategoryStatus
import app.clairix.CommsScreen
import app.clairix.DeviceScreen
import app.clairix.HealthFact
import app.clairix.HomeTab
import app.clairix.OnboardingScreen
import app.clairix.PhoneHealth
import app.clairix.PhoneHealthFinding
import app.clairix.Registry
import app.clairix.ScanCategory
import app.clairix.ScanHub
import app.clairix.SettingsScreen
import app.clairix.Severity
import app.clairix.ShellScaffold
import app.clairix.ShellSubTabs
import app.clairix.SosButton
import app.clairix.StatusTone
import app.clairix.homeTabsFor
import app.clairix.security.LockScreen
import app.clairix.ui.theme.ClairixThemeName
import com.android.ide.common.rendering.api.SessionParams
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Screenshots of the main screens in every colour theme, rendered on the JVM by
 * layoutlib. `gradle recordPaparazziFullDebug` writes them to src/test/snapshots;
 * `verifyPaparazziFullDebug` fails when a screen no longer matches.
 *
 * The screens are the app's own composables in the app's own shell. What they
 * show is put in place through the same Registry calls the scanner uses.
 */
class ClairixScreensTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        maxPercentDifference = 0.1
    )

    @After
    fun resetRegistry() {
        Registry.publishPhoneHealth(PhoneHealth())
    }

    /** Draws [content] once per theme, each saved as `<name>_<theme>`. */
    private fun eachTheme(name: String, content: @Composable () -> Unit) {
        for (theme in ClairixThemeName.entries) {
            paparazzi.snapshot(name = "${name}_${theme.name.lowercase(Locale.US)}") {
                ScreenshotHost(theme, content)
            }
        }
    }

    @Test
    fun scanHubNeedsALook() = eachTheme("scan_hub") {
        AppFrame(title = "Scan", selected = HomeTab.SCAN, scanAlert = true) {
            ScanHub(scanning = true, statuses = attentionStatuses(), onOpen = {})
        }
    }

    @Test
    fun scanHubAllClear() = eachTheme("scan_hub_clear") {
        AppFrame(title = "Scan", selected = HomeTab.SCAN, scanAlert = false) {
            ScanHub(scanning = true, statuses = clearStatuses(), onOpen = {})
        }
    }

    @Test
    fun phoneCheck() {
        Registry.publishPhoneHealth(phoneHealthWithFindings())
        eachTheme("phone_check") {
            AppFrame(
                title = ScanCategory.PHONE.title,
                selected = HomeTab.SCAN,
                scanAlert = true,
                onBack = {},
                header = { ShellSubTabs(ScanCategory.PHONE.subTabs, 0) {} }
            ) {
                DeviceScreen(onShown = {})
            }
        }
    }

    @Test
    fun comms() = eachTheme("comms") {
        AppFrame(title = HomeTab.COMMS.label, selected = HomeTab.COMMS, scanAlert = false) {
            CommsScreen(openPeer = null, onPeerConsumed = {})
        }
    }

    @Test
    fun settingsAppearance() = eachTheme("settings_appearance") {
        SettingsScreen(onBack = {})
    }

    @Test
    fun onboarding() = eachTheme("onboarding") {
        OnboardingScreen(startScanService = {}, onComplete = {})
    }

    @Test
    fun lockScreen() = eachTheme("lock") {
        LockScreen()
    }

    // ── Harness ────────────────────────────────────────────────────────────────

    @Composable
    private fun AppFrame(
        title: String,
        selected: HomeTab,
        scanAlert: Boolean,
        onBack: (() -> Unit)? = null,
        header: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
        content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
    ) {
        ShellScaffold(
            title = title,
            onBack = onBack,
            onSettings = {},
            menu = emptyList(),
            tabs = homeTabsFor(fullAccess = true),
            selected = selected,
            alert = { it == HomeTab.SCAN && scanAlert },
            onSelectTab = {},
            header = header,
            sos = { SosButton(onConfirmed = {}) },
            content = content
        )
    }

    private fun attentionStatuses(): Map<ScanCategory, CategoryStatus> = mapOf(
        ScanCategory.TRACKERS to CategoryStatus("1 tracker following you", StatusTone.ALERT),
        ScanCategory.CAMERAS to CategoryStatus("No findings", StatusTone.NEUTRAL),
        ScanCategory.CELL to CategoryStatus("Unusual tower activity", StatusTone.CAUTION),
        ScanCategory.WIFI to CategoryStatus("No risky networks found", StatusTone.CLEAR),
        ScanCategory.NFC to CategoryStatus("No tags read yet", StatusTone.NEUTRAL),
        ScanCategory.PHONE to CategoryStatus("No issues found", StatusTone.CLEAR)
    )

    private fun clearStatuses(): Map<ScanCategory, CategoryStatus> = mapOf(
        ScanCategory.TRACKERS to CategoryStatus("14 devices nearby, none following", StatusTone.CLEAR),
        ScanCategory.CAMERAS to CategoryStatus("No findings", StatusTone.NEUTRAL),
        ScanCategory.CELL to CategoryStatus("No suspicious towers", StatusTone.CLEAR),
        ScanCategory.WIFI to CategoryStatus("No risky networks found", StatusTone.CLEAR),
        ScanCategory.NFC to CategoryStatus("No tags read yet", StatusTone.NEUTRAL),
        ScanCategory.PHONE to CategoryStatus("No issues found", StatusTone.CLEAR)
    )

    /** A phone with USB debugging left on and an old patch level, as the monitor reports them. */
    private fun phoneHealthWithFindings() = PhoneHealth(
        findings = listOf(
            PhoneHealthFinding(
                id = "a11y:com.example.keyboard",
                severity = Severity.HIGH,
                category = "Accessibility service",
                title = "Keyboard Plus reads the screen",
                detail = "An accessibility service can read everything on screen and act for you. " +
                    "Switch it off under Settings > Accessibility if you didn't turn it on."
            )
        ),
        facts = listOf(
            HealthFact("root", "Integrity", "Root access", "Not detected"),
            HealthFact("bootloader", "Integrity", "Bootloader", "Locked"),
            HealthFact("vb", "Integrity", "Verified boot", "Green"),
            HealthFact("selinux", "Integrity", "SELinux", "Enforcing"),
            HealthFact("lock", "Access", "Screen lock", "PIN set"),
            HealthFact("enc", "Access", "Storage encryption", "Active"),
            HealthFact(
                "adb", "Access", "USB debugging", "On", Severity.MEDIUM,
                "Anyone with your unlocked phone and a cable can install apps and read data."
            ),
            HealthFact("dev", "Access", "Developer options", "On"),
            HealthFact("android", "Software", "Android", "15 (API 35)"),
            HealthFact(
                "patch", "Software", "Security patch", "2025-11-01 (11 months old)", Severity.MEDIUM,
                "Install the latest system update."
            )
        ),
        findingsScannedTs = SCANNED_AT
    )

    private companion object {
        /** A fixed instant, so the "Checked" line is the same on every run. */
        const val SCANNED_AT = 1_791_000_000_000L
    }
}
