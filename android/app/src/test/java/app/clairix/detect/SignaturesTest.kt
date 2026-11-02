package app.clairix.detect

import app.clairix.Threat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every family the signature table recognises, from the advertisement as the scanner
 * reads it, and the frames that must not be mistaken for one.
 */
class SignaturesTest {

    /** Apple manufacturer data for a Find My frame: type 0x12, [length], status byte with [kind] in bits 4–5. */
    private fun findMy(length: Int, kind: Int): ByteArray =
        ByteArray(2 + length).also { it[0] = 0x12; it[1] = length.toByte(); it[2] = (kind shl 4).toByte() }

    /** An iBeacon: type 0x02, length 0x15, then UUID, major, minor and power. */
    private val iBeacon = ByteArray(23).also { it[0] = 0x02; it[1] = 0x15; it[22] = 0xC5.toByte() }

    private fun shorts(vararg uuid: Int) = Advert(shorts = uuid.toSet())

    @Test
    fun findMyFramesByKindAndOwnerState() {
        assertSame(Signatures.AIRTAG, Signatures.match(Advert(apple = findMy(0x19, 1))))
        assertSame(Signatures.AIRTAG_NEAR_OWNER, Signatures.match(Advert(apple = findMy(0x02, 1))))
        assertSame(Signatures.FINDMY, Signatures.match(Advert(apple = findMy(0x19, 2))))
        assertSame(Signatures.FINDMY_NEAR_OWNER, Signatures.match(Advert(apple = findMy(0x02, 2))))
        assertSame(Signatures.AIRPODS_FINDMY, Signatures.match(Advert(apple = findMy(0x19, 3))))
        // An offline iPhone, iPad or Mac: findable, but a phone, not a tag.
        assertNull(Signatures.match(Advert(apple = findMy(0x19, 0))))
        // Too short to carry a status byte: a Find My item of unknown kind.
        assertSame(Signatures.FINDMY, Signatures.match(Advert(apple = byteArrayOf(0x12, 0x19))))
    }

    /**
     * A third-party Find My item's frame is a status byte, a rotating public key and a hint:
     * nothing in it names the maker. Whatever fills the key bytes and the low status bits, a
     * Chipolo Spot and any other third-party item read as the same Find My item, and Chipolo's
     * own service UUIDs are what name a Chipolo.
     */
    @Test
    fun aThirdPartyFindMyItemIsNotTakenForAnyOneMaker() {
        for (seed in 0 until 16) {
            val frame = findMy(0x19, 2).also { f ->
                f[2] = (f[2].toInt() or (seed and 0x0F) or ((seed and 0x03) shl 6)).toByte()
                for (i in 3 until f.size) f[i] = (seed * 31 + i * 7).toByte()
            }
            assertSame(Signatures.FINDMY, Signatures.match(Advert(apple = frame)))
        }
        assertSame(Signatures.CHIPOLO, Signatures.match(shorts(0xFE33)))
        assertSame(Signatures.CHIPOLO, Signatures.match(shorts(0xFE65)))
    }

    @Test
    fun theSelfTestBeaconIsWhatTheClassifierCallsASeparatedAirTag() {
        val payload = BleCanary.buildPayload()
        assertSame(Signatures.AIRTAG, Signatures.match(Advert(apple = payload)))
        assertFalse(Signatures.isBenign(Advert(apple = payload)))
    }

    @Test
    fun anIBeaconIsClassifiedNotDiscarded() {
        val advert = Advert(apple = iBeacon)
        assertFalse("an iBeacon must reach the follow check", Signatures.isBenign(advert))
        assertSame(Signatures.IBEACON, Signatures.match(advert))
        assertEquals(Threat.MEDIUM, Signatures.IBEACON.threat)
        // Type 0x02 without the iBeacon length is not one.
        assertNull(Signatures.match(Advert(apple = byteArrayOf(0x02, 0x03, 0x01, 0x02, 0x03))))
    }

    @Test
    fun appleAccessoriesAndMicrosoftDevicesAreBenign() {
        for (type in listOf(0x01, 0x07, 0x05, 0x09, 0x0B, 0x0D, 0x0E, 0x10)) {
            assertTrue("Apple type $type", Signatures.isBenign(Advert(apple = byteArrayOf(type.toByte(), 0x10, 0x00))))
        }
        assertTrue(Signatures.isBenign(Advert(microsoft = true)))
        assertFalse(Signatures.isBenign(Advert(apple = findMy(0x19, 1))))
        assertFalse(Signatures.isBenign(Advert()))
    }

    @Test
    fun dultSeparatedAnnouncementIsATrackerAndNearOwnerIsNot() {
        assertSame(Signatures.DULT_TRACKER, Signatures.match(Advert(dult = byteArrayOf(0x01, 0x00))))
        assertNull(Signatures.match(Advert(dult = byteArrayOf(0x01, 0x01))))
    }

