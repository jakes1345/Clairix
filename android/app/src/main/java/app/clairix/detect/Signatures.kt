package app.clairix.detect

import android.bluetooth.le.ScanRecord
import app.clairix.Threat
import app.clairix.TrackerType

private const val APPLE = 0x004C
private const val MICROSOFT = 0x0006
private const val FIND_MY_TYPE = 0x12
private const val FIND_MY_SEPARATED_LENGTH = 0x19
private val DULT_UUID = android.os.ParcelUuid.fromString("0000fcb2-0000-1000-8000-00805f9b34fb")
private val EDDYSTONE_UUID = android.os.ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

/**
 * Tracker signatures based on public reverse engineering — OpenHaystack,
 * packet captures and vendor documentation.
 *
 * Note the shape of Android's API differs from most write-ups:
 * getManufacturerSpecificData(id) returns the payload *after* the company ID,
 * so the Apple type byte is at index 0, not index 2.
 */
object Signatures {

    val AIRTAG = TrackerType(
        "airtag", "Apple AirTag", "Apple", Threat.CRITICAL,
        "Apple Find My tracker, away from its owner's iPhone. Rotates its Bluetooth address, " +
            "which is why this app tracks it by advertisement fingerprint rather than by MAC. " +
            "Commonly hidden in wheel wells, bumpers and bag linings."
    )

    val AIRTAG_NEAR_OWNER = TrackerType(
        "airtag_near", "Apple AirTag (owner nearby)", "Apple", Threat.MEDIUM,
        "An AirTag whose owner's iPhone is within Bluetooth range. Usually someone near you " +
            "carrying their own keys or bag. It is not proof of anything either way: if it keeps " +
            "turning up wherever you go, its owner is going there too, and the follow check judges " +
            "that the same way as for any other device."
    )

    val FINDMY = TrackerType(
        "findmy", "Apple Find My item", "Apple / third party", Threat.HIGH,
        "An item on Apple's Find My network, away from its owner: Chipolo ONE Spot, Pebblebee, " +
            "Motorola Tag or similar. Same global crowdsourced network as an AirTag."
    )

    val FINDMY_NEAR_OWNER = TrackerType(
        "findmy_near", "Apple Find My item (owner nearby)", "Apple / third party", Threat.MEDIUM,
        "A Find My item whose owner's iPhone is within Bluetooth range. The follow check still " +
            "applies: one that goes everywhere you go is travelling with someone who is."
    )

    val AIRPODS_FINDMY = TrackerType(
        "airpods_findmy", "AirPods on Find My", "Apple", Threat.LOW,
        "AirPods away from their owner, reporting on the Find My network. Unlikely to be planted, " +
            "but they can be located like any Find My item."
    )

    val GOOGLE_FIND_HUB = TrackerType(
        "google_find_hub", "Google Find Hub tracker", "Google / third party", Threat.HIGH,
        "A tag on Google's Find Hub network (Chipolo Point, Pebblebee for Android, Motorola Moto " +
            "Tag and others). Any Android phone nearby reports where it is."
    )

    val DULT_TRACKER = TrackerType(
        "dult", "Location tracker (away from owner)", "Unknown maker", Threat.HIGH,
        "Announces itself under the industry standard for unwanted tracking (DULT) as a tracker " +
            "that is separated from its owner. Clairix can ask it to name its maker and to play a sound."
    )

    val TILE = TrackerType(
        "tile", "Tile tracker", "Tile / Life360", Threat.HIGH,
        "Every Tile app in range passively reports this tag's position back to Tile."
    )

    val SMARTTAG = TrackerType(
        "smarttag", "Samsung SmartTag", "Samsung", Threat.HIGH,
        "Samsung's Find network (SmartTag, SmartTag+ or SmartTag2). Any nearby Galaxy running " +
            "SmartThings reports it."
    )

    val CHIPOLO = TrackerType(
        "chipolo", "Chipolo tracker", "Chipolo", Threat.HIGH,
        "Runs its own crowdsourced network, and the ONE Spot variant also joins Apple's."
    )

    val PEBBLEBEE = TrackerType(
        "pebblebee", "Pebblebee tracker", "Pebblebee", Threat.HIGH,
        "Clip, Card or Tag. Rechargeable, so it does not die after a year like a coin cell."
    )

