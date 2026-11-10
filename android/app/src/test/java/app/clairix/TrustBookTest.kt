package app.clairix

import app.clairix.analysis.IdentityResolver
import app.clairix.analysis.TrustedIdentities
import app.clairix.detect.CadenceFingerprinter
import app.clairix.detect.Signatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Mark as mine" follows the device across an address change only when the scanner can
 * prove it is the same unit: a certain cadence stitch does, a resolver link or a stitch
 * between two units of one product does not, and trust in a device that rotates by
 * design lasts a day before the owner is asked again.
 */
class TrustBookTest {

    private val t0 = 1_700_000_000_000L
    private val a = "6C:00:00:00:00:0A"
    private val b = "6C:00:00:00:00:0B"
    private val stranger = "6C:00:00:00:00:0C"

    @Test
    fun aTagTheResolverLinksToHerOwnTrustedTagIsNotTrusted() {
        // Her own AirTag under A, trusted, left at the door; a tag planted in the car
        // under B is first heard fifteen seconds after A goes quiet, as faint as A was
        // last heard. Every AirTag fingerprints alike, so the resolver files B under A's
        // identity. That link must not carry her trust onto the planted tag.
        val resolver = IdentityResolver()
        val book = TrustBook.EMPTY.trust(a, "Keys", rotating = true, now = t0)
        val first = resolver.resolve(a, -88, "airtag-fp", t0)
        assertTrue(TrustedIdentities.check(a, book.addresses, provenFrom = null).trusted)

        val linked = resolver.resolve(b, -90, "airtag-fp", t0 + 15_000L)
        assertTrue(linked.rotated)
        assertEquals(first.identity.id, linked.identity.id)
        val check = TrustedIdentities.check(b, book.addresses, provenFrom = null)
        assertFalse(check.trusted)
        assertNull(check.learned)
    }

    @Test
    fun onlyACertainCadenceStitchOntoATrustedAddressCarriesTrust() {
        val book = TrustBook.EMPTY.trust(a, "Earbuds", rotating = true, now = t0)
        fun match(certain: Boolean, indistinguishable: Boolean, logicalId: String = "id1") =
            CadenceFingerprinter.Match(logicalId, certain, indistinguishable, from = a)

        // Two units of one product: never proof, whatever else the stitch says.
        assertNull(TrustedIdentities.provenPredecessor(match(certain = false, indistinguishable = true), "id1"))
        assertNull(TrustedIdentities.provenPredecessor(match(certain = false, indistinguishable = false), "id1"))
        // A stitch onto another logical device than the one the packet was filed under.
        assertNull(TrustedIdentities.provenPredecessor(match(certain = true, indistinguishable = false, logicalId = "id9"), "id1"))
        assertNull(TrustedIdentities.provenPredecessor(null, "id1"))

        val proven = TrustedIdentities.provenPredecessor(match(certain = true, indistinguishable = false), "id1")
        assertEquals(a, proven)
        val check = TrustedIdentities.check(b, book.addresses, proven)
        // Not observed, not alerted on: the scanner returns before the tracker sees it.
        assertTrue(check.trusted)
        assertEquals(b, check.learned)
        assertEquals(a, check.knownAddress)

        val grown = book.extend(b, a)
        assertEquals(listOf(a, b), grown.device(b)!!.addresses)
        // The rotation does not renew the day.
        assertEquals(t0 + TrustBook.ROTATING_TRUST_MS, grown.device(b)!!.until)

        // A certain stitch onto an address she never trusted carries nothing.
        assertFalse(TrustedIdentities.check(b, book.addresses, provenFrom = stranger).trusted)
    }

    @Test
    fun theFingerprinterSaysWhichAddressACertainStitchFollows() {
        val fp = CadenceFingerprinter()
        fun gadget(address: String, atMs: Long) = CadenceFingerprinter.Packet(
            address = address, timestampNanos = atMs * 1_000_000L, nowMs = atMs, rssi = -65,
            primaryPhy = 1, advertisingSid = 3, hasRecord = true, txPower = -8,
            company = 0x0999, mfrPayload = byteArrayOf(0x07, 0x11, 0x22, 0x33), services = emptySet(), payloadLength = 22
        )
        val jitter = longArrayOf(0, 3, 7, 1, 9, 4, 2, 8, 5, 6)
        for (i in 0 until 15) fp.stitch(gadget(a, t0 + i * 1_285L + jitter[i % jitter.size]))
        fp.assign(a, "id1")
        val last = t0 + 14 * 1_285L
        val match = (0 until 6).firstNotNullOfOrNull { i ->
            fp.stitch(gadget(b, last + 5_000 + i * 1_285L + jitter[i % jitter.size]))
        }
        assertEquals("id1", match!!.logicalId)
        assertTrue(match.certain)
        assertEquals(a, TrustedIdentities.provenPredecessor(match, "id1"))
    }

    @Test
    fun takingTrustBackRemovesEveryAddress() {
        var book = TrustBook.EMPTY.trust(a, null, rotating = true, now = t0).extend(b, a)
        assertTrue(TrustedIdentities.check(a, book.addresses, provenFrom = null).trusted)
        book = book.untrust(b)
        assertTrue(book.addresses.isEmpty())
        // A later address proved to follow it is a stranger: nothing it follows is trusted.
        assertFalse(TrustedIdentities.check(stranger, book.addresses, provenFrom = a).trusted)
        assertFalse(TrustedIdentities.check(a, book.addresses, provenFrom = null).trusted)
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

    @Test
    fun aCarsTireSensorsAreMarkedForGoodEachOnItsOwnAndSurviveASave() {
        val tires = mapOf("80:EA:CA:00:00:01" to "My car's tire sensor, wheel 1", "81:EA:CA:00:00:02" to "My car's tire sensor, wheel 2")
        val book = TrustBook.EMPTY.trust(a, "Watch", rotating = false, now = t0).trustTires(tires, t0)
        assertTrue(book.hasTires)
        assertEquals(3, book.devices.size)
        assertTrue(book.devices.filter { it.tire }.all { it.until == 0L && it.addresses.size == 1 })
        assertTrue(book.expire(t0 + 365L * 24 * 3600_000L).hasTires)

        val reloaded = TrustBook.from(book.toJson(), emptySet())
        assertTrue(reloaded.hasTires)
        assertEquals("My car's tire sensor, wheel 2", reloaded.device("81:EA:CA:00:00:02")!!.label)
        assertFalse(reloaded.device(a)!!.tire)

        // One taken back alone; the other stays the owner's.
        val one = reloaded.untrust("80:EA:CA:00:00:01")
        assertNull(one.device("80:EA:CA:00:00:01"))
        assertTrue(one.hasTires)
        assertFalse(one.untrust("81:EA:CA:00:00:02").hasTires)
        assertFalse(TrustBook.EMPTY.hasTires)
    }
}
