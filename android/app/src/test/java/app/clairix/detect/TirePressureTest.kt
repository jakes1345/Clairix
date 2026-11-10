package app.clairix.detect

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.Threat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bluetooth tire pressure sensors, from the packets their decoders published, and the
 * advertisements that share a company id or a name with them and must not match.
 */
class TirePressureTest {

    private fun bytes(hex: String): ByteArray =
        hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * The valve-cap kit's packet as the ra6070/BLE-TPMS project published it, on air
     * "0001 80EACA108A78 E36D0000 E60A0000 5B 00". Android hands over what follows the
     * two-byte company id, which it reads little-endian as 0x0100.
     */
    private val valveCap = bytes("80EACA108A78E36D0000E60A00005B00")

    private fun valveCapAdvert(payload: ByteArray = valveCap, name: String? = "TPMS1_108A78") =
        Advert(manufacturer = mapOf(TirePressure.VALVE_CAP_COMPANY to payload), name = name)

    @Test
    fun theValveCapKitIsReadAsPublished() {
        val r = TirePressure.read(valveCapAdvert())!!
        assertEquals(TirePressure.Format.VALVE_CAP, r.format)
        assertEquals("80:EA:CA:10:8A:78", r.sensorId)
        assertEquals(1, r.wheel)
        // 0x00006DE3 Pa = 28131 Pa: 28.131 kPa, the sensor on a bench.
        assertEquals(28.131, r.pressureKpa!!, 1e-9)
        assertFalse(r.pressureApproximate)
        assertEquals(27.90, r.temperatureC!!, 1e-9)
        assertEquals(91, r.batteryPercent)
        assertFalse(r.noPressure)
        assertEquals("Wheel 1 · 0.3 bar (4 psi) · 28 °C · battery 91 %", r.summary())
    }

    @Test
    fun theWheelComesFromTheFirstByteAndTheAlarmFromTheLast() {
        val fourth = valveCap.copyOf().also { it[0] = 0x83.toByte(); it[15] = 0x01 }
        val r = TirePressure.read(valveCapAdvert(fourth, name = null))!!
        assertEquals(4, r.wheel)
        assertEquals("83:EA:CA:10:8A:78", r.sensorId)
        assertTrue(r.noPressure)
        assertTrue(r.summary(), r.summary().startsWith("Wheel 4 · no pressure"))
    }

    @Test
    fun aRoadTiresPressureReadsInBarAndPsi() {
        // 230 000 Pa = 230 kPa = 2.3 bar = 33 psi.
        val pumped = valveCap.copyOf().also {
            val pa = 230_000
            for (i in 0..3) it[6 + i] = (pa shr (8 * i)).toByte()
        }
        val r = TirePressure.read(valveCapAdvert(pumped))!!
        assertEquals(230.0, r.pressureKpa!!, 1e-9)
        assertTrue(r.summary(), "2.3 bar (33 psi)" in r.summary())
    }

    @Test
    fun anythingElseUnderTomTomsCompanyIdIsNotATireSensor() {
        // 0x0100 is TomTom's id in the Bluetooth SIG list: only the kit's full shape counts.
        assertNull(TirePressure.read(valveCapAdvert(valveCap.copyOf(15))))
        assertNull(TirePressure.read(valveCapAdvert(valveCap.copyOf() + 0x00)))
        assertNull(TirePressure.read(valveCapAdvert(valveCap.copyOf().also { it[1] = 0x00 })))
        assertNull(TirePressure.read(valveCapAdvert(valveCap.copyOf().also { it[0] = 0x10 })))
        assertNull(TirePressure.read(Advert(manufacturer = mapOf(0x004C to valveCap))))
    }

    @Test
    fun aPressureNoTireHasIsLeftOutRatherThanShown() {
        val absurd = valveCap.copyOf().also { for (i in 6..9) it[i] = 0x7F }
        val r = TirePressure.read(valveCapAdvert(absurd))!!
        assertNull(r.pressureKpa)
    }

    /**
     * The "BR" sensor's packets from the Arduino forum thread: name "BR", service 0x27A5,
     * then seven bytes of manufacturer data that Android splits into a company id (the
     * first two bytes, little-endian) and the rest. The sensor's app showed 1.3 bar /
     * 17 °C / 3.0 V and 2.5 bar / 21 °C / 3.0 V for these two.
     */
    private fun brAdvert(raw: String, name: String? = "BR", service: Boolean = true): Advert {
        val b = bytes(raw)
        val company = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        return Advert(
            shorts = if (service) setOf(TirePressure.BR_SERVICE) else emptySet(),
            name = name,
            manufacturer = mapOf(company to b.copyOfRange(2, b.size))
        )
    }

    @Test
    fun theBrSensorMatchesWhatItsAppShowed() {
        val low = TirePressure.read(brAdvert("281E11014D8511"), "C1:22:33:44:55:66")!!
        assertEquals(TirePressure.Format.BR, low.format)
        assertEquals("C1:22:33:44:55:66", low.sensorId)
        assertEquals(17.0, low.temperatureC!!, 1e-9)
        assertEquals(3.0, low.batteryVolts!!, 1e-9)
        assertEquals(130.0, low.pressureKpa!!, 5.0)
        assertTrue(low.pressureApproximate)
        assertTrue(low.summary(), low.summary().startsWith("about 1.3 bar"))

        val high = TirePressure.read(brAdvert("401E1501FA0FF8"), "C1:22:33:44:55:66")!!
        assertEquals(21.0, high.temperatureC!!, 1e-9)
        assertEquals(250.0, high.pressureKpa!!, 5.0)
    }

