package app.clairix.detect

import android.bluetooth.le.ScanRecord
import app.clairix.Threat
import app.clairix.TrackerType

private const val APPLE = 0x004C
private const val MICROSOFT = 0x0006
private const val FIND_MY_TYPE = 0x12
private const val FIND_MY_SEPARATED_LENGTH = 0x19
/** Apple's iBeacon frame: type 0x02, length 0x15, then a 16-byte UUID, major, minor and power. */
private const val IBEACON_TYPE = 0x02
private const val IBEACON_LENGTH = 0x15
/** Find Hub frame types in Google's 0xFEAA service data; 0x41 is unwanted-tracking protection mode. */
private const val FIND_HUB_FRAME = 0x40
private const val FIND_HUB_SEPARATED_FRAME = 0x41
private val DULT_UUID = android.os.ParcelUuid.fromString("0000fcb2-0000-1000-8000-00805f9b34fb")
private val EDDYSTONE_UUID = android.os.ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

/**
 * The parts of one advertisement the signatures read, taken out of [ScanRecord] so
 * the classification can run, and be tested, without the Bluetooth stack.
 */
internal class Advert(
    /** Apple's manufacturer data (company 0x004C), after the company id; null when absent. */
    val apple: ByteArray? = null,
    /** Whether the advertisement carries manufacturer data under Microsoft's company id (0x0006). */
    val microsoft: Boolean = false,
    /** Every 16-bit UUID in the service UUID list or among the service data keys. */
    val shorts: Set<Int> = emptySet(),
    /** Service data under the DULT UUID (0xFCB2). */
    val dult: ByteArray? = null,
    /** Service data under Google's UUID (0xFEAA): Eddystone beacons and Find Hub tags. */
    val google: ByteArray? = null,
    /** The advertising flags byte, or -1 when the advertisement has none. */
    val flags: Int = -1,
    /** The advertised name, when there is one. */
    val name: String? = null
) {
    companion object {
        fun of(record: ScanRecord): Advert {
            val shorts = HashSet<Int>()
            record.serviceUuids?.forEach { short(it.uuid.toString())?.let(shorts::add) }
            record.serviceData?.keys?.forEach { short(it.uuid.toString())?.let(shorts::add) }
            return Advert(
                apple = record.getManufacturerSpecificData(APPLE),
                microsoft = record.getManufacturerSpecificData(MICROSOFT) != null,
                shorts = shorts,
                dult = record.getServiceData(DULT_UUID),
                google = record.getServiceData(EDDYSTONE_UUID),
                flags = record.advertiseFlags,
                name = record.deviceName
            )
        }

        /** The four hex digits a 16-bit UUID occupies in its 128-bit form, as a number. */
        private fun short(uuid: String): Int? = if (uuid.length >= 8) uuid.substring(4, 8).toIntOrNull(16) else null
    }
}

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
        "google_find_hub", "Google Find Hub tracker", "Google / third party", Threat.MEDIUM,
        "A tag on Google's Find Hub network (Chipolo Point, Pebblebee for Android, Motorola Moto " +
            "Tag and others), sending the frame a tag uses until it has been away from its owner's " +
            "phone for a while. Any Android phone nearby reports where it is. The follow check still " +
            "applies: one that goes everywhere you go is travelling with someone who is."
    )

    val GOOGLE_FIND_HUB_SEPARATED = TrackerType(
        "google_find_hub_separated", "Google Find Hub tracker (away from owner)", "Google / third party", Threat.HIGH,
        "A tag on Google's Find Hub network in its unwanted-tracking protection mode, which it " +
            "switches to once it has been away from its owner's phone: Chipolo Point, Pebblebee for " +
            "Android, Motorola Moto Tag and others. Any Android phone nearby reports where it is."
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

    val IBEACON = TrackerType(
        "ibeacon", "iBeacon", "Apple format / generic", Threat.MEDIUM,
        "Proximity beacon in Apple's iBeacon format. Shops and museums fix them to walls; cheap " +
            "anti-lost tags and home-made trackers send the same frame, and they do not change " +
            "their address."
    )

    /**
     * Readings of a tracker still with its owner, or not yet known to have left them:
     * Find My's "owner nearby" frames, and Find Hub's ordinary frame (0x40), which a tag
     * sends until it has been away from its owner's phone for a while and switches to
     * the unwanted-tracking frame (0x41). One that moves with you is travelling with
     * someone who is ([app.clairix.analysis.Tracker] calls it that, not a tracker
     * following you), and a higher-severity separated reading replaces it.
     */
    private val WITH_OWNER: Set<String> = setOf(AIRTAG_NEAR_OWNER.id, FINDMY_NEAR_OWNER.id, GOOGLE_FIND_HUB.id)

    /** Whether [type] is a reading of a tracker with its owner nearby; see [WITH_OWNER]. */
    fun withOwner(type: TrackerType?): Boolean = type != null && type.id in WITH_OWNER

    /**
     * Tracker types that change their Bluetooth address as a matter of course: every
     * Find My and Find Hub item, SmartTags, and anything announcing itself under DULT.
     * Marking one as yours holds for a day at a time ([app.clairix.TrustBook]): it is
     * recognised across the address changes the scanner links, and then asked about again.
     */
    private val ROTATING: Set<String> = setOf(
        AIRTAG.id, AIRTAG_NEAR_OWNER.id, FINDMY.id, FINDMY_NEAR_OWNER.id, AIRPODS_FINDMY.id,
        GOOGLE_FIND_HUB.id, GOOGLE_FIND_HUB_SEPARATED.id, SMARTTAG.id, DULT_TRACKER.id
    )

    /** Whether [type] rotates its address by design; see [ROTATING]. */
    fun rotates(type: TrackerType?): Boolean = type != null && type.id in ROTATING

    private val GPS_NAME_HINTS = listOf(
        "gps", "tracker", "tk102", "tk103", "tk303", "gt02", "gt06", "gl300", "gl500",
        "st-9", "st901", "coban", "concox", "sinotrack", "meitrack", "queclink",
        "teltonika", "calamp", "bouncie", "optimus", "landairsea", "brickhouse",
        "spytec", "americaloc", "vyncs", "linxup", "samsara", "geotab"
    )

    /**
     * Devices that superficially look like trackers but are not. Checked first,
     * because otherwise every pair of AirPods in a coffee shop raises an alert
     * and the app becomes noise.
     */
    fun isBenign(record: ScanRecord?): Boolean = record != null && isBenign(Advert.of(record))

    /**
     * [isBenign] on an [Advert]. An iBeacon (Apple type 0x02) is not on this list: a
     * shop's beacon stays on its wall and the follow check leaves it alone, while a
     * cheap anti-lost tag in the same format, which never changes its address, is
     * exactly what the follow check exists to catch.
     */
    internal fun isBenign(advert: Advert): Boolean {
        if (advert.microsoft) return true

        val apple = advert.apple ?: return false
        if (apple.isEmpty()) return false

        return when (apple[0].toInt() and 0xFF) {
            0x01, 0x07 -> true                       // AirPods, Beats
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
    fun match(record: ScanRecord?): TrackerType? = record?.let { match(Advert.of(it)) }

    /** [match] on an [Advert]. */
    internal fun match(advert: Advert): TrackerType? {
        advert.apple?.let { apple ->
            val type = if (apple.isNotEmpty()) apple[0].toInt() and 0xFF else -1
            if (apple.size >= 2 && type == FIND_MY_TYPE) {
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
            // iBeacon: [0x02][0x15][UUID ×16][major ×2][minor ×2][power].
            if (type == IBEACON_TYPE && apple.size >= 2 + IBEACON_LENGTH &&
                (apple[1].toInt() and 0xFF) == IBEACON_LENGTH
            ) return IBEACON
        }

        // DULT's own advertisement: [network ID][flags, bit 0 = near owner]. Sent by
        // compliant trackers of any maker, mandatory once separated.
        advert.dult?.let { data ->
            if (data.size >= 2 && (data[1].toInt() and 0x01) == 0) return DULT_TRACKER
        }

        val shorts = advert.shorts
        if (0xFEED in shorts || 0xFEEC in shorts || 0xFD84 in shorts) return TILE
        if (0xFD5A in shorts) return SMARTTAG
        if (0xFE33 in shorts || 0xFE65 in shorts) return CHIPOLO
        if (0xFA25 in shorts) return PEBBLEBEE
        // 0xFFE0/0xFFF3 are generic HM-10 UART UUIDs used by countless non-Orbit devices
        if (0xFFF3 in shorts && 0xFFE0 in shorts) return ORBIT
        if (0xAA01 in shorts || 0xAA02 in shorts) return NUT

        // Google's 0xFEAA carries both Eddystone beacons (frame types 0x00–0x30) and
        // Find Hub tags: 0x40 ordinarily, 0x41 in unwanted-tracking protection mode,
        // which a tag enters once it has been away from its owner. Android phones
        // advertise Find Hub frames too while switched off; they set the BR/EDR-capable
        // advertising flags (0x02) where a tag sets 0x06.
        advert.google?.let { data ->
            val frame = data.firstOrNull()?.toInt()?.and(0xFF)
            if (frame == FIND_HUB_FRAME || frame == FIND_HUB_SEPARATED_FRAME) {
                if (advert.flags == 0x02) return null
                return if (frame == FIND_HUB_SEPARATED_FRAME) GOOGLE_FIND_HUB_SEPARATED else GOOGLE_FIND_HUB
            }
            if (frame != null && frame <= 0x30) return EDDYSTONE
        }
        if (0xFE85 in shorts) return INVOXIA

        val name = advert.name?.lowercase()
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
