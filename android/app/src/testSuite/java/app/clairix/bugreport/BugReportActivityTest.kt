package app.clairix.bugreport

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.clairix.security.DiscreetMode
import app.clairix.security.LauncherEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** What "Check a bug report" is handed, that it never reaches for the network, and that discreet mode hides it. */
@RunWith(RobolectricTestRunner::class)
class BugReportActivityTest {

    @Test
    fun theFileComesFromAShareOrTheZipOfAShareWithItsScreenshot() {
        val zip = Uri.parse("content://com.android.shell/bugreports/bugreport-x.zip")
        val png = Uri.parse("content://com.android.shell/bugreports/screenshot-x.png")
        assertEquals(zip, BugReportActivity.sharedStream(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, zip)))
        val both = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(png, zip))
        assertEquals(zip, BugReportActivity.sharedStream(both))
        assertNull(BugReportActivity.sharedStream(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, zip)))
        assertNull(BugReportActivity.sharedStream(null))
    }

    @Test
    fun discreetModeTakesItOutOfTheShareSheet() {
        val app = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val target = ComponentName(app.packageName, DiscreetMode.BUG_REPORT)
        assertEquals(BugReportActivity::class.java.name, DiscreetMode.BUG_REPORT)
        DiscreetMode.set(app, on = true, disguise = LauncherEntry.NOTES)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(target))
        DiscreetMode.set(app, on = false, disguise = LauncherEntry.NOTES)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(target))
    }

    @Test
    fun nothingHereCanReachTheNetwork() {
        val forbidden = listOf(
            "java.net.URL", "java.net.Socket", "HttpURLConnection", "okhttp3", "java.net.InetAddress", "URLConnection",
            "WebView", "DownloadManager", "Volley", "ktor",
        )
        for (name in listOf("BugReportActivity.kt", "BugReportParser.kt", "BugReportZip.kt")) {
            val f = listOf("src/suite/java/app/clairix/bugreport/$name", "app/src/suite/java/app/clairix/bugreport/$name").map(::File).first { it.exists() }
            val text = f.readText()
            for (word in forbidden) assertFalse("$name mentions $word", text.contains(word))
        }
    }

    @Test
    fun theManifestTakesTheBugReportTypeAndZips() {
        val manifest = listOf("src/suite/AndroidManifest.xml", "app/src/suite/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        val block = manifest.substringAfter("android:name=\".bugreport.BugReportActivity\"").substringBefore("</activity>")
        for (type in listOf("application/vnd.android.bugreport", "application/zip")) assertTrue(type, block.contains("android:mimeType=\"$type\""))
        assertTrue(block.contains("android.intent.action.SEND_MULTIPLE"))
    }
}
