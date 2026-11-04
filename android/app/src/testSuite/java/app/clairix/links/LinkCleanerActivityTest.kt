package app.clairix.links

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import app.clairix.security.DiscreetMode
import app.clairix.security.LauncherEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What the "Clean link" screen is handed, what it says was removed, and that discreet mode takes it out of other apps' menus. */
@RunWith(RobolectricTestRunner::class)
class LinkCleanerActivityTest {

    @Test
    fun theTextComesFromAShareOrASelection() {
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "see https://example.org/?fbclid=1")
        assertEquals("see https://example.org/?fbclid=1", LinkCleanerActivity.sharedText(shared))
        val selected = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "https://youtu.be/x?si=2" as CharSequence)
        assertEquals("https://youtu.be/x?si=2", LinkCleanerActivity.sharedText(selected))
        assertNull(LinkCleanerActivity.sharedText(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   ")))
        assertNull(LinkCleanerActivity.sharedText(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "https://example.org")))
        assertNull(LinkCleanerActivity.sharedText(null))
    }

    @Test
    fun theSummaryNamesTheRedirectAndTheParametersRemoved() {
        val r = LinkCleaner.cleanText("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2F%3Futm_source%3Dfb%26fbclid%3D1 and https://example.org/?gclid=2")
        assertEquals("redirect through l.facebook.com; utm_source, fbclid, gclid", removedSummary(r))
        assertNull(removedSummary(LinkCleaner.cleanText("https://example.org/plain")))
    }

    @Test
    fun discreetModeTakesCleanLinkOutOfOtherAppsMenus() {
        val app = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val cleaner = ComponentName(app.packageName, DiscreetMode.LINK_CLEANER)
        assertEquals(LinkCleanerActivity::class.java.name, DiscreetMode.LINK_CLEANER)
        DiscreetMode.set(app, on = true, disguise = LauncherEntry.NOTES)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(cleaner))
        DiscreetMode.set(app, on = false, disguise = LauncherEntry.NOTES)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(cleaner))
    }
}
