package app.clairix.security

import android.Manifest
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.clairix.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The window shield on Android 14 (Robolectric's SDK): every view of a shielded window is
 * data-sensitive, views added later inherit it, and other apps' overlays are hidden, in the suite
 * edition; the Comms edition's windows are left alone.
 */
@RunWith(RobolectricTestRunner::class)
class WindowShieldTest {

    @Test
    fun theSwitchesExistFromTheirAndroidVersions() {
        assertFalse(WindowShield.dataSensitiveAvailable(33))
        assertTrue(WindowShield.dataSensitiveAvailable(34))
        assertTrue(WindowShield.dataSensitiveAvailable(36))
        assertFalse(WindowShield.hideOverlaysAvailable(30))
        assertTrue(WindowShield.hideOverlaysAvailable(31))
        assertTrue(WindowShield.active(fullAccess = true))
        assertFalse(WindowShield.active(fullAccess = false))
    }

    @Test
    fun everyViewUnderAShieldedRootIsSensitiveAndSoIsOneAddedLater() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val before = TextView(context).also(root::addView)
        WindowShield.shieldViews(root)
        val after = TextView(context).also(root::addView)
        assertEquals(BuildConfig.FULL_ACCESS, root.isAccessibilityDataSensitive)
        assertEquals(BuildConfig.FULL_ACCESS, before.isAccessibilityDataSensitive)
        assertEquals(BuildConfig.FULL_ACCESS, after.isAccessibilityDataSensitive)
        // A view of an unshielded window is not.
        assertFalse(TextView(context).isAccessibilityDataSensitive)
    }

    @Test
    fun anActivityWindowIsShieldedWholeAndHidesOverlays() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val content = TextView(activity)
        activity.setContentView(content)
        WindowShield.shield(activity)
        assertEquals(BuildConfig.FULL_ACCESS, activity.window.decorView.isAccessibilityDataSensitive)
        assertEquals(BuildConfig.FULL_ACCESS, content.isAccessibilityDataSensitive)
        val attrs = activity.window.attributes
        val privateFlags = WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(attrs)
        val hide = WindowManager.LayoutParams::class.java.getField("SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS").getInt(null)
        assertEquals(BuildConfig.FULL_ACCESS, (privateFlags and hide) != 0)
    }

    @Test
    fun withoutThePermissionTheOverlaySwitchFailsQuietlyAndTheRestStillHolds() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        WindowShield.shield(activity)
        assertEquals(BuildConfig.FULL_ACCESS, activity.window.decorView.isAccessibilityDataSensitive)
    }
}
