package app.clairix.security

import android.telephony.SmsManager
import app.clairix.AppSettings
import app.clairix.LatLon
import app.clairix.PrefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SOS sheet's extras: off until turned on, the text's wording with and without a
 * position, which edition sends it itself, and what the sheet says came of each.
 */
class SosExtrasTest {

    @Test
    fun bothExtrasAreOffUntilTurnedOn() {
        val ctx = PrefsContext()
        val c = SosExtras.config(ctx)
        assertFalse(c.textOn)
        assertFalse(c.callOn)
        assertNull(c.textTo)
        assertFalse(c.any)
    }

    @Test
    fun settingsAreKeptAndATextNeedsANumber() {
        val ctx = PrefsContext()
        SosExtras.setText(ctx, true)
        assertNull("no number yet: nothing to text", SosExtras.config(ctx).textTo)
        assertFalse(SosExtras.config(ctx).any)
        SosExtras.setNumber(ctx, " +44 7700 900-123 ")
        assertEquals("+447700900123", SosExtras.config(ctx).textTo)
        assertTrue(SosExtras.config(ctx).any)
        SosExtras.setText(ctx, false)
        assertNull("off keeps the number but texts nobody", SosExtras.config(ctx).textTo)
        SosExtras.setCall(ctx, true)
        assertTrue(SosExtras.config(ctx).any)
    }

    @Test
    fun theTrustedNumberIsNotKeptByADuressWipesDecoy() {
        // A duress wipe keeps only AppSettings.DECOY_KEYS of these preferences: the
        // number someone would text for help must not survive it.
        val ctx = PrefsContext()
        SosExtras.setText(ctx, true)
        SosExtras.setNumber(ctx, "07700900123")
        SosExtras.setCall(ctx, true)
        val stored = ctx.prefs(AppSettings.PREFS_NAME).all.keys
        assertEquals(3, stored.size)
        assertTrue(stored.none { it in AppSettings.DECOY_KEYS })
    }

    @Test
    fun numbersAreNormalisedOrRefused() {
        assertEquals("07700900123", SosExtras.normalizeNumber("07700 900123"))
        assertEquals("+12025550143", SosExtras.normalizeNumber("+1 (202) 555-0143"))
        assertEquals("112", SosExtras.normalizeNumber("112"))
        assertNull(SosExtras.normalizeNumber(""))
        assertNull(SosExtras.normalizeNumber("12"))
        assertNull(SosExtras.normalizeNumber("1234567890123456"))
        assertNull(SosExtras.normalizeNumber("call mum"))
        assertNull(SosExtras.normalizeNumber("+44+7700"))
        assertNull(SosExtras.normalizeNumber("*#06#"))
    }

    @Test
    fun theTextCarriesAnOpenStreetMapLinkToThePosition() {
        val body = SosExtras.smsBody(LatLon(51.507351, -0.127758))
        assertEquals(
            "SOS: I need help. Where I am: https://www.openstreetmap.org/?mlat=51.50735&mlon=-0.12776#map=17/51.50735/-0.12776",
            body
        )
    }

    @Test
    fun withoutAPositionTheTextSaysSo() {
        val body = SosExtras.smsBody(null)
        assertTrue(body.startsWith("SOS: I need help."))
        assertTrue(body, body.contains("no position"))
        assertFalse(body.contains("http"))
    }

    @Test
    fun theTextDoesNotNameClairix() {
        assertFalse(SosExtras.smsBody(null).contains("Clairix"))
        assertFalse(SosExtras.smsBody(LatLon(1.0, 2.0)).contains("Clairix"))
    }

    @Test
    fun theTextFitsInOneSmsWithAPosition() {
        // GSM 7-bit, 160 characters: one part, one answer from the network.
        val body = SosExtras.smsBody(LatLon(-33.868820, 151.209290))
        assertTrue(body.length.toString(), body.length <= 160)
        assertTrue(body.all { it.code < 128 })
    }

    @Test
    fun onlyTheFullEditionWithThePermissionSendsItself() {
        assertEquals(SosExtras.TextRoute.DIRECT, SosExtras.route(canSendDirectly = true, permitted = true))
        assertEquals(SosExtras.TextRoute.COMPOSER, SosExtras.route(canSendDirectly = true, permitted = false))
        assertEquals(SosExtras.TextRoute.COMPOSER, SosExtras.route(canSendDirectly = false, permitted = false))
        // The Play edition never sends itself, whatever a permission check says.
        assertEquals(SosExtras.TextRoute.COMPOSER, SosExtras.route(canSendDirectly = false, permitted = true))
    }

