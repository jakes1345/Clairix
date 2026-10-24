package app.clairix.detect

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import java.util.Locale

/**
 * Radio fingerprints of fixed surveillance hardware: what a Flock Safety plate
 * reader, its battery pack or a Raven gunshot detector says about itself over
 * Bluetooth and Wi-Fi.
 *
 * The map list (OpenStreetMap, via the maps release) is the main way the app knows
 * where plate readers are; this is the second way, for a unit nobody has mapped yet.
 * Every signature here comes from public field work: the DeFlock project, Ryan
 * O'Horo's teardown of the Falcon and Raven, and the flock-you detectors
 * (colonelpanichacks, zmattmanz and others), all of which published what they heard.
 *
 * Honesty about reach: the cameras themselves upload over LTE and most do not
 * transmit Bluetooth at all. What is heard is the external battery pack reporting
 * its health to the camera, a Raven's GATT services, and the Wi-Fi a unit runs
 * during installation or troubleshooting. Flock renamed the batteries in March
 * 2025 and has turned the beacon off on many units since, so hearing nothing next
 * to a camera is normal; hearing one of these is not nothing. A phone cannot see
 * the Wi-Fi probe requests the newest detectors key on: that needs monitor mode,
 * which stock Android does not expose.
 *
 * The matching functions take plain values so they can be tested without Android;
 * [match] and [matchWifi] read them off the platform types.
 */
object SurveillanceHardware {

    enum class Kind(val label: String, val headline: String) {
        /** The external battery pack that powers a solar/battery Falcon or Sparrow. */
        FLOCK_BATTERY("Flock Safety camera battery", "Plate-reader hardware nearby"),
        /** Flock's acoustic gunshot detector (the Raven). */
        FLOCK_RAVEN("Flock Safety Raven gunshot detector", "Gunshot detector nearby"),
        /** A Flock unit's own Wi-Fi: the setup hotspot, or its radio's hardware address. */
        FLOCK_WIFI("Flock Safety camera Wi-Fi", "Plate-reader hardware nearby"),
        /** A Flock device that says so by name or by its accessory service, type unknown. */
        FLOCK_DEVICE("Flock Safety device", "Flock Safety device nearby"),
    }

    /** How sure the match is. POSSIBLE is shown nowhere; it exists so a test can see a near miss. */
    enum class Confidence { POSSIBLE, LIKELY, CERTAIN }

    data class Hit(val kind: Kind, val confidence: Confidence, val evidence: List<String>) {
        val label: String get() = kind.label
    }

    /** Bluetooth SIG company identifier in the battery pack's manufacturer data: XUNTONG. */
    const val COMPANY_XUNTONG = 0x09C8

    /** The GATT service the Flock accessory protocol runs on, as advertised. */
    const val ACCESSORY_SERVICE = "e8ccbb38-9532-46a8-9fe5-1814df172e6f"

    /** Flock Safety's own IEEE MA-L block, as registered: the first three octets of its radios' addresses. */
    const val FLOCK_OUI = "B4:1E:52"

    /** The Raven's vendor GATT services, 16-bit, advertised in this range by every firmware since 1.2. */
    private val RAVEN_SERVICES = 0x3100..0x35FF

    /** The battery's serial, "TN" and a long run of digits, inside the XUNTONG payload. */
    private val BATTERY_SERIAL = Regex("TN[0-9]{10,16}")
    /** Battery name before the March 2025 firmware. */
    private val PENGUIN_NAME = Regex("^Penguin-[0-9]{10}$", RegexOption.IGNORE_CASE)
    /** Battery name after it: the same ten digits with the prefix dropped. */
    private val TEN_DIGITS = Regex("^[0-9]{10}$")
    /** The setup hotspot: "Flock-" and part of the radio's address. */
    private val FLOCK_HOTSPOT = Regex("^Flock-[0-9A-Fa-f]{4,6}$")

