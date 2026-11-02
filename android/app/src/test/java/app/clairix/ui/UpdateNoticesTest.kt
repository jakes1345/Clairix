package app.clairix.ui

import app.clairix.comms.LatestRelease
import app.clairix.comms.UpdateNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the update offer and the unsafe-version banner say, and where they send the owner. */
class UpdateNoticesTest {

    private val newer = LatestRelease(1_700_000_000L, 29, "3.0.0-beta.9", 27, "Fixes a flaw that let a crafted link take over a verified contact.")

    @Test
    fun theBannerSaysWhatIsWrongWhatStopsAndWhatTheUpdateBrings() {
        val body = UpdateCopy.unsafeBody("3.0.0-beta.6", newer)
        assertTrue(body.contains("3.0.0-beta.6"))
        assertTrue(body.contains("3.0.0-beta.9 fixes"))
        assertTrue("it says the brake leaves SOS alone", body.contains("nothing but SOS alerts"))
        assertTrue(body.endsWith(newer.notes))
    }

    @Test
    fun theOfferSaysWhereTheUpdateIsAndBelowTheFloorWhatStops() {
        val behind = UpdateNotice(newer, offer = true, unsafe = false)
        val full = UpdateCopy.offerBody(behind, newer, fromPlay = false)
        assertTrue(full.startsWith(newer.notes))
        assertTrue(full.contains("does not update itself"))
        assertFalse(full.contains("SOS"))
        assertTrue(UpdateCopy.offerBody(behind, newer, fromPlay = true).contains("Google Play"))
        val unsafe = UpdateCopy.offerBody(UpdateNotice(newer, offer = true, unsafe = true), newer, fromPlay = false)
        assertTrue(unsafe.contains("known security flaw"))
        assertEquals("Update available: 3.0.0-beta.9", UpdateCopy.offerTitle(newer))
    }

    @Test
    fun thePlayEditionUpdatesFromPlayAndEveryOtherFromTheDownloadPage() {
        assertTrue(updatesFromPlay("playSuite"))
        assertFalse(updatesFromPlay("fullSuite"))
        assertFalse(updatesFromPlay("fullComms"))
        assertEquals("https://clairix.app/download", updateUrl(fromPlay = false))
        assertTrue(updateUrl(fromPlay = true).startsWith("https://play.google.com/store/apps/details?id="))
    }
}