    @Test
    fun thePlanSaysWhoPressesSend() {
        val direct = SosExtras.textPlan("07700900123", SosExtras.TextRoute.DIRECT, canSendDirectly = true)
        assertTrue(direct, direct.startsWith("Also texts 07700900123"))
        val play = SosExtras.textPlan("07700900123", SosExtras.TextRoute.COMPOSER, canSendDirectly = false)
        assertTrue(play, play.contains("press send"))
        assertFalse(play, play.contains("permission"))
        val refused = SosExtras.textPlan("07700900123", SosExtras.TextRoute.COMPOSER, canSendDirectly = true)
        assertTrue(refused, refused.contains("SMS permission is off"))
        assertTrue(SosExtras.editionLine(false, false).contains("Google Play"))
        assertTrue(SosExtras.editionLine(true, true).startsWith("Clairix sends the text itself"))
        assertTrue(SosExtras.editionLine(true, false).contains("press send"))
    }

    @Test
    fun theNetworksAnswersBecomeAnOutcome() {
        val ok = SosExtras.OK
        assertEquals(SosExtras.TextOutcome.Sent("1234", true), SosExtras.directOutcome("1234", true, listOf(ok)))
        assertEquals(SosExtras.TextOutcome.Sent("1234", false), SosExtras.directOutcome("1234", false, listOf(ok, ok)))
        assertEquals(SosExtras.TextOutcome.Unconfirmed("1234", true), SosExtras.directOutcome("1234", true, null))
        val failed = SosExtras.directOutcome("1234", true, listOf(ok, SmsManager.RESULT_ERROR_NO_SERVICE))
        assertEquals(SosExtras.TextOutcome.Failed("1234", "there is no mobile service here"), failed)
        val radio = SosExtras.directOutcome("1234", true, listOf(SmsManager.RESULT_ERROR_RADIO_OFF))
        assertTrue((radio as SosExtras.TextOutcome.Failed).reason.contains("flight mode"))
        val odd = SosExtras.directOutcome("1234", true, listOf(99)) as SosExtras.TextOutcome.Failed
        assertTrue(odd.reason.contains("99"))
    }

    @Test
    fun theSheetSaysWhatCameOfTheText() {
        val n = "07700900123"
        assertEquals("Finding your position for the text to $n…", SosExtras.textLine(SosExtras.TextOutcome.Finding(n)))
        assertEquals("Texted $n, with your position.", SosExtras.textLine(SosExtras.TextOutcome.Sent(n, true)))
        assertEquals(
            "Texted $n, without a position (none was found).",
            SosExtras.textLine(SosExtras.TextOutcome.Sent(n, false))
        )
        val unconfirmed = SosExtras.textLine(SosExtras.TextOutcome.Unconfirmed(n, true))
        assertTrue(unconfirmed, unconfirmed.contains("not confirmed"))
        assertEquals(
            "Text to $n not sent: there is no mobile service here.",
            SosExtras.textLine(SosExtras.TextOutcome.Failed(n, "there is no mobile service here"))
        )
        val composer = SosExtras.textLine(SosExtras.TextOutcome.ComposerOpened(n, true, permissionOff = false))
        assertTrue(composer, composer.contains("not sent until you press send"))
        assertFalse(composer, composer.startsWith("Texted"))
        val refused = SosExtras.textLine(SosExtras.TextOutcome.ComposerOpened(n, false, permissionOff = true))
        assertTrue(refused, refused.contains("without a position"))
        assertTrue(refused, refused.contains("SMS permission is off"))
    }

    @Test
    fun theSheetSaysWhetherTheNumberWasDialled() {
        val opened = SosExtras.callLine(SosExtras.CallOutcome.DiallerOpened("112"))
        assertTrue(opened, opened.contains("open with 112 entered"))
        assertTrue(opened, opened.contains("Clairix places no call itself"))
        assertEquals("999 not dialled: no app on this phone can make calls.", SosExtras.callLine(SosExtras.CallOutcome.NoDialler("999")))
        assertEquals("911 not dialled.", SosExtras.callLine(SosExtras.CallOutcome.NotDialled("911")))
    }

    @Test
    fun resultLinesAfterAnSos() {
        val sent = SosExtras.TextOutcome.Sent("1234", true)
        // Text and call set up, the call button never pressed: it says so.
        assertEquals(listOf("Texted 1234, with your position.", "112 not dialled."), SosExtras.resultLines(sent, null, "112"))
        // Only the call set up.
        val dialled = SosExtras.CallOutcome.DiallerOpened("000")
        assertEquals(listOf(SosExtras.callLine(dialled)), SosExtras.resultLines(null, dialled, "000"))
        // Only the text set up.
        assertEquals(listOf("Texted 1234, with your position."), SosExtras.resultLines(sent, null, null))
        assertTrue(SosExtras.resultLines(null, null, null).isEmpty())
    }
}