    val ORBIT = TrackerType(
        "orbit", "Orbit / KeySmart", "Orbit", Threat.HIGH,
        "Uses its own network and piggybacks on Tile's."
    )

    val NUT = TrackerType(
        "nut", "Nut tracker", "Nut / Nutale", Threat.HIGH,
        "Crowdsourced Nut network. Cheap and widely sold outside the US."
    )

    val MOTOROLA_TAG = TrackerType(
        "motorola_tag", "Motorola MA1", "Motorola / Apple", Threat.HIGH,
        "Find My compatible tracker. Same Apple global crowdsourced network as AirTag."
    )

    val INVOXIA = TrackerType(
        "invoxia", "Invoxia GPS tracker", "Invoxia", Threat.HIGH,
        "Cellular + BLE GPS tracker designed for vehicle and asset tracking."
    )

    val CHIPOLO_SPOT = TrackerType(
        "chipolo_spot", "Chipolo ONE Spot", "Chipolo", Threat.HIGH,
        "Works on Apple's Find My network in addition to Chipolo's own."
    )

    val GPS_BLE = TrackerType(
        "gps_ble", "GPS tracker (BLE config)", "Unknown", Threat.HIGH,
        "A standalone GPS/LTE tracker advertising a Bluetooth configuration interface. " +
            "This is the kind of unit that gets magnet-mounted under a car."
    )

    val EDDYSTONE = TrackerType(
        "eddystone", "Eddystone beacon", "Google / generic", Threat.MEDIUM,
        "Proximity beacon. Usually retail analytics, occasionally repurposed for tracking."
    )

    private val GPS_NAME_HINTS = listOf(
        "gps", "tracker", "tk102", "tk103", "tk303", "gt02", "gt06", "gl300", "gl500",
        "st-9", "st901", "coban", "concox", "sinotrack", "meitrack", "queclink",
        "teltonika", "calamp", "bouncie", "optimus", "landairsea", "brickhouse",
        "spytec", "americaloc", "vyncs", "linxup", "samsara", "geotab"
    )