    @Test
    fun everyServiceUuidFamily() {
        for (tile in listOf(0xFEED, 0xFEEC, 0xFD84)) assertSame(Signatures.TILE, Signatures.match(shorts(tile)))
        assertSame(Signatures.SMARTTAG, Signatures.match(shorts(0xFD5A)))
        assertSame(Signatures.CHIPOLO, Signatures.match(shorts(0xFE33)))
        assertSame(Signatures.CHIPOLO, Signatures.match(shorts(0xFE65)))
        assertSame(Signatures.PEBBLEBEE, Signatures.match(shorts(0xFA25)))
        assertSame(Signatures.ORBIT, Signatures.match(shorts(0xFFF3, 0xFFE0)))
        assertSame(Signatures.NUT, Signatures.match(shorts(0xAA01)))
        assertSame(Signatures.NUT, Signatures.match(shorts(0xAA02)))
        assertSame(Signatures.INVOXIA, Signatures.match(shorts(0xFE85)))
        // Bose and an unallocated id are not Chipolo; one generic HM-10 UUID is not Orbit.
        assertNull(Signatures.match(shorts(0xFEBE)))
        assertNull(Signatures.match(shorts(0xFE2B)))
        assertNull(Signatures.match(shorts(0xFFE0)))
    }

    @Test
    fun findHubFrameTypesTellTheOwnersPresence() {
        val ordinary = Advert(google = byteArrayOf(0x40, 0x11), flags = 0x06)
        val separated = Advert(google = byteArrayOf(0x41, 0x11), flags = 0x06)
        assertSame(Signatures.GOOGLE_FIND_HUB, Signatures.match(ordinary))
        assertSame(Signatures.GOOGLE_FIND_HUB_SEPARATED, Signatures.match(separated))
        // The separated frame outranks the ordinary one, so an entry is upgraded when it flips.
        assertTrue(Signatures.GOOGLE_FIND_HUB_SEPARATED.threat.ordinal > Signatures.GOOGLE_FIND_HUB.threat.ordinal)
        // A switched-off Android phone sends the same frames with BR/EDR flags: not a tag.
        assertNull(Signatures.match(Advert(google = byteArrayOf(0x40), flags = 0x02)))
        assertNull(Signatures.match(Advert(google = byteArrayOf(0x41), flags = 0x02)))
    }

    @Test
    fun eddystoneFramesAreBeacons() {
        for (frame in listOf(0x00, 0x10, 0x20, 0x30)) {
            assertSame("frame $frame", Signatures.EDDYSTONE, Signatures.match(Advert(google = byteArrayOf(frame.toByte()))))
        }
        assertNull(Signatures.match(Advert(google = byteArrayOf(0x50))))
    }

    @Test
    fun gpsModulesByName() {
        assertSame(Signatures.GPS_BLE, Signatures.match(Advert(name = "TK102-GPS")))
        assertSame(Signatures.GPS_BLE, Signatures.match(Advert(name = "Bouncie")))
        assertNull(Signatures.match(Advert(name = "Living Room TV")))
        assertNull(Signatures.match(Advert()))
    }

    @Test
    fun ownerNearbyReadingsAreNotTrackerVerdictsAndRotatingTypesAreKnown() {
        assertTrue(Signatures.withOwner(Signatures.AIRTAG_NEAR_OWNER))
        assertTrue(Signatures.withOwner(Signatures.FINDMY_NEAR_OWNER))
        assertTrue(Signatures.withOwner(Signatures.GOOGLE_FIND_HUB))
        assertFalse(Signatures.withOwner(Signatures.GOOGLE_FIND_HUB_SEPARATED))
        assertFalse(Signatures.withOwner(Signatures.AIRTAG))
        assertFalse(Signatures.withOwner(null))
        assertTrue(Signatures.rotates(Signatures.AIRTAG))
        assertTrue(Signatures.rotates(Signatures.GOOGLE_FIND_HUB_SEPARATED))
        assertFalse(Signatures.rotates(Signatures.GPS_BLE))
        assertFalse(Signatures.rotates(Signatures.IBEACON))
    }

    @Test
    fun theDultTriggerAsksSeparatedReadingsOnly() {
        assertTrue("google_find_hub_separated" in DultInterrogator.SUPPORTED)
        assertFalse(Signatures.GOOGLE_FIND_HUB.id in DultInterrogator.SUPPORTED)
        assertFalse(Signatures.AIRTAG_NEAR_OWNER.id in DultInterrogator.SUPPORTED)
        assertTrue(Signatures.AIRTAG.id in DultInterrogator.SUPPORTED)
    }
}
