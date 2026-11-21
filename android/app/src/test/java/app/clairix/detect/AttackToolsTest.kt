package app.clairix.detect

import app.clairix.detect.AttackTools.Confidence
import app.clairix.detect.AttackTools.Kind
import app.clairix.detect.AttackTools.PairingFlood
import app.clairix.detect.AttackTools.Prompt
import app.clairix.detect.AttackTools.PromptFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Flipper Zero's Bluetooth fingerprint as its firmware sends it, the pairing
 * frames as the spam tools build them, and the things that look like either and are not.
 */
class AttackToolsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ── Flipper Zero ─────────────────────────────────────────────────────────────

    @Test
    fun flipperServiceWithItsNameOrAddressIsCertain() {
        val named = AttackTools.matchBle("Flipper Ucaru", listOf(0x3082), "AA:BB:CC:00:11:22")
        assertEquals(Kind.FLIPPER_ZERO, named!!.kind)
        assertEquals(Confidence.CERTAIN, named.confidence)
        assertTrue(named.evidence.toString(), named.evidence.any { it.contains("0x3082") && it.contains("white") })

        val addressed = AttackTools.matchBle(null, listOf(0x3081), "80:E1:26:12:34:56")
        assertEquals(Confidence.CERTAIN, addressed!!.confidence)
        assertTrue(addressed.evidence.any { it.contains("black") })
        assertTrue(addressed.evidence.any { it.contains("80:E1:26") })
    }

    @Test
    fun everyCaseColourIsRecognised() {
        for (service in 0x3080..0x3083) {
            assertEquals("service %04X".format(service), Kind.FLIPPER_ZERO, AttackTools.matchBle("Flipper Abc", listOf(service), null)!!.kind)
        }
        assertNull(AttackTools.matchBle(null, listOf(0x3084), null))
        assertNull(AttackTools.matchBle(null, listOf(0x307F), null))
    }

    @Test
    fun flipperServiceAloneIsOnlyLikely() {
        // Custom firmware renames the device and changes its address; the service is what is left.
        val hit = AttackTools.matchBle("Totally a speaker", listOf(0x180F, 0x3083), "C4:11:22:33:44:55")
        assertEquals(Kind.FLIPPER_ZERO, hit!!.kind)
        assertEquals(Confidence.LIKELY, hit.confidence)
    }

    @Test
    fun nameAndAddressWithoutTheServiceIsLikely() {
        assertEquals(Confidence.LIKELY, AttackTools.matchBle("Flipper Ucaru", emptyList(), "80:e1:26:aa:bb:cc")!!.confidence)
        // Flipper Devices' own registered block is theirs whatever the product calls itself.
        val own = AttackTools.matchBle(null, emptyList(), "0C:FA:22:01:02:03")
        assertEquals(Kind.FLIPPER_ZERO, own!!.kind)
        assertEquals(Confidence.LIKELY, own.confidence)
    }

    @Test
    fun aNameAloneIsNeverShown() {
        // Anybody can call a speaker "Flipper Sound"; POSSIBLE is dropped before any alert.
        assertEquals(Confidence.POSSIBLE, AttackTools.matchBle("Flipper Sound", emptyList(), "11:22:33:44:55:66")!!.confidence)
    }

    @Test
    fun lookalikesAreNotFlippers() {
        // The bare word, with nothing after it.
        assertNull(AttackTools.matchBle("Flipper", emptyList(), null))
        assertNull(AttackTools.matchBle("Flipper ", emptyList(), null))
        // The radio chip's prefix alone is every product built on that chip.
        assertNull(AttackTools.matchBle("Heart Rate Strap", listOf(0x180D), "80:E1:26:00:00:01"))
        // An ordinary device.
        assertNull(AttackTools.matchBle("JBL Flip 6", listOf(0xFE2C), "A0:B1:C2:D3:E4:F5"))
        assertNull(AttackTools.matchBle(null, emptyList(), null))
    }

    @Test
    fun keyboardModeNeedsAllThreeSigns() {
        val hit = AttackTools.matchBle("Control Ucaru", listOf(0x1812), "80:E1:26:12:34:56")
        assertEquals(Kind.FLIPPER_KEYBOARD, hit!!.kind)
        assertEquals(Confidence.LIKELY, hit.confidence)
        // The keystroke-script application's name for the same profile.
        assertEquals(Kind.FLIPPER_KEYBOARD, AttackTools.matchBle("BadUSB Ucaru", listOf(0x1812), "80:E1:26:13:34:54")!!.kind)
        assertNull(AttackTools.matchBle("BadUSB ", listOf(0x1812), "80:E1:26:13:34:54"))
        assertNull(AttackTools.matchBle("BadUSB Ucaru", listOf(0x1812), "DC:2C:26:00:00:01"))
        // A real keyboard, a real remote called "Control ...", and the chip without the name.
        assertNull(AttackTools.matchBle("Keychron K2", listOf(0x1812), "DC:2C:26:00:00:01"))
        assertNull(AttackTools.matchBle("Control Center", listOf(0x1812), "DC:2C:26:00:00:01"))
        assertNull(AttackTools.matchBle("MX Keys", listOf(0x1812), "80:E1:26:12:34:56"))
        assertNull(AttackTools.matchBle("Control Ucaru", emptyList(), "80:E1:26:12:34:56"))
    }

    // ── Pairing frames, built the way the spam tools build them ───────────────────

    /** Apple proximity pairing: type 0x07, 25 bytes, prefix, model, status 0x55, battery, lid, colour, 0, 16 bytes. */
    private fun appleAccessory(model: Int, prefix: Int = 0x01): ByteArray =
        bytes(0x07, 0x19, prefix, model shr 8, model and 0xFF, 0x55, 0x93, 0x52, 0x1C, 0x00, 0x00) + ByteArray(16) { (it * 7).toByte() }

    /** Apple nearby action: type 0x0F, 5 bytes: flags, action, three-byte tag. */
    private fun appleAction(action: Int): ByteArray = bytes(0x0F, 0x05, 0xC0, action, 0x11, 0x22, 0x33)

    @Test
    fun applePairingAndActionFramesAreRead() {
        assertEquals(Prompt(PromptFamily.APPLE_PAIRING, 0x0E20), AttackTools.promptOf(Advert(apple = appleAccessory(0x0E20))))
        assertEquals(Prompt(PromptFamily.APPLE_PAIRING, 0x0220), AttackTools.promptOf(Advert(apple = appleAccessory(0x0220, prefix = 0x07))))
        assertEquals(Prompt(PromptFamily.APPLE_ACTION, 0x13), AttackTools.promptOf(Advert(apple = appleAction(0x13))))
    }

    @Test
    fun otherAppleFramesAreNotPairingRequests() {
        // Find My (an AirTag), nearby info (every iPhone), handoff, iBeacon.
        assertNull(AttackTools.promptOf(Advert(apple = bytes(0x12, 0x19, 0x10) + ByteArray(24))))
        assertNull(AttackTools.promptOf(Advert(apple = bytes(0x10, 0x05, 0x01, 0x18, 0x44, 0x55, 0x66))))
        assertNull(AttackTools.promptOf(Advert(apple = bytes(0x0C, 0x0E, 0x00) + ByteArray(13))))
        assertNull(AttackTools.promptOf(Advert(apple = bytes(0x02, 0x15) + ByteArray(21))))
        // Type 0x07 with another length is not the 25-byte pairing frame.
        assertNull(AttackTools.promptOf(Advert(apple = bytes(0x07, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05))))
        assertNull(AttackTools.promptOf(Advert(apple = ByteArray(0))))
        assertNull(AttackTools.promptOf(Advert()))
    }

    @Test
    fun fastPairIsAPairingRequestOnlyInPairingMode() {
        assertEquals(Prompt(PromptFamily.FAST_PAIR, 0xCD8256), AttackTools.promptOf(Advert(fastPair = bytes(0xCD, 0x82, 0x56))))
        // A device that is already paired sends a longer frame with its account key filter.
        assertNull(AttackTools.promptOf(Advert(fastPair = bytes(0x00, 0x40, 0x11, 0x22, 0x33, 0x44, 0x11, 0x55))))
        assertNull(AttackTools.promptOf(Advert(fastPair = ByteArray(0))))
    }

    @Test
    fun swiftPairBeaconIsReadAndOtherMicrosoftBeaconsAreNot() {
        val swift = bytes(0x03, 0x00, 0x80) + "Headset".toByteArray(Charsets.US_ASCII)
        val prompt = AttackTools.promptOf(Advert(microsoft = true, manufacturer = mapOf(0x0006 to swift)))
        assertEquals(PromptFamily.SWIFT_PAIR, prompt!!.family)
        // A different name is a different "product".
        val other = AttackTools.promptOf(Advert(manufacturer = mapOf(0x0006 to bytes(0x03, 0x00, 0x80) + "Mouse".toByteArray(Charsets.US_ASCII))))
        assertTrue(prompt.model != other!!.model)
        // The device-presence beacon every Windows PC sends (0x01) is not Swift Pair.
        assertNull(AttackTools.promptOf(Advert(microsoft = true, manufacturer = mapOf(0x0006 to bytes(0x01, 0x09, 0x20, 0x02, 0x11, 0x22)))))
        assertNull(AttackTools.promptOf(Advert(microsoft = true, manufacturer = mapOf(0x0006 to bytes(0x03, 0x00)))))
    }

    @Test
    fun samsungSetupFramesAreRead() {
        val buds = bytes(0x42, 0x09, 0x81, 0x02, 0x14, 0x15, 0x03, 0x21, 0x01, 0x09, 0xEE, 0x7A, 0x01, 0x0C, 0x06, 0x3C, 0x94, 0x8E, 0, 0, 0, 0, 0xC7, 0)
        assertEquals(Prompt(PromptFamily.SAMSUNG_SETUP, 0xEE7A0C), AttackTools.promptOf(Advert(samsung = buds)))
        val watch = bytes(0x01, 0x00, 0x02, 0x00, 0x01, 0x01, 0xFF, 0x00, 0x00, 0x43, 0x1A)
        assertEquals(Prompt(PromptFamily.SAMSUNG_SETUP, 0x1A), AttackTools.promptOf(Advert(samsung = watch)))
        // Some other Samsung payload (a phone, a SmartTag's own frame).
        assertNull(AttackTools.promptOf(Advert(samsung = bytes(0x02, 0x00, 0x01, 0x10, 0x00, 0x00, 0x00, 0x63, 0x50, 0x8D, 0xB1))))
    }

    @Test
    fun theFirstSamsungBlockIsTakenFromTheRawBytes() {
        // The earbud frame as the spam tool sends it: the real block, then a second one that is cut short.
        val first = bytes(0x42, 0x09, 0x81, 0x02, 0x14, 0x15, 0x03, 0x21, 0x01, 0x09, 0xEE, 0x7A, 0x01, 0x0C, 0x06, 0x3C, 0x94, 0x8E, 0, 0, 0, 0, 0xC7, 0)
        val raw = bytes(27, 0xFF, 0x75, 0x00) + first + bytes(16, 0xFF, 0x75)
        assertArrayEquals(first, Advert.firstManufacturerBlock(raw, 0x0075))
        // Flags and a name first, then Apple, then Samsung.
        val mixed = bytes(2, 0x01, 0x06, 4, 0x09, 0x41, 0x42, 0x43, 4, 0xFF, 0x4C, 0x00, 0x10, 5, 0xFF, 0x75, 0x00, 0xAA, 0xBB)
        assertArrayEquals(bytes(0xAA, 0xBB), Advert.firstManufacturerBlock(mixed, 0x0075))
        assertArrayEquals(bytes(0x10), Advert.firstManufacturerBlock(mixed, 0x004C))
        // None, empty, padded with zeros after the last structure, and nonsense.
        assertNull(Advert.firstManufacturerBlock(bytes(2, 0x01, 0x06), 0x0075))
        assertNull(Advert.firstManufacturerBlock(ByteArray(0), 0x0075))
        assertNull(Advert.firstManufacturerBlock(null, 0x0075))
        assertNull(Advert.firstManufacturerBlock(bytes(2, 0x01, 0x06, 0, 0, 0, 0), 0x0075))
        assertNull(Advert.firstManufacturerBlock(bytes(0xFF), 0x0075))
        // A block that claims more bytes than there are is read as far as it goes.
        assertArrayEquals(bytes(0x42), Advert.firstManufacturerBlock(bytes(20, 0xFF, 0x75, 0x00, 0x42), 0x0075))
    }

    // ── The flood ────────────────────────────────────────────────────────────────

    private val t0 = 1_700_000_000_000L
    private fun addr(i: Int) = "%02X:%02X:%02X:%02X:%02X:%02X".format(0x40 + (i % 60), i shr 8 and 0xFF, i and 0xFF, 0x11, 0x22, 0x33)
    private val pair = Prompt(PromptFamily.APPLE_PAIRING, 0x0E20)

    /** Feeds [count] one-off addresses, [everyMs] apart, and returns every finding in order. */
    private fun flood(detector: PairingFlood, count: Int, everyMs: Long, rssi: (Int) -> Int = { -60 + (it % 5) }): List<AttackTools.Hit> {
        val hits = ArrayList<AttackTools.Hit>()
        for (i in 0 until count) {
            detector.observe(Prompt(PromptFamily.FAST_PAIR, 0x100 + i), addr(i), rssi(i), t0 + i * everyMs)?.let(hits::add)
        }
        return hits
    }

    @Test
    fun aStreamOfOneOffAddressesFromOnePlaceIsAFlood() {
        // The tool at its slowest setting: one packet every half second, for 25 seconds.
        val hits = flood(PairingFlood(), count = 50, everyMs = 500)
        assertTrue("no finding", hits.isNotEmpty())
        assertEquals(Kind.PAIRING_FLOOD, hits.first().kind)
        assertEquals(Confidence.LIKELY, hits.first().confidence)
        assertTrue(hits.first().evidence.toString(), hits.first().evidence.any { it.contains("heard only once") })
        assertTrue(hits.first().evidence.any { it.contains("Android Fast Pair") })
    }

    @Test
    fun aFastFloodBecomesCertain() {
        // Twenty packets a second for ten seconds.
        val hits = flood(PairingFlood(), count = 200, everyMs = 50)
        assertEquals(Confidence.CERTAIN, hits.last().confidence)
    }

    @Test
    fun nothingIsSaidBeforeEnoughHaveHadTimeToSpeakTwice() {
        // Fourteen one-off addresses is one short, however long they are given.
        val detector = PairingFlood()
        val hits = flood(detector, count = 14, everyMs = 200)
        assertTrue(hits.toString(), hits.isEmpty())
        assertNull(detector.observe(pair, "AA:AA:AA:AA:AA:AA", -60, t0 + 20_000))
    }

    @Test
    fun aRoomFullOfHeadphonesIsNotAFlood() {
        // Forty real devices: each keeps its address and is heard once a second for half a minute.
        val detector = PairingFlood()
        for (second in 0 until 30) {
            for (device in 0 until 40) {
                val hit = detector.observe(Prompt(PromptFamily.APPLE_PAIRING, 0x0E20 + device % 3), addr(device), -50 - device, t0 + second * 1_000L + device)
                assertNull("second $second device $device", hit)
            }
        }
    }

    @Test
    fun walkingThroughACrowdIsNotAFlood() {
        // Sixty devices each heard exactly once, as when passing them, but from near and far.
        val hits = flood(PairingFlood(), count = 60, everyMs = 400, rssi = { -45 - (it * 7) % 50 })
        assertTrue(hits.toString(), hits.isEmpty())
    }

    @Test
    fun aFewOneOffsInABusyRoomAreNotAFlood() {
        // Forty real accessories repeating, and twenty addresses each heard once among
        // them at the same strength: passers-by, not a flood. Twenty would be enough
        // on their own; here they are a third of what is heard, not four fifths.
        val detector = PairingFlood()
        for (tick in 0 until 100) {
            val now = t0 + tick * 250L
            for (device in 0 until 40) {
                assertNull(detector.observe(pair, addr(1_000 + device), -60, now + device))
            }
            if (tick % 5 == 0) {
                assertNull("tick $tick", detector.observe(Prompt(PromptFamily.FAST_PAIR, tick), addr(tick), -60, now + 50))
            }
        }
    }

    @Test
    fun faintAccessoriesAtTheEdgeOfRangeAreNotAFlood() {
        // Sixty devices each caught once and all barely audible: a big room's far side,
        // not one transmitter nearby. Their strengths agree, so only the floor rules it out.
        val hits = flood(PairingFlood(), count = 60, everyMs = 400, rssi = { -96 + (it % 6) })
        assertTrue(hits.toString(), hits.isEmpty())
        // The same traffic from close by is a flood.
        assertTrue(flood(PairingFlood(), count = 60, everyMs = 400, rssi = { -84 + (it % 6) }).isNotEmpty())
    }

    @Test
    fun aFewRealDevicesAmongTheFakesDoNotHideTheFlood() {
        val detector = PairingFlood()
        val hits = ArrayList<AttackTools.Hit>()
        for (i in 0 until 120) {
            val now = t0 + i * 100L
            detector.observe(Prompt(PromptFamily.SWIFT_PAIR, i), addr(i), -58, now)?.let(hits::add)
            // Three real accessories repeating throughout.
            detector.observe(pair, "DE:V1:CE:00:00:0${i % 3}", -70, now + 1)?.let(hits::add)
        }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.kind == Kind.PAIRING_FLOOD })
    }

    @Test
    fun oneAddressClaimingToBeManyProductsIsATool() {
        // The attacks that keep one address step through product after product.
        val detector = PairingFlood()
        val address = "5A:11:22:33:44:55"
        assertNull(detector.observe(Prompt(PromptFamily.FAST_PAIR, 1), address, -60, t0))
        assertNull(detector.observe(Prompt(PromptFamily.FAST_PAIR, 2), address, -60, t0 + 100))
        assertNull(detector.observe(Prompt(PromptFamily.FAST_PAIR, 3), address, -60, t0 + 200))
        val fourth = detector.observe(Prompt(PromptFamily.FAST_PAIR, 4), address, -60, t0 + 300)
        assertEquals(Kind.PAIRING_FLOOD, fourth!!.kind)
        assertEquals(Confidence.LIKELY, fourth.confidence)
        detector.observe(Prompt(PromptFamily.FAST_PAIR, 5), address, -60, t0 + 400)
        val sixth = detector.observe(Prompt(PromptFamily.FAST_PAIR, 6), address, -60, t0 + 500)
        assertEquals(Confidence.CERTAIN, sixth!!.confidence)
        assertTrue(sixth.evidence.toString(), sixth.evidence.any { it.contains("6 different products") })
    }

    @Test
    fun oneAccessoryRepeatingItselfIsNotATool() {
        val detector = PairingFlood()
        for (i in 0 until 500) {
            assertNull(detector.observe(pair, "5A:11:22:33:44:55", -60, t0 + i * 100L))
        }
    }

    @Test
    fun aFloodThatHasStoppedIsForgotten() {
        val detector = PairingFlood()
        assertNotNull(flood(detector, count = 100, everyMs = 100).lastOrNull())
        // Half a minute of silence, then one ordinary accessory.
        val later = t0 + 100 * 100L + PairingFlood.WINDOW_MS + 6_000
        assertNull(detector.observe(pair, "AA:AA:AA:AA:AA:AA", -60, later))
        assertNull(detector.observe(pair, "AA:AA:AA:AA:AA:AA", -60, later + 2_000))
    }

    @Test
    fun resetStartsClean() {
        val detector = PairingFlood()
        assertNotNull(flood(detector, count = 100, everyMs = 100).lastOrNull())
        detector.reset()
        assertNull(detector.observe(pair, "AA:AA:AA:AA:AA:AA", -60, t0 + 12_000))
        assertNull(detector.observe(pair, "BB:BB:BB:BB:BB:BB", -60, t0 + 14_000))
    }
}
