package app.clairix.analysis

import java.net.URLDecoder
import java.util.Locale
import kotlin.math.max

/**
 * What the hidden-camera sweep makes of the answers it gets, apart from the sockets,
 * the NsdManager callbacks and the Bluetooth scan that collect them
 * ([HiddenCameraScanner]): an ONVIF WS-Discovery `ProbeMatch`, an mDNS service, a
 * Bluetooth advertisement, and the verdict on one host from everything it answered.
 * Pure functions, so each can be tested with the formats real devices send.
 */
internal object CameraEvidence {

    /** The RTSP ports the sweep asks every live host about. */
    val RTSP_PORTS = setOf(554, 8554, 10554)

    // ── ONVIF WS-Discovery ──────────────────────────────────────────────────

    private val ONVIF_NAME = Regex("onvif://www\\.onvif\\.org/name/([^ <\"]+)")
    private val ONVIF_HARDWARE = Regex("onvif://www\\.onvif\\.org/hardware/([^ <\"]+)")
    private val XADDRS = Regex("<[^>]*XAddrs>([^<]+)<")

    /**
     * What a WS-Discovery answer says about the device that sent it ("name ·
     * hardware · service address", or "ONVIF device" when it says none of them), or
     * null when it is not a `ProbeMatch` from a `NetworkVideoTransmitter`: printers
     * and NAS boxes answer ONVIF probes too, and another phone's `Probe` is not an
     * answer at all.
     */
    fun parseProbeMatch(xml: String): String? {
        if (!xml.contains("ProbeMatch", ignoreCase = true) || !xml.contains("NetworkVideoTransmitter", ignoreCase = true)) return null
        val name = ONVIF_NAME.find(xml)?.groupValues?.get(1)?.let { urlDecode(it) }
        val hardware = ONVIF_HARDWARE.find(xml)?.groupValues?.get(1)?.let { urlDecode(it) }
        val xaddr = XADDRS.find(xml)?.groupValues?.get(1)?.trim()?.substringBefore(' ')
        val desc = listOfNotNull(name, hardware, xaddr).distinct().joinToString(" · ").ifEmpty { "ONVIF device" }
        return desc.take(120)
    }

    private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // ── mDNS ────────────────────────────────────────────────────────────────

    /** One camera-shaped mDNS service: where, what type, what it calls itself, and what that means. */
    data class MdnsHit(val ip: String, val type: String, val name: String, val label: String, val confidence: Float)

    /** A web server (`_http._tcp`) is only interesting when its name says camera. */
    val MDNS_CAMERA_NAME = Regex(
        "cam(era)?\\b|ipc|wyze|reolink|arlo|blink|eufy|tapo|ezviz|hik|dahua|imou|amcrest|foscam|nest cam|yi home|kami|doorbell|nvr|dvr|axis",
        RegexOption.IGNORE_CASE
    )

    /**
     * A resolved mDNS service of [type], at [ip] ([ipv4] false for an IPv6 address,
     * which the sweep does not reach), named [name], with the HomeKit accessory
     * category [homeKitCategory] from its TXT record (`ci`) when it has one; null when
     * it is not a camera.
     */
    fun classifyMdns(type: String, ip: String?, ipv4: Boolean, name: String, homeKitCategory: String?): MdnsHit? {
        if (ip == null || !ipv4) return null
        return when (type) {
            "_rtsp._tcp." -> MdnsHit(ip, type, name, "RTSP video stream advertised over mDNS", 0.8f)
            "_axis-video._tcp." -> MdnsHit(ip, type, name, "Axis camera advertised over mDNS", 0.9f)
            "_psia._tcp." -> MdnsHit(ip, type, name, "PSIA camera interface advertised over mDNS", 0.85f)
            "_onvif._tcp." -> MdnsHit(ip, type, name, "ONVIF camera advertised over mDNS", 0.9f)
            // HomeKit accessory category: 17 is an IP camera, 18 a video doorbell.
            "_hap._tcp." -> when (homeKitCategory?.trim()) {
                "17" -> MdnsHit(ip, type, name, "HomeKit IP camera", 0.9f)
                "18" -> MdnsHit(ip, type, name, "HomeKit video doorbell", 0.85f)
                else -> null
            }
            "_http._tcp." -> if (MDNS_CAMERA_NAME.containsMatchIn(name)) MdnsHit(ip, type, name, "Web server named like a camera", 0.6f) else null
            else -> null
        }
    }

    // ── Bluetooth ───────────────────────────────────────────────────────────

    class BleRule(pattern: String, val label: String, val confidence: Float) {
        val regex = Regex(pattern)
    }

    /** Advertised BLE names, lower-cased, anchored at the start. */
    val BLE_NAME_RULES = listOf(
        BleRule("^(wyze|wyzecam|wyze_cam).*", "Wyze camera pairing over Bluetooth", 0.85f),
        BleRule("^(arlo|blink|eufy|eufycam|eufy_cam|reolink|amcrest|foscam|tapo|ezviz|imou|lorex|swann|zmodo|annke|wansview|vimtag).*", "Security camera pairing over Bluetooth", 0.8f),
        BleRule("^(nest cam|nest_cam|google nest cam|nest doorbell|ring video doorbell|ring doorbell|ring-cam|ring cam).*", "Nest / Ring camera pairing over Bluetooth", 0.8f),
        BleRule("^(yi|kami)[-_ ](home|cam|dome|outdoor|smart|pro).*", "YI / Kami camera pairing over Bluetooth", 0.8f),
        BleRule("^(ipc|ipcam|ip_cam|ip-cam|camhi|v380|yoosee|icsee|xmeye|lookcam|a9[-_ ]|sq\\d{1,2}[-_ ]|minicam|spycam|hdcam).*", "Budget / covert Wi-Fi camera pairing over Bluetooth", 0.85f),
        BleRule("^(mi camera|mi_camera|xiaomi camera|chuangmi|imilab|aqara camera|aqara-cam|aqara g\\d).*", "Xiaomi / Aqara camera pairing over Bluetooth", 0.8f),
        BleRule("^(camera|cam|webcam)[-_ ]?[0-9a-f]{4,}$", "Device named like a camera pairing over Bluetooth", 0.7f),
        BleRule("^(gopro|insta360|osmo|dji|akaso)[-_ ].*|^(gopro|insta360)$", "Action camera (GoPro / Insta360 / DJI)", 0.5f),
        BleRule("^(70mai|viofo|blackvue|nextbase|ddpai|thinkware|dashcam|dash cam).*", "Dash camera", 0.45f)
    )

