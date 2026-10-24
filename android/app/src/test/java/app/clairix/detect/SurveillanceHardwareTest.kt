package app.clairix.detect

import app.clairix.detect.SurveillanceHardware.Confidence
import app.clairix.detect.SurveillanceHardware.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Flock radio fingerprints, as the field reports describe them, and the near misses. */
class SurveillanceHardwareTest {

    private val none: (Int) -> ByteArray? = { null }
    private fun base(short: Int) = "0000%04x-0000-1000-8000-00805f9b34fb".format(short)

    private fun ble(
        name: String? = null,
        companies: List<Int> = emptyList(),
        data: Map<Int, ByteArray> = emptyMap(),
        uuids: List<String> = emptyList(),
        static: Boolean? = null
    ) = SurveillanceHardware.matchBle(name, companies, { data[it] }, uuids, static)

    @Test
    fun batteryWithXuntongIdAndSerialIsCertain() {
        val payload = "TN72023022000771".toByteArray(Charsets.US_ASCII)
        val hit = ble(companies = listOf(0x09C8), data = mapOf(0x09C8 to payload))
        assertNotNull(hit)
        assertEquals(Kind.FLOCK_BATTERY, hit!!.kind)
        assertEquals(Confidence.CERTAIN, hit.confidence)
        assertTrue(hit.evidence.any { it.contains("TN72023022000771") })
    }

    @Test
    fun serialIsFoundWithBinaryBytesAroundIt() {
        val payload = byteArrayOf(0x01, 0x00, 0xFF.toByte()) + "TN7202302200".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x7F)
        val hit = ble(companies = listOf(0x09C8), data = mapOf(0x09C8 to payload))
        assertEquals(Confidence.CERTAIN, hit!!.confidence)
    }

    @Test
    fun oldAndNewBatteryNamesAreCertainOnTheirOwn() {
        assertEquals(Confidence.CERTAIN, ble(name = "FS Ext Battery")!!.confidence)
        assertEquals(Confidence.CERTAIN, ble(name = "Penguin-1234567890")!!.confidence)
        assertEquals(Kind.FLOCK_BATTERY, ble(name = "fs ext battery 2")!!.kind)
    }

    @Test
    fun tenDigitNameAloneIsOnlyPossibleWithXuntongItIsCertain() {
        val alone = ble(name = "1234567890", static = true)
        assertNotNull(alone)
        assertEquals(Confidence.POSSIBLE, alone!!.confidence)
        val withId = ble(name = "1234567890", companies = listOf(0x09C8))
        assertEquals(Confidence.CERTAIN, withId!!.confidence)
    }

    @Test
    fun xuntongWithoutANameOrSerialIsLikely() {
        val hit = ble(companies = listOf(0x09C8), data = mapOf(0x09C8 to byteArrayOf(1, 2, 3)))
        assertEquals(Kind.FLOCK_BATTERY, hit!!.kind)
        assertEquals(Confidence.LIKELY, hit.confidence)
    }

    @Test
    fun accessoryServiceIsCertainWhateverElseIsSaid() {
        val hit = ble(uuids = listOf("E8CCBB38-9532-46A8-9FE5-1814DF172E6F"))
        assertEquals(Kind.FLOCK_DEVICE, hit!!.kind)
        assertEquals(Confidence.CERTAIN, hit.confidence)
    }

    @Test
    fun ravenServicesScaleWithHowManyAreHeard() {
        val one = ble(uuids = listOf(base(0x3101)))
        assertEquals(Kind.FLOCK_RAVEN, one!!.kind)
        assertEquals(Confidence.LIKELY, one.confidence)
        val three = ble(uuids = listOf(base(0x3100), base(0x3200), base(0x3300), base(0x180A)))
        assertEquals(Confidence.CERTAIN, three!!.confidence)
    }

    @Test
    fun ordinaryDevicesDoNotMatch() {
        assertNull(ble(name = "Pixel 9", companies = listOf(0x00E0)))
        assertNull(ble(name = "JBL Flip 6", companies = listOf(0x0057), uuids = listOf(base(0x180F), base(0x180A))))
        assertNull(ble(name = "Tile", uuids = listOf(base(0xFEED))))
        // A twelve-digit name is a MAC-like default, not the battery's ten digits.
        assertNull(ble(name = "123456789012"))
        // Nordic DFU alone is on countless devices.
        assertNull(ble(uuids = listOf("00001530-1212-efde-1523-785feabcd123")))
    }

    @Test
    fun flockInANameIsLikelyNothingMore() {
        val hit = ble(name = "FlockCam-02")
        assertEquals(Kind.FLOCK_DEVICE, hit!!.kind)
        assertEquals(Confidence.LIKELY, hit.confidence)
    }

    @Test
    fun flockOuiAndSetupHotspotAreCertain() {
        val oui = SurveillanceHardware.matchWifi("", "b4:1e:52:aa:bb:cc")
        assertEquals(Kind.FLOCK_WIFI, oui!!.kind)
        assertEquals(Confidence.CERTAIN, oui.confidence)
        val ap = SurveillanceHardware.matchWifi("Flock-A1B2C3", "74:4c:a1:68:da:63")
        assertEquals(Confidence.CERTAIN, ap!!.confidence)
        assertEquals(Confidence.CERTAIN, SurveillanceHardware.matchWifi("\"Flock-0A1B\"", null)!!.confidence)
    }

    @Test
    fun flockInANetworkNameIsAPossibilityNotAFinding() {
        assertEquals(Confidence.POSSIBLE, SurveillanceHardware.matchWifi("flock of seagulls", "00:11:22:33:44:55")!!.confidence)
        assertEquals(Confidence.LIKELY, SurveillanceHardware.matchWifi("FlockSafety-Install", "00:11:22:33:44:55")!!.confidence)
        assertNull(SurveillanceHardware.matchWifi("xfinitywifi", "00:11:22:33:44:55"))
        assertNull(SurveillanceHardware.matchWifi(null, null))
    }

    @Test
    fun aLiteOnRadioAddressAloneIsNotAFinding() {
        // LiteOn makes Wi-Fi cards for laptops too; the community OUI lists are corroboration, not proof.
        assertNull(SurveillanceHardware.matchWifi("Home", "74:4c:a1:68:da:63"))
    }
}
