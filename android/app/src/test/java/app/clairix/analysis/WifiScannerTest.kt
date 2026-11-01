package app.clairix.analysis

import app.clairix.Threat
import app.clairix.detect.KarmaCanary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bait rules on beacon lists as the platform reports them, capability strings
 * and all. What matters is what they do *not* flag: an earlier version lit up on
 * every carrier hotspot in a US street.
 */
class WifiScannerTest {

    private val now = 1_700_000_000_000L

    private fun open(ssid: String, bssid: String, rssi: Int = -62) = WifiBeacon(ssid, bssid, rssi, "[ESS]")
    private fun wpa2(ssid: String, bssid: String, rssi: Int = -58) = WifiBeacon(ssid, bssid, rssi, "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]")
    private fun wpa3(ssid: String, bssid: String) = WifiBeacon(ssid, bssid, -55, "[RSN-SAE-CCMP][ESS][MFPR][MFPC]")
    private fun owe(ssid: String, bssid: String) = WifiBeacon(ssid, bssid, -60, "[RSN-OWE-CCMP][ESS][MFPR][MFPC]")
    private fun passpoint(ssid: String, bssid: String) = WifiBeacon(ssid, bssid, -64, "[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS][PASSPOINT]")

    @Test
    fun anOpenCarrierHotspotIsANoteNotAnAlarm() {
        // A cable neighbourhood: every gateway broadcasts the open captive portal beside the
        // secured home network and the Passpoint sibling. None of it is bait.
        val a = WifiScanner.classify(listOf(
            open("xfinitywifi", "aa:bb:cc:00:00:01"),
            passpoint("XFINITY", "aa:bb:cc:00:00:02"),
            wpa2("HOME-5G", "aa:bb:cc:00:00:03"),
            open("attwifi", "aa:bb:cc:00:00:04"),
            open("BTWifi-with-FON", "aa:bb:cc:00:00:05"),
            open("Verizon Guest", "aa:bb:cc:00:00:06")
        ), now)
        assertTrue(a.toString(), a.none { it.threat.ordinal >= Threat.HIGH.ordinal })
        assertEquals(a.toString(), 4, a.size)
        assertTrue(a.toString(), a.all { it.reason == WifiScanner.REASON_OPEN_CARRIER_HOTSPOT && it.threat == Threat.LOW })
    }

    @Test
    fun aSecuredCarrierNetworkIsNotEvenANote() {
        val a = WifiScanner.classify(listOf(
            wpa2("xfinitywifi", "aa:bb:cc:00:00:01"),
            wpa3("AT&T WiFi", "aa:bb:cc:00:00:02"),
            owe("Verizon Guest", "aa:bb:cc:00:00:03")
        ), now)
        assertTrue(a.toString(), a.isEmpty())
    }

    @Test
    fun ordinaryNamesAreNothing() {
        // "att" inside Hyatt, "ee" inside Free, Orange and Metro as ordinary words.
        val a = WifiScanner.classify(listOf(
            open("Hyatt Guest", "10:00:00:00:00:01"),
            open("Free WiFi", "10:00:00:00:00:02"),
            open("Orange County Library", "10:00:00:00:00:03"),
            open("Metro Transit WiFi", "10:00:00:00:00:04"),
            wpa2("Seattle Public Library", "10:00:00:00:00:05")
        ), now)
        assertTrue(a.toString(), a.isEmpty())
    }

    @Test
    fun anOpenTwinOfASecuredNetworkIsHigh() {
        val a = WifiScanner.classify(listOf(
            wpa2("Starbucks", "20:00:00:00:00:01"),
            open("Starbucks", "20:00:00:00:00:02")
        ), now)
        assertEquals(a.toString(), 1, a.size)
        assertEquals("open_twin_of_secured", a[0].reason)
        assertEquals(Threat.HIGH, a[0].threat)
        assertEquals("20:00:00:00:00:02", a[0].bssid)
    }

    @Test
    fun anOpenTwinOfACarrierNetworkIsTheTwinNotTheNote() {
        // The secured XFINITY is real; an open one beside it is an evil twin, judged
        // before the hotspot note so it is reported as what it is, once.
        val a = WifiScanner.classify(listOf(
            wpa3("XFINITY", "30:00:00:00:00:01"),
            open("XFINITY", "30:00:00:00:00:02")
        ), now)
        assertEquals(a.toString(), 1, a.size)
        assertEquals("open_twin_of_secured", a[0].reason)
        assertEquals(Threat.HIGH, a[0].threat)
    }

    @Test
    fun enhancedOpenIsNotOpen() {
        // OWE encrypts; an OWE "Starbucks" beside a WPA2 one is a transition pair, not a twin.
        val a = WifiScanner.classify(listOf(
            wpa2("Starbucks", "40:00:00:00:00:01"),
            owe("Starbucks", "40:00:00:00:00:02")
        ), now)
        assertTrue(a.toString(), a.isEmpty())
    }

    @Test
    fun wifiInterceptionKitIsHighAndCellularProductNamesAreNot() {
        val a = WifiScanner.classify(listOf(
            open("Pineapple", "50:00:00:00:00:01"),
            wpa2("stingray", "50:00:00:00:00:02"),
            open("hailstorm", "50:00:00:00:00:03"),
            wpa2("AndroidWifi", "50:00:00:00:00:04")
        ), now)
        assertEquals(a.toString(), 1, a.size)
        assertEquals("known_catcher_ssid", a[0].reason)
        assertEquals(Threat.HIGH, a[0].threat)
        assertEquals("Pineapple", a[0].ssid)
    }

    @Test
    fun theCanaryAnsweredIsCritical() {
        val canary = "3f9a1c0e5b7d2a4c6e8f0a1b2c3d4e5f"
        val beacons = listOf(open(canary, "de:ad:be:ef:00:01", rssi = -48), open("xfinitywifi", "de:ad:be:ef:00:02"))
        val hits = KarmaCanary.checkBeacons(canary, beacons, now)
        assertEquals(hits.toString(), 1, hits.size)
        assertEquals(Threat.CRITICAL, hits[0].threat)
        assertEquals(KarmaCanary.REASON, hits[0].reason)
        assertEquals("de:ad:be:ef:00:01", hits[0].bssid)
        // The canary is the canary's business: the bait rules see a random name and say nothing.
        assertTrue(WifiScanner.classify(beacons, now).none { it.ssid == canary })
    }

    @Test
    fun oneAnomalyPerAccessPointAndReason() {
        val twice = listOf(open("Starbucks", "60:00:00:00:00:02"), wpa2("Starbucks", "60:00:00:00:00:01"), open("Starbucks", "60:00:00:00:00:02"))
        assertEquals(1, WifiScanner.classify(twice, now).size)
    }
}