    /** Matches a 16-bit service UUID in either the UUID list or the service data. */
    private fun ScanRecord.hasShortUuid(short: Int): Boolean {
        val hex = "%04x".format(short)
        serviceUuids?.forEach {
            if (it.uuid.toString().regionMatches(4, hex, 0, 4, ignoreCase = true)) return true
        }
        serviceData?.keys?.forEach {
            if (it.uuid.toString().regionMatches(4, hex, 0, 4, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Devices that superficially look like trackers but are not. Checked first,
     * because otherwise every pair of AirPods in a coffee shop raises an alert
     * and the app becomes noise.
     */
    fun isBenign(record: ScanRecord?): Boolean {
        if (record == null) return false

        record.getManufacturerSpecificData(MICROSOFT)?.let { return true }

        val apple = record.getManufacturerSpecificData(APPLE) ?: return false
        if (apple.isEmpty()) return false

        return when (apple[0].toInt() and 0xFF) {
            0x01, 0x07 -> true                       // AirPods, Beats
            0x02 -> true                             // iBeacon — fixed infrastructure
            0x05, 0x09, 0x0B, 0x0D, 0x0E, 0x10 -> true // Handoff, Nearby, Watch, AirPlay
            else -> false
        }
    }

    /**
     * Identifiers are checked against the Bluetooth SIG's member UUID registry and, for
     * payload layouts, SEEMOO's AirGuard and the IETF DULT accessory protocol draft.
     *
     * Apple Find My (company 0x004C, type 0x12): byte 1 is the payload length, 0x19
     * while the item is separated from its owner and 0x02 while the owner's device is
     * near; bits 4–5 of the status byte (byte 2) say what it is: 0 an Apple phone,
     * tablet or computer, 1 an AirTag, 2 a third-party Find My item, 3 AirPods.
     */
    fun match(record: ScanRecord?): TrackerType? {
        if (record == null) return null

        record.getManufacturerSpecificData(APPLE)?.let { apple ->
            if (apple.size >= 2 && (apple[0].toInt() and 0xFF) == FIND_MY_TYPE) {
                val separated = (apple[1].toInt() and 0xFF) == FIND_MY_SEPARATED_LENGTH
                val kind = if (apple.size >= 3) (apple[2].toInt() and 0x30) shr 4 else -1
                return when (kind) {
                    1 -> if (separated) AIRTAG else AIRTAG_NEAR_OWNER
                    2 -> if (separated) FINDMY else FINDMY_NEAR_OWNER
                    3 -> AIRPODS_FINDMY
                    // An iPhone, iPad or Mac that is offline or switched off: findable,
                    // but a phone, not a tag. It is still followed like any device.
                    0 -> null
                    else -> if (separated) FINDMY else FINDMY_NEAR_OWNER
                }
            }
        }

        // DULT's own advertisement: [network ID][flags, bit 0 = near owner]. Sent by
        // compliant trackers of any maker, mandatory once separated.
        record.getServiceData(DULT_UUID)?.let { data ->
            if (data.size >= 2 && (data[1].toInt() and 0x01) == 0) return DULT_TRACKER
        }

        if (record.hasShortUuid(0xFEED) || record.hasShortUuid(0xFEEC) || record.hasShortUuid(0xFD84)) return TILE
        if (record.hasShortUuid(0xFD5A)) return SMARTTAG
        if (record.hasShortUuid(0xFE33) || record.hasShortUuid(0xFE65)) return CHIPOLO
        if (record.hasShortUuid(0xFA25)) return PEBBLEBEE
        // 0xFFE0/0xFFF3 are generic HM-10 UART UUIDs used by countless non-Orbit devices
        if (record.hasShortUuid(0xFFF3) && record.hasShortUuid(0xFFE0)) return ORBIT
        if (record.hasShortUuid(0xAA01) || record.hasShortUuid(0xAA02)) return NUT

        // Google's 0xFEAA carries both Eddystone beacons (frame types 0x00–0x30) and
        // Find Hub tags (0x40, and 0x41 in unwanted-tracking protection mode). Android
        // phones advertise Find Hub frames too while switched off; they set the
        // BR/EDR-capable advertising flags (0x02) where a tag sets 0x06.
        record.getServiceData(EDDYSTONE_UUID)?.let { data ->
            val frame = data.firstOrNull()?.toInt()?.and(0xFF)
            if (frame == 0x40 || frame == 0x41) {
                return if (record.advertiseFlags == 0x02) null else GOOGLE_FIND_HUB
            }
            if (frame != null && frame <= 0x30) return EDDYSTONE
        }
        if (record.hasShortUuid(0xFE85)) return INVOXIA

        val name = record.deviceName?.lowercase()
        if (name != null && GPS_NAME_HINTS.any { name.contains(it) }) return GPS_BLE

        return null
    }

    /**
     * Returns a human-readable brand label from the manufacturer-specific data company ID.
     * Used as a fallback when the device name is blank and no tracker signature matched.
     * The table itself lives in [BleNames], which the scanner now consults directly.
     */
    fun companyLabel(record: ScanRecord?): String? =
        BleNames.manufacturer(record)?.let { "$it device" }

    /**
     * Free-space path loss estimate. Rough, and honest about being rough.
     *
     * The curve wants the RSSI you would measure at one metre, not the radio's
     * transmit power — and `ScanRecord.getTxPowerLevel()` is the latter, frequently a
     * positive number. Feeding it in raw made the ratio negative, and a negative ratio
     * to the tenth power produced readings like "~540000 km away". Converting through
     * the ~41 dB of path loss over the first metre at 2.4 GHz gives the reference the
     * formula is actually expecting.
     */
    fun approximateMetres(rssi: Int, txPower: Int?): Double? {
        if (rssi >= 0) return null
        val reference = (txPower?.minus(41) ?: -59).coerceIn(-100, -30)
        val ratio = rssi.toDouble() / reference.toDouble()
        if (ratio <= 0.0) return null
        val metres = if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            0.89976 * Math.pow(ratio, 7.7095) + 0.111
        }
        // Beyond this the estimate is meaningless anyway, and BLE does not reach.
        return if (metres.isFinite() && metres > 0 && metres < 1000) metres else null
    }
}