    @Test
    fun theBrSensorNeedsBothItsNameAndItsService() {
        assertNull(TirePressure.read(brAdvert("281E11014D8511", name = "BRAVIA")))
        assertNull(TirePressure.read(brAdvert("281E11014D8511", service = false)))
        assertNull(TirePressure.read(brAdvert("281E11014D8511", name = null)))
    }

    @Test
    fun theDjtpmsSensorIsReadAsItsIntegrationPublishedIt() {
        // "00 00 1F 27 01 54 00 06 0C 3D 5E 4E 8B E6": 3.1 V, 39 °C, 340 kPa absolute.
        val raw = bytes("00001F27015400060C3D5E4E8BE6")
        val advert = Advert(name = "DJTPMS", manufacturer = mapOf(0x0000 to raw.copyOfRange(2, raw.size)))
        val r = TirePressure.read(advert, "D0:11:22:33:44:55")!!
        assertEquals(TirePressure.Format.DJTPMS, r.format)
        assertEquals("D0:11:22:33:44:55", r.sensorId)
        assertEquals(3.1, r.batteryVolts!!, 1e-9)
        assertEquals(39.0, r.temperatureC!!, 1e-9)
        assertEquals(340.0 - 101.325, r.pressureKpa!!, 1e-9)
    }

    @Test
    fun theSignatureTableCallsEveryFormatATireSensor() {
        assertSame(Signatures.TIRE_SENSOR, Signatures.match(valveCapAdvert()))
        assertSame(Signatures.TIRE_SENSOR, Signatures.match(brAdvert("281E11014D8511")))
        assertSame(Signatures.TIRE_SENSOR, Signatures.match(Advert(name = "DJTPMS")))
        assertNull(Signatures.match(valveCapAdvert(valveCap.copyOf(15), name = null)))
        // A fixed ID for life: marking one as yours holds for good, not for a day.
        assertFalse(Signatures.rotates(Signatures.TIRE_SENSOR))
        assertFalse(Signatures.withOwner(Signatures.TIRE_SENSOR))
        assertEquals(Threat.MEDIUM, Signatures.TIRE_SENSOR.threat)
    }

    // ── One vehicle's set ─────────────────────────────────────────────────

    private val t0 = 1_700_000_000_000L

    private fun sensor(
        address: String, wheel: Int?, firstSeen: Long, lastSeen: Long = firstSeen + 20 * 60_000L,
        format: TirePressure.Format = TirePressure.Format.VALVE_CAP
    ) = Detection(
        key = "k-$address", address = address, name = "TPMS$wheel", rssi = -70,
        tracker = Signatures.TIRE_SENSOR, threat = Threat.MEDIUM, score = 44,
        firstSeen = firstSeen, lastSeen = lastSeen, sightings = 20,
        persistent = true, following = true, confidence = FollowConfidence.CONFIRMED,
        displacementM = 4000.0, places = 6, rotations = 0, addresses = 1, approxMetres = null,
        tire = TirePressure.Reading(format, address, wheel = wheel)
    )

    @Test
    fun aCarsOwnFourAreOneSetAndACarAlongsideIsNot() {
        val front = sensor("80:EA:CA:00:00:01", 1, t0)
        val own = listOf(
            front,
            sensor("81:EA:CA:00:00:02", 2, t0 + 20_000L),
            sensor("82:EA:CA:00:00:03", 3, t0 + 45_000L),
            sensor("83:EA:CA:00:00:04", 4, t0 + 90_000L)
        )
        // Another car with the same kit, met half an hour into the drive.
        val alongside = sensor("80:EA:CA:00:00:99", 1, t0 + 30 * 60_000L, t0 + 32 * 60_000L)
        // A second unit claiming wheel 2 at the same moment: a set has one of each.
        val duplicateWheel = sensor("81:EA:CA:00:00:77", 2, t0 + 5 * 60_000L)
        // A non-tire device and a tire sensor of another format.
        val phone = sensor("AA:BB:CC:00:00:01", null, t0).copy(tracker = null, tire = null)
        val br = sensor("C1:00:00:00:00:01", null, t0, format = TirePressure.Format.BR)

        val set = TirePressure.sameVehicle(front, own + alongside + duplicateWheel + phone + br)
        assertEquals(own.map { it.address }, set.map { it.address })
    }

    @Test
    fun sensorsAlreadyMarkedAreLeftOutAndTheSetIsCapped() {
        val first = sensor("80:EA:CA:00:00:01", null, t0)
        val many = (2..10).map { sensor("80:EA:CA:00:00:%02X".format(it), null, t0 + it * 1000L) }
        val set = TirePressure.sameVehicle(first, listOf(first) + many, trusted = setOf(many[0].address))
        assertEquals(TirePressure.MAX_SET, set.size)
        assertFalse(set.any { it.address == many[0].address })
        assertEquals(first.address, set.first().address)
    }

    @Test
    fun anythingThatIsNotATireSensorHasNoSet() {
        val phone = sensor("AA:BB:CC:00:00:01", null, t0).copy(tracker = null, tire = null)
        assertTrue(TirePressure.sameVehicle(phone, listOf(phone)).isEmpty())
        assertNotNull(TirePressure.sameVehicle(sensor("80:EA:CA:00:00:01", 1, t0), emptyList()).singleOrNull())
    }
}