    /**
     * Judges one BLE advertisement. [name] is the raw advertised name (not cleaned:
     * the battery's name is ten digits, which the display code treats as no name at
     * all). [staticAddress] is true for a public or random-static address, false for
     * a resolvable private one, null when the platform does not say.
     */
    fun matchBle(
        name: String?,
        companyIds: Collection<Int>,
        manufacturerData: (Int) -> ByteArray?,
        serviceUuids: Collection<String>,
        staticAddress: Boolean?
    ): Hit? {
        val evidence = ArrayList<String>(4)
        val n = name?.trim().orEmpty()
        val lower = n.lowercase(Locale.US)

        if (serviceUuids.any { it.equals(ACCESSORY_SERVICE, ignoreCase = true) }) {
            evidence += "advertises the Flock accessory service"
            return Hit(Kind.FLOCK_DEVICE, Confidence.CERTAIN, evidence)
        }

        val xuntong = COMPANY_XUNTONG in companyIds
        var serial: String? = null
        if (xuntong) {
            evidence += "manufacturer data from XUNTONG (0x09C8), the battery's maker"
            val payload = manufacturerData(COMPANY_XUNTONG)
            if (payload != null) {
                val ascii = String(CharArray(payload.size) { i ->
                    val b = payload[i].toInt() and 0xFF
                    if (b in 0x20..0x7E) b.toChar() else ' '
                })
                serial = BATTERY_SERIAL.find(ascii)?.value
                if (serial != null) evidence += "battery serial $serial in the payload"
            }
        }

        val strongName = lower.startsWith("fs ext battery") || PENGUIN_NAME.matches(n)
        val numericName = TEN_DIGITS.matches(n)
        val flockName = lower.contains("flock") || lower.contains("pigvision")
        when {
            strongName -> evidence += "named \"$n\", the battery pack's name"
            numericName -> evidence += "named \"$n\", the ten-digit name the batteries took in 2025"
            flockName -> evidence += "named \"$n\""
        }

        if (serial != null || (xuntong && (strongName || numericName || flockName)) || strongName) {
            return Hit(Kind.FLOCK_BATTERY, Confidence.CERTAIN, evidence)
        }

        val raven = serviceUuids.count { shortUuid(it) in RAVEN_SERVICES }
        if (raven > 0) {
            evidence += "$raven Raven GATT service${if (raven == 1) "" else "s"} (0x31xx–0x35xx)"
            return Hit(Kind.FLOCK_RAVEN, if (raven >= 3) Confidence.CERTAIN else Confidence.LIKELY, evidence)
        }

        if (xuntong) return Hit(Kind.FLOCK_BATTERY, Confidence.LIKELY, evidence)
        if (flockName) return Hit(Kind.FLOCK_DEVICE, Confidence.LIKELY, evidence)
        if (numericName) {
            if (staticAddress == true) evidence += "fixed Bluetooth address, as the batteries use"
            return Hit(Kind.FLOCK_BATTERY, Confidence.POSSIBLE, evidence)
        }
        return null
    }

    /**
     * Judges one Wi-Fi access point. The hardware address block is Flock's own
     * registration with the IEEE, so a BSSID in it is theirs; the hotspot name is the
     * one the units broadcast for setup. A bare "flock" in a network name is somebody's
     * home network far more often than a camera, and is only a possibility.
     */
    fun matchWifi(ssid: String?, bssid: String?): Hit? {
        val evidence = ArrayList<String>(2)
        val mac = bssid?.let { BleNames.formatMac(it) }
        if (mac != null && mac.startsWith(FLOCK_OUI)) {
            evidence += "hardware address $mac is in Flock Safety's own IEEE block (B4:1E:52)"
            return Hit(Kind.FLOCK_WIFI, Confidence.CERTAIN, evidence)
        }
        val s = ssid?.trim()?.trim('"').orEmpty()
        if (s.isEmpty()) return null
        if (FLOCK_HOTSPOT.matches(s)) {
            evidence += "network \"$s\", the units' setup hotspot name"
            return Hit(Kind.FLOCK_WIFI, Confidence.CERTAIN, evidence)
        }
        val lower = s.lowercase(Locale.US)
        if (lower.contains("flocksafety") || lower.contains("flock safety") || lower.contains("flockos") || lower == "flock") {
            evidence += "network \"$s\""
            return Hit(Kind.FLOCK_WIFI, Confidence.LIKELY, evidence)
        }
        if (lower.contains("flock")) {
            evidence += "network \"$s\""
            return Hit(Kind.FLOCK_WIFI, Confidence.POSSIBLE, evidence)
        }
        return null
    }

    /** [matchBle] over a platform scan record. [address] is the formatted address, for the address-type read. */
    fun match(record: ScanRecord?, device: BluetoothDevice?, address: String): Hit? {
        if (record == null) return null
        val mfr = record.manufacturerSpecificData
        val companyIds = ArrayList<Int>(mfr?.size() ?: 0)
        if (mfr != null) for (i in 0 until mfr.size()) companyIds += mfr.keyAt(i)
        val uuids = ArrayList<String>()
        record.serviceUuids?.forEach { uuids += it.uuid.toString() }
        record.serviceData?.keys?.forEach { uuids += it.uuid.toString() }
        val kind = if (device != null) BleNames.addressKind(device, address) else null
        val static = when (kind) {
            "public", "random static" -> true
            null -> null
            else -> false
        }
        return matchBle(record.deviceName, companyIds, { id -> record.getManufacturerSpecificData(id) }, uuids, static)
    }

    /** The 16-bit form of a SIG base UUID, or -1 for a vendor UUID. */
    private fun shortUuid(uuid: String): Int {
        val lower = uuid.lowercase(Locale.US)
        if (lower.length != 36 || !lower.startsWith("0000") || !lower.endsWith("-0000-1000-8000-00805f9b34fb")) return -1
        return lower.substring(4, 8).toIntOrNull(16) ?: -1
    }
}
