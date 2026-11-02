package app.clairix.analysis

import app.clairix.analysis.CameraEvidence.HostEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network and Bluetooth halves of the hidden-camera sweep, on the formats
 * devices send: WS-Discovery answers laid out as the WS-Discovery 2005/04 and ONVIF
 * Core specifications describe them (the scopes and service addresses an ONVIF
 * camera publishes, a printer's answer, another phone's probe), mDNS service types
 * and HomeKit categories, Bluetooth names and provisioning services, and the verdict
 * on a host from what it answered.
 */
class CameraEvidenceTest {

    private val now = 1_793_450_117_000L

    /** A ProbeMatches envelope as ONVIF Core §7.3 lays it out, with the given types, scopes and service addresses. */
    private fun probeMatches(types: String, scopes: String, xaddrs: String?): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
            xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:dn="http://www.onvif.org/ver10/network/wsdl"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
        <SOAP-ENV:Header>
        <wsa:MessageID>uuid:4f1c6a10-8c1d-11ec-8000-a41437c0ffee</wsa:MessageID>
        <wsa:RelatesTo>uuid:0a9e5b4c-2f61-4d53-9a8e-3b7c1d2e4f50</wsa:RelatesTo>
        <wsa:To SOAP-ENV:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To>
        <wsa:Action SOAP-ENV:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
        <d:AppSequence InstanceId="1644312000" MessageNumber="7"/>
        </SOAP-ENV:Header>
        <SOAP-ENV:Body>
        <d:ProbeMatches>
        <d:ProbeMatch>
        <wsa:EndpointReference><wsa:Address>urn:uuid:4f1c6a10-8c1d-11ec-8000-a41437c0ffee</wsa:Address></wsa:EndpointReference>
        <d:Types>$types</d:Types>
        <d:Scopes>$scopes</d:Scopes>
        ${if (xaddrs != null) "<d:XAddrs>$xaddrs</d:XAddrs>" else ""}
        <d:MetadataVersion>10</d:MetadataVersion>
        </d:ProbeMatch>
        </d:ProbeMatches>
        </SOAP-ENV:Body>
        </SOAP-ENV:Envelope>
    """.trimIndent()

    // ── ONVIF ProbeMatch ────────────────────────────────────────────────────

    @Test
    fun anOnvifCameraSaysItsNameHardwareAndAddress() {
        val xml = probeMatches(
            types = "dn:NetworkVideoTransmitter tds:Device",
            scopes = "onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/Profile/Streaming " +
                "onvif://www.onvif.org/Profile/T onvif://www.onvif.org/MAC/a4:14:37:c0:ff:ee " +
                "onvif://www.onvif.org/hardware/DS-2CD2043G2-I onvif://www.onvif.org/name/HIKVISION%20DS-2CD2043G2-I " +
                "onvif://www.onvif.org/location/city/hangzhou",
            xaddrs = "http://192.168.1.64/onvif/device_service http://[fe80::a614:37ff:fec0:ffee]/onvif/device_service"
        )
        assertEquals(
            "HIKVISION DS-2CD2043G2-I · DS-2CD2043G2-I · http://192.168.1.64/onvif/device_service",
            CameraEvidence.parseProbeMatch(xml)
        )
    }

    @Test
    fun aNameTheSameAsTheHardwareIsSaidOnce() {
        val xml = probeMatches(
            types = "tdn:NetworkVideoTransmitter",
            scopes = "onvif://www.onvif.org/type/Network_Video_Transmitter onvif://www.onvif.org/name/IPC-HDW2431T-AS-S2 " +
                "onvif://www.onvif.org/hardware/IPC-HDW2431T-AS-S2 onvif://www.onvif.org/location/country/china",
            xaddrs = "http://10.0.0.108/onvif/device_service"
        )
        assertEquals("IPC-HDW2431T-AS-S2 · http://10.0.0.108/onvif/device_service", CameraEvidence.parseProbeMatch(xml))
    }

    @Test
    fun aCameraThatSaysNothingIsStillAnOnvifDevice() {
        val xml = probeMatches(types = "dn:NetworkVideoTransmitter", scopes = "onvif://www.onvif.org/Profile/Streaming", xaddrs = null)
        assertEquals("ONVIF device", CameraEvidence.parseProbeMatch(xml))
    }

    @Test
    fun aPrinterAnsweringTheProbeIsNotACamera() {
        val xml = probeMatches(
            types = "wsdp:Device wprt:PrintDeviceType",
            scopes = "onvif://www.onvif.org/name/Brother%20HL-L2350DW",
            xaddrs = "http://192.168.1.30:80/WebServices/Device"
        )
        assertNull(CameraEvidence.parseProbeMatch(xml))
    }

    @Test
    fun anotherPhonesProbeIsNotAnAnswer() {
        // What this app sends itself, as another phone running a sweep would: a Probe, not a ProbeMatch.
        val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:0a9e5b4c-2f61-4d53-9a8e-3b7c1d2e4f50</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
        assertNull(CameraEvidence.parseProbeMatch(probe))
        assertNull(CameraEvidence.parseProbeMatch(""))
    }

    @Test
    fun aLongNameIsCutToFitTheFinding() {
        val xml = probeMatches(
            types = "dn:NetworkVideoTransmitter",
            scopes = "onvif://www.onvif.org/name/" + "Camera".repeat(30),
            xaddrs = "http://192.168.1.70/onvif/device_service"
        )
        assertEquals(120, CameraEvidence.parseProbeMatch(xml)!!.length)
    }

    // ── mDNS ────────────────────────────────────────────────────────────────

    private fun mdns(type: String, name: String, ci: String? = null, ip: String? = "192.168.1.41", ipv4: Boolean = true) =
        CameraEvidence.classifyMdns(type, ip, ipv4, name, ci)

    @Test
    fun cameraServiceTypesAreCamerasWhateverTheyAreCalled() {
        assertEquals(0.9f, mdns("_axis-video._tcp.", "AXIS M3045-V - ACCC8E123456")!!.confidence)
        assertEquals(0.9f, mdns("_onvif._tcp.", "Living room")!!.confidence)
        assertEquals(0.85f, mdns("_psia._tcp.", "Front gate")!!.confidence)
        assertEquals(0.8f, mdns("_rtsp._tcp.", "Synology DS220+")!!.confidence)
    }

    @Test
    fun homeKitCountsOnlyCamerasAndDoorbells() {
        assertEquals("HomeKit IP camera", mdns("_hap._tcp.", "Logitech Circle View 1A2B", ci = "17")!!.label)
        assertEquals("HomeKit video doorbell", mdns("_hap._tcp.", "Aqara Doorbell G4", ci = "18 ")!!.label)
        assertNull("a light bulb", mdns("_hap._tcp.", "Hue Bridge 3C4D", ci = "5"))
        assertNull("a bridge", mdns("_hap._tcp.", "Kitchen", ci = "2"))
        assertNull("no category at all", mdns("_hap._tcp.", "Thermostat", ci = null))
    }

    @Test
    fun aWebServerCountsOnlyWhenItsNameSaysCamera() {
        assertEquals(0.6f, mdns("_http._tcp.", "Reolink E1 Pro")!!.confidence)
        assertEquals(0.6f, mdns("_http._tcp.", "Garden Cam")!!.confidence)
        assertNull(mdns("_http._tcp.", "Brother HL-L2350DW"))
        assertNull(mdns("_http._tcp.", "Camden office NAS"))
        assertNull(mdns("_http._tcp.", "OctoPrint on Prusa"))
        assertNull("not a type the sweep browses", mdns("_ipp._tcp.", "Office camera"))
    }

    @Test
    fun anAddressTheSweepCannotReachIsNoHit() {
        assertNull(mdns("_onvif._tcp.", "Living room", ip = "fe80::a614:37ff:fec0:ffee", ipv4 = false))
        assertNull(mdns("_onvif._tcp.", "Living room", ip = null))
        val hit = mdns("_rtsp._tcp.", "Porch")!!
        assertEquals("192.168.1.41", hit.ip)
        assertEquals("_rtsp._tcp.", hit.type)
        assertEquals("Porch", hit.name)
    }

    // ── Bluetooth ───────────────────────────────────────────────────────────

    private val tuya = "0000fd50-0000-1000-8000-00805f9b34fb"
    private val xiaomi = "0000fe95-0000-1000-8000-00805f9b34fb"
    private val bluFi = "0000ffff-0000-1000-8000-00805f9b34fb"
    private val goPro = "0000fea6-0000-1000-8000-00805f9b34fb"
    private val battery = "0000180f-0000-1000-8000-00805f9b34fb"

    @Test
    fun camerasNamedInTheirAdvertsAreFound() {
        assertEquals(0.85f, CameraEvidence.matchBle("WYZE_CAM_V3", emptyList())!!.confidence)
        assertEquals(0.8f, CameraEvidence.matchBle("Tapo_C200_A1B2", emptyList())!!.confidence)
        assertEquals(0.8f, CameraEvidence.matchBle("Blink XT2", emptyList())!!.confidence)
        assertEquals(0.85f, CameraEvidence.matchBle("IPC_5F3A", listOf(tuya))!!.confidence)
        assertEquals(0.85f, CameraEvidence.matchBle("A9-3F9A1C", emptyList())!!.confidence)
        assertEquals(0.7f, CameraEvidence.matchBle("CAM-3F9A1C", emptyList())!!.confidence)
        // The name wins over a provisioning service it also carries.
        assertEquals("Wyze camera pairing over Bluetooth", CameraEvidence.matchBle("Wyze Cam", listOf(bluFi))!!.label)
    }

    @Test
    fun aTuyaPlugIsAWeakHintAndHeadphonesAreNothing() {
        // A Smart Life plug in pairing mode: no camera name, Tuya's service.
        val plug = CameraEvidence.matchBle("SmartPlug", listOf(tuya))
        assertEquals(0.3f, plug!!.confidence)
        assertTrue(plug.label.startsWith("Tuya smart-home device"))
        assertEquals(0.3f, CameraEvidence.matchBle(null, listOf(tuya))!!.confidence)
        assertNull(CameraEvidence.matchBle("WH-1000XM4", listOf(battery)))
        assertNull(CameraEvidence.matchBle("Camden's iPhone", emptyList()))
        assertNull(CameraEvidence.matchBle(null, emptyList()))
    }

    @Test
    fun theStrongestProvisioningServiceIsTheOneReported() {
        assertEquals(0.3f, CameraEvidence.matchBle(null, listOf(xiaomi, bluFi))!!.confidence)
        assertEquals(0.3f, CameraEvidence.matchBle(null, listOf(bluFi, xiaomi))!!.confidence)
        // Upper-case UUIDs, as some stacks print them.
        assertEquals(0.25f, CameraEvidence.matchBle(null, listOf(xiaomi.uppercase()))!!.confidence)
        // A GoPro that advertises its name is named by it; one that advertises only its control service, by that.
        assertEquals("Action camera (GoPro / Insta360 / DJI)", CameraEvidence.matchBle("GoPro 4821", listOf(goPro))!!.label)
        assertEquals(0.55f, CameraEvidence.matchBle(null, listOf(goPro))!!.confidence)
    }

    // ── One host ────────────────────────────────────────────────────────────

    private val hikvisionPage = HiddenCameraScanner.HTTP_SIGNATURES.first { it.label == "Hikvision camera or recorder" }
    private val goAhead = HiddenCameraScanner.HTTP_SIGNATURES.first { it.confidence == 0.4f }

    private fun judge(h: HostEvidence, gateway: String? = "192.168.1.1") =
        CameraEvidence.judgeHost(h, gateway, ssid = "Harbour View Guest", now = now, lat = 40.0, lon = -83.0)

    @Test
    fun anOnvifCameraWithRtspAndAWebPageIsReportedWithEveryReason() {
        val f = judge(HostEvidence(
            ip = "192.168.1.64", openPorts = setOf(80, 554, 8000),
            rtsp = "RTSP/1.0 401 Unauthorized, Server: Hikvision-Webs", rtspPort = 554,
            http = hikvisionPage, httpServer = "App-webs/", httpPort = 80,
            onvif = "HIKVISION DS-2CD2043G2-I · DS-2CD2043G2-I · http://192.168.1.64/onvif/device_service",
        ))
        assertNotNull(f)
        assertEquals(0.95f, f!!.confidence)
        assertEquals("net|192.168.1.64", f.id)
        assertEquals(CameraFindMethod.NETWORK, f.method)
        assertEquals("192.168.1.64 on Harbour View Guest", f.location)
        assertTrue(f.detail, f.detail.startsWith("Answered an ONVIF discovery probe (HIKVISION DS-2CD2043G2-I"))
        assertTrue(f.detail, "RTSP video server on port 554" in f.detail)
        assertTrue(f.detail, "Hikvision camera or recorder (web UI on port 80, server \"App-webs/\")" in f.detail)
        assertTrue(f.detail, "Open ports: 80, 554, 8000" in f.detail)
        assertEquals(now, f.ts)
        assertEquals(40.0, f.lat!!, 0.0)
    }

    @Test
    fun aWebUiWithNoCameraFingerprintIsEveryPrinterAndIsNotReported() {
        assertNull(judge(HostEvidence(ip = "192.168.1.30", openPorts = setOf(80, 443, 631))))
        assertNull(judge(HostEvidence(ip = "192.168.1.31")))
    }

    @Test
    fun theRouterIsACameraOnlyOnHardEvidence() {
        // GoAhead on the router, and an RTSP port it leaves open for a media server: not enough.
        assertNull(judge(HostEvidence(ip = "192.168.1.1", openPorts = setOf(80, 554), http = goAhead, httpPort = 80)))
        // The same answers from another host are reported, low.
        assertEquals(0.4f, judge(HostEvidence(ip = "192.168.1.9", openPorts = setOf(80, 554), http = goAhead, httpPort = 80))!!.confidence)
        // A router that answers ONVIF as a video transmitter is one.
        val router = judge(HostEvidence(ip = "192.168.1.1", openPorts = setOf(80), onvif = "ONVIF device"))
        assertEquals(0.95f, router!!.confidence)
        assertTrue(router.location.endsWith(" (the router)"))
    }

    @Test
    fun recorderPortsCountOnlyWithoutAWebFingerprint() {
        val dahua = judge(HostEvidence(ip = "192.168.1.108", openPorts = setOf(37777)))
        assertEquals(0.5f, dahua!!.confidence)
        assertTrue("Dahua-protocol port 37777 open" in dahua.detail)
        val xmeye = judge(HostEvidence(ip = "192.168.1.10", openPorts = setOf(34567)))
        assertEquals(0.5f, xmeye!!.confidence)
        // With the web page identified, the page is the reason and the port adds nothing.
        val withPage = judge(HostEvidence(ip = "192.168.1.108", openPorts = setOf(80, 37777), http = hikvisionPage, httpPort = 80))
        assertEquals(0.92f, withPage!!.confidence)
        assertTrue(withPage.detail, "37777 open" !in withPage.detail)
    }

    @Test
    fun aSilentRtspPortAndRtmpAreWeakAndAnMdnsCameraIsStrong() {
        assertEquals(0.4f, judge(HostEvidence(ip = "192.168.1.20", openPorts = setOf(8554)))!!.confidence)
        assertTrue("RTSP port 8554 open but silent" in judge(HostEvidence(ip = "192.168.1.20", openPorts = setOf(8554)))!!.detail)
        assertEquals(0.35f, judge(HostEvidence(ip = "192.168.1.21", openPorts = setOf(1935)))!!.confidence)
        val axis = CameraEvidence.MdnsHit("192.168.1.22", "_axis-video._tcp.", "AXIS M3045-V", "Axis camera advertised over mDNS", 0.9f)
        val f = judge(HostEvidence(ip = "192.168.1.22", mdns = listOf(axis)))
        assertEquals(0.9f, f!!.confidence)
        assertTrue(f.detail, f.detail.startsWith("Axis camera advertised over mDNS — advertises \"AXIS M3045-V\" as _axis-video._tcp"))
        assertTrue("no ports, no port list", "Open ports" !in f.detail)
    }

    @Test
    fun withoutAnSsidTheFindingSaysThisWifi() {
        val f = CameraEvidence.judgeHost(HostEvidence(ip = "10.0.0.5", onvif = "ONVIF device"), gateway = null, ssid = null, now = now, lat = null, lon = null)
        assertEquals("10.0.0.5 on this Wi-Fi", f!!.location)
    }
}
