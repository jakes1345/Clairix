package app.clairix.screenshots

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.clairix.ACTIVITY_SUB_TABS
import app.clairix.CellScreen
import app.clairix.HomeTab
import app.clairix.NfcScreen
import app.clairix.ScanCategory
import app.clairix.ScanScreen
import app.clairix.ShellScaffold
import app.clairix.ShellSubTabs
import app.clairix.SosButton
import app.clairix.TimelineScreen
import app.clairix.TrackerActionsRow
import app.clairix.WifiScreen
import app.clairix.Registry
import app.clairix.WifiNetwork
import app.clairix.WifiSecurity
import app.clairix.WifiStatus
import app.clairix.homeTabsFor
import app.clairix.DuressPage
import app.clairix.FakeCallPage
import app.clairix.DriveContent
import app.clairix.ObdData
import app.clairix.SdrState
import app.clairix.ui.theme.ClairixThemeName
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test

/**
 * The other screens, in the default Acid theme and their first-run state. The
 * hidden-camera sweep is left out: it opens the camera through CameraX, which
 * needs a device.
 */
class AllScreensTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        maxPercentDifference = 0.1
    )

    private fun shot(name: String, content: @Composable () -> Unit) =
        paparazzi.snapshot(name = name) { ScreenshotHost(ClairixThemeName.ACID, content) }

    @Composable
    private fun Frame(
        title: String,
        selected: HomeTab,
        onBack: (() -> Unit)? = null,
        header: @Composable ColumnScope.() -> Unit = {},
        content: @Composable BoxScope.() -> Unit
    ) = ShellScaffold(
        title = title, onBack = onBack, onSettings = {}, menu = emptyList(),
        tabs = homeTabsFor(true), selected = selected, alert = { false }, onSelectTab = {},
        header = header, sos = { SosButton(onConfirmed = {}) }, content = content
    )

    @Test
    fun trackers() = shot("trackers") {
        Frame(ScanCategory.TRACKERS.title, HomeTab.SCAN, onBack = {}, header = {
            ShellSubTabs(ScanCategory.TRACKERS.subTabs, 0) {}
            TrackerActionsRow {}
        }) { ScanScreen(onStart = {}, onStop = {}, hasPermissions = { true }) }
    }

    @Test
    fun cell() = shot("cell") {
        Frame(ScanCategory.CELL.title, HomeTab.SCAN, onBack = {}) { CellScreen() }
    }

    @Test
    fun wifi() = shot("wifi") {
        Frame(ScanCategory.WIFI.title, HomeTab.SCAN, onBack = {}) { WifiScreen() }
    }

    /** The "In range" group with networks in it, drawn as lazy items that join into one card. */
    @Test
    fun wifiInRange() {
        Registry.publishWifiStatus(
            WifiStatus(
                available = true,
                wifiEnabled = true,
                nearby = listOf(
                    network("Harbour Café", "a4:2b:b0:11:3c:01", -48, 4, WifiSecurity.WPA2, "Wi-Fi 6"),
                    network("Harbour Café Guest", "a6:2b:b0:11:3c:02", -55, 3, WifiSecurity.OPEN, "Wi-Fi 6"),
                    network("DIRECT-7F-Printer", "0e:9a:41:77:20:9f", -71, 2, WifiSecurity.WPA2, "Wi-Fi 4"),
                    network("", "3c:84:6a:d2:51:7e", -83, 1, WifiSecurity.WPA3, null, hidden = true)
                )
            )
        )
        try {
            shot("wifi_in_range") {
                Frame(ScanCategory.WIFI.title, HomeTab.SCAN, onBack = {}) { WifiScreen() }
            }
        } finally {
            Registry.publishWifiStatus(WifiStatus())
        }
    }

    private fun network(
        ssid: String, bssid: String, rssi: Int, level: Int, security: WifiSecurity, standard: String?,
        hidden: Boolean = false
    ) = WifiNetwork(
        ssid = ssid, bssid = bssid, rssi = rssi, level = level, frequencyMhz = 5180, channel = 36,
        band = "5 GHz", security = security, standard = standard, hidden = hidden,
        flags = if (security.secure) emptyList() else listOf("Open network: traffic can be read nearby"),
        connected = false, ageMs = 0L
    )

    @Test
    fun nfc() = shot("nfc") {
        Frame(ScanCategory.NFC.title, HomeTab.SCAN, onBack = {}) {
            NfcScreen(onAddToVault = { _, _, _ -> }, onRemoveVault = {}, onEmulate = {}, onUnlockVault = {}, onLockVault = {}, onEraseVault = {})
        }
    }

    @Test
    fun timeline() = shot("timeline") {
        Frame(HomeTab.ACTIVITY.label, HomeTab.ACTIVITY, header = { ShellSubTabs(ACTIVITY_SUB_TABS, 0) {} }) { TimelineScreen() }
    }

    @Test
    fun fakeCall() = shot("fake_call") {
        Frame("Fake call", HomeTab.COMMS, onBack = {}) { FakeCallPage() }
    }

    @Test
    fun duress() = shot("duress") {
        Frame("Duress phrase", HomeTab.COMMS, onBack = {}) { DuressPage() }
    }

    @Test
    fun driveSensors() = shot("drive") {
        Frame(HomeTab.DRIVE.label, HomeTab.DRIVE) {
            DriveContent(
                showRadio = true, showVehicle = true, obd = ObdData(), sdrState = SdrState.Closed,
                channels = emptyList(), activeChannel = null, squelchOpen = false, isRecording = false,
                lastRecordingPath = null, aircraft = emptyMap(), driveModeActive = false, squelchLevel = 0.3f,
                onSetSquelch = {}, onAddChannel = { _, _ -> }, onRemoveChannel = {}, onSetChannelActive = { _, _ -> },
                onConnectObd = {}, onDisconnectObd = {}, onToggleDriveMode = {}, onOpenRecording = {}
            )
        }
    }
}
