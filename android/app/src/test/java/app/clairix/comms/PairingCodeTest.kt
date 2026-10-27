package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

/** Invite and pairing codes survive every form they travel in: QR text, invite link, app link. */
class PairingCodeTest {

    private val code = PairingCode(
        relayUrl = "https://relay.example.com",
        number = "482913605",
        ed25519 = "a+b/c=",
        curve25519 = "d+e/f=",
        sealing = "g+h/i=",
        signature = "j+k/l==",
        name = "Jake & Co",
        invite = "AbCdEfGhIjKlMnOpQrStUv"
    )

    @Test
    fun theQrTextRoundTrips() {
        assertEquals(code, PairingCode.decode(code.encode()))
        assertEquals(code, PairingCode.fromText("  " + code.encode() + "\n"))
    }

    @Test
    fun theInviteLinkRoundTrips() {
        val link = code.inviteLink()
        // The project's site, which Android verifies as Clairix's: never the relay's own page.
        assertEquals(true, link.startsWith("https://clairix.app/i#"))
        assertEquals(code, PairingCode.fromText(link))
        // Pasted as part of the shared message.
        assertEquals(code, PairingCode.fromText("Join me on Clairix: $link"))
    }

    @Test
    fun anInviteLinkFromBeforeBeta8StillOpens() {
        // Beta 7 and earlier sent people to the relay's own invite page.
        val old = "https://relay.example.com/i#" + URLEncoder.encode(code.encode(), "UTF-8")
        assertEquals(code, PairingCode.fromText(old))
    }

    @Test
    fun theAppLinkRoundTrips() {
        // What the invite page hands the app: encodeURIComponent(payload).
        val appLink = "clairix://invite?c=" + URLEncoder.encode(code.encode(), "UTF-8").replace("+", "%20")
        assertEquals(code, PairingCode.fromText(appLink))
    }

    @Test
    fun aContactCodeIsNotAnInvite() {
        val plain = code.copy(invite = null)
        assertEquals(plain, PairingCode.fromText(plain.encode()))
        assertNull(PairingCode.fromText(plain.encode())!!.invite)
    }

    @Test
    fun aMalformedInviteCodeIsDropped() {
        val bad = code.encode().replace("i=AbCdEfGhIjKlMnOpQrStUv", "i=short")
        assertNull(PairingCode.decode(bad)!!.invite)
        assertNull(PairingCode.fromText("https://relay/i#garbage"))
    }
}