    /** 16-bit service UUIDs that mark a device as unprovisioned IoT; weak alone, so they rank low. */
    val BLE_SERVICE_RULES = mapOf(
        "0000fea6-0000-1000-8000-00805f9b34fb" to ("GoPro camera control service" to 0.55f),
        "0000fd50-0000-1000-8000-00805f9b34fb" to ("Tuya smart-home device (budget cameras, plugs, bulbs) advertising for setup" to 0.3f),
        "0000fe95-0000-1000-8000-00805f9b34fb" to ("Xiaomi Mi Home device advertising for setup" to 0.25f),
        "0000ffff-0000-1000-8000-00805f9b34fb" to ("Espressif BluFi — an unprovisioned Wi-Fi IoT module" to 0.3f)
    )

    /** What a Bluetooth advertisement says it is, and how strongly. */
    data class BleMatch(val label: String, val confidence: Float)

    /**
     * The camera an advertisement names ([advertisedName], trimmed, from the
     * advertisement itself) or, failing a name, the strongest provisioning service
     * among [serviceUuids]; null when it is neither.
     */
    fun matchBle(advertisedName: String?, serviceUuids: List<String>): BleMatch? {
        val lower = advertisedName?.lowercase(Locale.US)
        if (lower != null) BLE_NAME_RULES.firstOrNull { it.regex.matches(lower) }?.let { return BleMatch(it.label, it.confidence) }
        var best: BleMatch? = null
        for (uuid in serviceUuids) {
            val (label, confidence) = BLE_SERVICE_RULES[uuid.lowercase(Locale.US)] ?: continue
            if (confidence > (best?.confidence ?: 0f)) best = BleMatch(label, confidence)
        }
        return best
    }

    // ── One host ────────────────────────────────────────────────────────────

    /** Everything one address answered during a network sweep. */
    data class HostEvidence(
        val ip: String,
        val openPorts: Set<Int> = emptySet(),
        /** The RTSP server's banner, and the port it answered on. */
        val rtsp: String? = null,
        val rtspPort: Int = 0,
        /** The strongest camera fingerprint in its web pages, its Server header, and the port. */
        val http: HiddenCameraScanner.HttpSignature? = null,
        val httpServer: String? = null,
        val httpPort: Int = 0,
        /** What its ONVIF ProbeMatch said ([parseProbeMatch]). */
        val onvif: String? = null,
        val mdns: List<MdnsHit> = emptyList(),
    )

    /**
     * The finding for one host on the Wi-Fi called [ssid], or null when nothing it
     * answered says camera: a host with only a web UI and no camera fingerprint is
     * every printer and router. The router ([gateway]) runs a web UI and sometimes a
     * media port, so only hard evidence (0.9 or more) makes it a camera.
     */
    fun judgeHost(h: HostEvidence, gateway: String?, ssid: String?, now: Long, lat: Double?, lon: Double?): CameraFinding? {
        var conf = 0f
        val reasons = ArrayList<String>()
        h.onvif?.let { conf = max(conf, 0.95f); reasons += "Answered an ONVIF discovery probe ($it)" }
        h.rtsp?.let { conf = max(conf, 0.85f); reasons += "RTSP video server on port ${h.rtspPort}: $it" }
        h.http?.let {
            conf = max(conf, it.confidence)
            val server = h.httpServer
            val serverNote = if (server != null) ", server \"$server\"" else ""
            reasons += "${it.label} (web UI on port ${h.httpPort}$serverNote)"
        }
        for (m in h.mdns) { conf = max(conf, m.confidence); reasons += "${m.label} — advertises \"${m.name}\" as ${m.type.trimEnd('.')}" }
        if (37777 in h.openPorts && h.http == null) { conf = max(conf, 0.5f); reasons += "Dahua-protocol port 37777 open" }
        if (34567 in h.openPorts && h.http == null) { conf = max(conf, 0.5f); reasons += "XMeye/Sofia recorder port 34567 open" }
        if (1935 in h.openPorts) { conf = max(conf, 0.35f); reasons += "RTMP streaming port 1935 open" }
        if (h.rtsp == null && h.openPorts.any { it in RTSP_PORTS }) {
            conf = max(conf, 0.4f)
            reasons += "RTSP port ${h.openPorts.first { it in RTSP_PORTS }} open but silent"
        }
        if (conf <= 0f) return null
        if (h.ip == gateway && conf < 0.9f) return null
        val ports = h.openPorts.sorted().joinToString(", ")
        return CameraFinding(
            id = "net|${h.ip}", ts = now, method = CameraFindMethod.NETWORK, confidence = conf,
            location = "${h.ip} on ${ssid ?: "this Wi-Fi"}" + if (h.ip == gateway) " (the router)" else "",
            detail = reasons.joinToString(". ") + (if (ports.isNotEmpty()) ". Open ports: $ports" else "") +
                ". There is a camera on this network. If it is not yours, or a camera the venue has told you about, ask who put it there.",
            lat = lat, lon = lon
        )
    }
}
