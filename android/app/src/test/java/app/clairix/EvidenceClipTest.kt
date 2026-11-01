package app.clairix

import app.clairix.analysis.StalkwareScanner
import app.clairix.ui.components.EXTRA_IS_SENSITIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flagged-app sheet's copy of the evidence: offered only on request and
 * never to a keyboard, which can read the clipboard, and cleared as the sheet
 * closes unless the owner has copied something else since.
 */
class EvidenceClipTest {

    private fun app(pkg: String, ime: Boolean) = StalkwareScanner.SuspiciousApp(
        packageName = pkg, appLabel = pkg, score = 7, reasons = emptyList(),
        installTs = 0L, lastUpdateTs = 0L, sideloaded = true, hasLauncherIcon = false, vpnCapable = false,
        notificationListener = false, accessibilityEnabled = false, imeEnabled = ime, deviceAdminActive = false,
        installerPackage = null
    )

    @Test
    fun aKeyboardIsNeverHandedAClipThatNamesIt() {
        assertFalse("a flagged keyboard", EvidenceClip.offersCopy("com.keys", app("com.keys", ime = true), emptyList()))
        assertFalse("an enabled keyboard the scan did not score", EvidenceClip.offersCopy("com.keys", null, listOf("com.keys")))
        assertTrue("an app that is no keyboard", EvidenceClip.offersCopy("com.spy", app("com.spy", ime = false), listOf("com.keys")))
    }

    @Test
    fun theCopyIsClearedWhenItIsStillThereOrCannotBeSeen() {
        assertFalse("nothing was copied", EvidenceClip.shouldClear(copied = false, label = EvidenceClip.LABEL))
        assertTrue("still the evidence", EvidenceClip.shouldClear(copied = true, label = EvidenceClip.LABEL))
        assertTrue("Android will not say what is there", EvidenceClip.shouldClear(copied = true, label = null))
        assertFalse("the owner copied something else since", EvidenceClip.shouldClear(copied = true, label = "Shopping list"))
    }

    @Test
    fun theSensitiveMarkUsesTheKeyEveryVersionReads() {
        // ClipDescription.EXTRA_IS_SENSITIVE on Android 13; the same key earlier, where keyboards read it.
        assertEquals("android.content.extra.IS_SENSITIVE", EXTRA_IS_SENSITIVE)
    }
}
