package app.clairix.security

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import app.clairix.BuildConfig

/**
 * Keeps Clairix's own screens away from the apps it looks for. Stalkerware reads a phone through
 * an accessibility service (every word on every screen) and tricks the owner through windows it
 * draws over other apps. Two platform switches close both, on every Clairix window:
 *
 * - Android 14+ (API 34): View.setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES) on
 *   the window's root view, which every view under it inherits (View.calculateAccessibilityDataSensitive,
 *   and ViewGroup's override passes a change down to the children already attached). The window's
 *   nodes and events then reach only services that declare android:isAccessibilityTool="true":
 *   AccessibilityInteractionController.isVisibleToAccessibilityService admits a sensitive view
 *   only when AccessibilityManager.isRequestFromAccessibilityTool() (the requesting connection
 *   carries FLAG_SERVICE_IS_ACCESSIBILITY_TOOL, set in AbstractAccessibilityServiceConnection from
 *   AccessibilityServiceInfo.isAccessibilityTool), and the same connection drops a sensitive
 *   event for a service without it. A Compose screen's nodes are virtual children of one
 *   AndroidComposeView, and the controller checks that host view first, so they are covered too.
 *   TalkBack, Switch Access, Voice Access and Select to Speak declare themselves accessibility
 *   tools and keep working; a service that does not sees an empty window. The declaration is
 *   the app's own word (Google Play reviews it; nothing reviews a sideloaded app), so this keeps
 *   out stalkerware that does not claim to be an accessibility tool, not one that lies about it.
 *   The stalkerware check scores every accessibility service either way.
 * - Android 12+ (API 31): Window.setHideOverlayWindows(true), with the normal permission
 *   HIDE_OVERLAY_WINDOWS (src/suite/AndroidManifest.xml): while a Clairix window is showing,
 *   Android hides every other app's TYPE_APPLICATION_OVERLAY window, so nothing can be drawn over
 *   Clairix to cover a warning or capture a tap. System windows and accessibility overlays stay.
 *
 * Instrumented tests (androidTest, FreshUi): UiAutomator reads windows through the test's
 * UiAutomation, and AOSP registers that connection as an accessibility tool unless the test asks
 * otherwise: UiAutomationConnection.connect calls info.setAccessibilityTool(true) whenever
 * UiAutomation.FLAG_NOT_ACCESSIBILITY_TOOL (a @TestApi flag Clairix's tests never pass) is clear
 * (frameworks/base core/java/android/app/UiAutomationConnection.java, android14-release and
 * main; the flag is documented in UiAutomation.java: "UiAutomation sets
 * AccessibilityServiceInfo#isAccessibilityTool() true by default"). So the device tests keep
 * reading Clairix's screens with no exemption here. The emulator's NoopAccessibilityService is not
 * a tool and no longer receives Clairix's events; it only exists to make Compose send them, which
 * it still does (Compose asks whether any service is enabled, not which one).
 *
 * The suite edition only, which is where the stalkerware check lives; the Comms edition's
 * windows are left as they were.
 */
object WindowShield {

    private const val TAG = "WindowShield"

    /** Whether Android has View.setAccessibilityDataSensitive. */
    internal fun dataSensitiveAvailable(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Whether Android has Window.setHideOverlayWindows. */
    internal fun hideOverlaysAvailable(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.S

    /** Whether this build shields its windows at all. */
    internal fun active(fullAccess: Boolean = BuildConfig.FULL_ACCESS): Boolean = fullAccess

    /** Shields [activity]'s window: call in onCreate, after setContent or before; the root view is the decor view. */
    fun shield(activity: Activity) = shield(activity.window)

    /** Shields [window]: other apps' overlays hidden while it shows, its views sensitive to accessibility services. */
    fun shield(window: Window) {
        if (!active()) return
        if (hideOverlaysAvailable(Build.VERSION.SDK_INT)) {
            runCatching { window.setHideOverlayWindows(true) }.onFailure { Log.w(TAG, "could not hide overlays", it) }
        }
        shieldViews(window.decorView)
    }

    /** Marks [root] and everything under it as data-sensitive (Android 14+). */
    fun shieldViews(root: View) {
        if (!active()) return
        // dataSensitiveAvailable, spelled out so lint sees the guard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES) }
                .onFailure { Log.w(TAG, "could not mark the window sensitive", it) }
        }
    }
}

/**
 * Shields the window this is composed in: a dialog or a bottom sheet, which Compose shows in a
 * window of its own, outside the activity's decor view. Put it in the dialog's content.
 */
@Composable
fun ShieldThisWindow() {
    val view = LocalView.current
    LaunchedEffect(view) { WindowShield.shieldViews(view.rootView) }
}
