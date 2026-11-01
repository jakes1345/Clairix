package app.clairix

import app.clairix.analysis.IdentityResolver
import app.clairix.analysis.TrustedIdentities
import app.clairix.detect.Signatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Mark as mine" follows the device, not one of its addresses: a rotation the scanner
 * links stays the owner's, another unit of the product does not, and trust in a device
 * that rotates by design lasts a day before the owner is asked again.
 */
class TrustBookTest {

    private val t0 = 1_700_000_000_000L
    private val a = "6C:00:00:00:00:0A"
    private val b = "6C:00:00:00:00:0B"
    private val stranger = "6C:00:00:00:00:0C"

    @Test
    fun aRotationTheResolverLinksStaysTrustedAndIsAddedToTheDevice() {
        val resolver = IdentityResolver()
        val identities = TrustedIdentities()
        var book = TrustBook.EMPTY.trust(a, "Keys", rotating = true, now = t0)

        // Her own AirTag under address A, trusted.
        val first = resolver.resolve(a, -60, "airtag-fp", t0)
        assertTrue(identities.check(first.identity.id, a, book.addresses).trusted)

        // A goes quiet; fifteen seconds later B appears with the same fingerprint and
        // signal, and the resolver links it as a rotation of the same identity.
        val rotated = resolver.resolve(b, -62, "airtag-fp", t0 + 15_000L)
        assertTrue(rotated.rotated)
        val check = identities.check(rotated.identity.id, b, book.addresses)
        // Not observed, not alerted on: the scanner returns before the tracker sees it.
        assertTrue(check.trusted)
        assertEquals(b, check.learned)
        assertEquals(a, check.knownAddress)

        book = book.extend(b, a)
        assertTrue(b in book.addresses)
        assertEquals(listOf(a, b), book.device(b)!!.addresses)
        // The rotation does not renew the day.
        assertEquals(t0 + TrustBook.ROTATING_TRUST_MS, book.device(b)!!.until)
    }

    @Test
    fun anotherDeviceOfTheSameKindIsNotTrusted() {
        val resolver = IdentityResolver()
        val identities = TrustedIdentities()
        val book = TrustBook.EMPTY.trust(a, "Keys", rotating = true, now = t0)
        val mine = resolver.resolve(a, -60, "airtag-fp", t0)
        assertTrue(identities.check(mine.identity.id, a, book.addresses).trusted)
        // Heard at the same time as hers: a second AirTag, its own identity.
        val other = resolver.resolve(stranger, -61, "airtag-fp", t0 + 2_000L)
        assertFalse(other.rotated)
        assertFalse(identities.check(other.identity.id, stranger, book.addresses).trusted)
    }

    @Test
    fun takingTrustBackRemovesEveryAddressAndTheKey() {
        val identities = TrustedIdentities()
        var book = TrustBook.EMPTY.trust(a, null, rotating = true, now = t0).extend(b, a)
        assertTrue(identities.check("id1", a, book.addresses).trusted)
        book = book.untrust(b)
        assertTrue(book.addresses.isEmpty())
        // The key went with it: a later address linked to it is a stranger.
        assertFalse(identities.check("id1", stranger, book.addresses).trusted)
        assertFalse(identities.check("id1", a, book.addresses).trusted)
    }

    @Test
    fun trustInARotatingDeviceLapsesAfterADayAndAFixedOneDoesNot() {
        val car = "00:1A:2B:3C:4D:5E"
        val book = TrustBook.EMPTY
            .trust(a, "Keys", rotating = true, now = t0)
            .trust(car, "Civic", rotating = false, now = t0)
        assertEquals(0L, book.device(car)!!.until)
        val sameDay = book.expire(t0 + TrustBook.ROTATING_TRUST_MS - 1)
        assertTrue(sameDay === book)
        val nextDay = book.expire(t0 + TrustBook.ROTATING_TRUST_MS)
        assertFalse(a in nextDay.addresses)
        assertTrue(car in nextDay.addresses)
    }

    @Test
    fun extendingNeedsATrustedDevice() {
        val book = TrustBook.EMPTY.trust(a, null, rotating = false, now = t0)
        assertTrue(book.extend(b, stranger) === book)
        assertTrue(book.extend(a, a) === book)
    }

    @Test
    fun theBookSurvivesASaveAndTakesInAddressesAnOlderVersionSaved() {
        val legacyOnly = "11:22:33:44:55:66"
        val saved = TrustBook.EMPTY.trust(a, "Keys", rotating = true, now = t0).extend(b, a).toJson()
        val loaded = TrustBook.from(saved, legacy = setOf(a, b, legacyOnly))
        assertEquals(setOf(a, b, legacyOnly), loaded.addresses)
        assertEquals("Keys", loaded.device(a)!!.label)
        assertEquals(t0 + TrustBook.ROTATING_TRUST_MS, loaded.device(b)!!.until)
        // An address only the plain set knew is trusted for good, as it always was.
        assertEquals(0L, loaded.device(legacyOnly)!!.until)
        assertNull(loaded.device(stranger))
        // Nothing saved at all: the plain set alone.
        assertEquals(setOf(legacyOnly), TrustBook.from(null, setOf(legacyOnly)).addresses)
        assertEquals(setOf(legacyOnly), TrustBook.from("not json", setOf(legacyOnly)).addresses)
    }

    @Test
    fun whatCountsAsRotating() {
        fun d(tracker: TrackerType?, rotations: Int = 0, kind: String? = null) = Detection(
            key = "k", address = a, name = "n", rssi = -60, tracker = tracker, threat = Threat.LOW, score = 0,
            firstSeen = t0, lastSeen = t0, sightings = 1, persistent = false, following = false,
            confidence = FollowConfidence.NONE, displacementM = 0.0, places = 0, rotations = rotations,
            addresses = 1, approxMetres = null, addressKind = kind
        )
        assertTrue(TrustBook.rotating(d(Signatures.AIRTAG)))
        assertTrue(TrustBook.rotating(d(null, rotations = 2)))
        assertTrue(TrustBook.rotating(d(null, kind = "resolvable private (rotates)")))
        // A car, headphones, a GPS box: one address for life.
        assertFalse(TrustBook.rotating(d(null, kind = "public")))
        assertFalse(TrustBook.rotating(d(Signatures.GPS_BLE)))
        assertFalse(TrustBook.rotating(d(null)))
    }
}
