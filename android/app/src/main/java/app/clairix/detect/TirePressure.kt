package app.clairix.detect

import app.clairix.Detection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tire pressure sensors (TPMS) heard over Bluetooth.
 *
 * Most factory tire sensors talk to the car on 315 or 433 MHz, a band a phone's radio
 * cannot receive, so those are out of reach here whatever their ID. The ones a phone
 * does hear are Bluetooth sensors: the valve-cap kits sold for cars, motorcycles and
 * trailers, and a few newer factory systems. Each broadcasts the same fixed ID for its
 * whole life and asks nothing of the listener, so anyone with a receiver can log it.
 * A sensor does not report where it is to anybody; what it gives away is that the
 * same wheel passed by. Clairix uses that the same way it uses any fixed address: one
 * that keeps turning up wherever you go is on a vehicle that keeps going there too,
 * or on your own car, which is why the owner can mark their car's set as theirs.
 *
 * The layouts read here are those published by people who decoded the sensors, and
 * nothing is guessed beyond them:
 *
 * - [Format.VALVE_CAP]: the common ZEEPIN / TP630 valve-cap kit, which names itself
 *   "TPMS1_…" to "TPMS4_…". Manufacturer data under company id 0x0100 (on air
 *   "00 01"), 16 bytes: the sensor's own address with the wheel in the first byte
 *   (0x80 is wheel 1, 0x83 wheel 4) and 0xEA 0xCA next, pressure in pascals as a
 *   little-endian 32-bit number, temperature in hundredths of a degree Celsius the
 *   same way, battery percent, and an alarm byte that is 1 when the tire has no
 *   pressure. Sources: the ra6070/BLE-TPMS project and a decode posted to the Home
 *   Assistant community, which agree. The Bluetooth SIG lists 0x0100 as TomTom's
 *   company id, so the whole shape is checked, not the id alone.
 * - [Format.BR]: the "BR" valve-cap sensor, which advertises the 16-bit service UUID
 *   0x27A5 and the short name "BR", and seven bytes of manufacturer data with no real
 *   company id: a status byte, battery volts × 10, temperature in °C, and pressure as a
 *   big-endian 16-bit number. Volts and temperature are as posted to the Arduino and
 *   Home Assistant forums. The pressure unit is ours: absolute pressure in tenths of a
 *   psi fits all three published packets against what the sensor's app showed
 *   (1.3, 2.5 and about 2.55 bar), so it is read that way and labelled approximate.
 * - [Format.DJTPMS]: the DJTPMS motorcycle sensor, named "DJTPMS", as its Home
 *   Assistant integration's author published it: battery volts × 10, temperature as a
 *   signed byte, and absolute pressure in kPa as a big-endian 16-bit number.
 *
 * Valve-cap sensors stay silent with no pressure on them, and send more often while
 * the wheel turns, so a parked car's set may not be heard at all.
 */
object TirePressure {

    enum class Format(val label: String) {
        VALVE_CAP("Valve-cap tire sensor (TPMS)"),
        BR("\"BR\" valve-cap tire sensor"),
        DJTPMS("DJTPMS tire sensor")
    }

    /** What one advertisement of a tire sensor said. */
    data class Reading(
        val format: Format,
        /** The ID the sensor broadcasts for its whole life, as colon-separated hex. */
        val sensorId: String,
        /** Wheel position 1–16 when the sensor says which, else null. */
        val wheel: Int? = null,
        /** Gauge pressure (above the air around it) in kPa, or null when not readable. */
        val pressureKpa: Double? = null,
        /** Whether [pressureKpa] comes from a fitted unit rather than a published one. */
        val pressureApproximate: Boolean = false,
        val temperatureC: Double? = null,
        val batteryPercent: Int? = null,
        val batteryVolts: Double? = null,
        /** The sensor says its tire has no pressure: off the wheel, or a flat. */
        val noPressure: Boolean = false
    ) {
        /** "Wheel 2 · 2.3 bar (33 psi) · 21 °C · battery 91 %", from what the sensor sent. */
        fun summary(): String {
            val parts = ArrayList<String>(5)
            wheel?.let { parts.add("Wheel $it") }
            when {
                noPressure -> parts.add("no pressure")
                pressureKpa != null -> {
                    val bar = String.format(Locale.US, "%.1f", pressureKpa / 100.0)
                    val psi = (pressureKpa * PSI_PER_KPA).roundToInt()
                    parts.add((if (pressureApproximate) "about " else "") + "$bar bar ($psi psi)")
                }
            }
            temperatureC?.let { parts.add("${it.roundToInt()} °C") }
            batteryPercent?.let { parts.add("battery $it %") }
                ?: batteryVolts?.let { parts.add("battery " + String.format(Locale.US, "%.1f", it) + " V") }
            return parts.joinToString(" · ")
        }
    }

    /** Company id the valve-cap kit sends ("00 01" on air, little-endian). */
    const val VALVE_CAP_COMPANY = 0x0100

    /** 16-bit service UUID the "BR" sensor advertises. */
    const val BR_SERVICE = 0x27A5

    private const val VALVE_CAP_LENGTH = 16
    private const val PSI_PER_KPA = 0.1450377
    private const val KPA_PER_PSI = 6.894757
    private const val ATMOSPHERE_PSI = 14.696
    private const val ATMOSPHERE_KPA = 101.325

    /** Above this a reading is not a road tire's, and is left out rather than shown. */
    private const val MAX_PLAUSIBLE_KPA = 1500.0

    /** Sensors heard together within this of each other can be one vehicle's set. */
    const val SET_WINDOW_MS = 10 * 60_000L

    /** No road vehicle a phone rides in has more Bluetooth tire sensors than this. */
    const val MAX_SET = 6

    /**
     * The tire sensor [advert] comes from, or null when it is not one. [address] is the
     * Bluetooth address it was heard on, the sensor's ID for the formats that put none
     * in their payload; without it those report their ID as unknown.
     */
    internal fun read(advert: Advert, address: String? = null): Reading? =
        valveCap(advert) ?: br(advert, address) ?: djtpms(advert, address)

    private fun valveCap(advert: Advert): Reading? {
        val d = advert.manufacturer[VALVE_CAP_COMPANY] ?: return null
        if (d.size != VALVE_CAP_LENGTH) return null
        val first = u8(d, 0)
        if (first !in 0x80..0x8F || u8(d, 1) != 0xEA || u8(d, 2) != 0xCA) return null
        val pascals = u32le(d, 6)
        val kpa = (pascals / 1000.0).takeIf { it <= MAX_PLAUSIBLE_KPA }
        val battery = u8(d, 14).takeIf { it <= 100 }
        return Reading(
            format = Format.VALVE_CAP,
            sensorId = hex(d, 0, 6),
            wheel = first - 0x7F,
            pressureKpa = kpa,
            temperatureC = s32le(d, 10) / 100.0,
            batteryPercent = battery,
            noPressure = u8(d, 15) == 0x01
        )
    }

    private fun br(advert: Advert, address: String?): Reading? {
        if (advert.name?.trim() != "BR" || BR_SERVICE !in advert.shorts) return null
        // Android reads the first two bytes as a company id; for this sensor they are
        // the status byte and the battery, and the rest is the payload.
        val (company, payload) = advert.manufacturer.entries.firstOrNull { it.value.size >= 3 }
            ?.let { it.key to it.value } ?: return Reading(Format.BR, address ?: UNKNOWN_ID)
        val volts = ((company shr 8) and 0xFF) / 10.0
        val raw = (u8(payload, 1) shl 8) or u8(payload, 2)
        val gauge = (raw / 10.0 - ATMOSPHERE_PSI) * KPA_PER_PSI
        return Reading(
            format = Format.BR,
            sensorId = address ?: UNKNOWN_ID,
            pressureKpa = max(gauge, 0.0).takeIf { gauge <= MAX_PLAUSIBLE_KPA },
            pressureApproximate = true,
            temperatureC = payload[0].toDouble(),
            batteryVolts = volts.takeIf { it in 1.5..4.0 }
        )
    }

    private fun djtpms(advert: Advert, address: String?): Reading? {
        if (advert.name?.trim() != "DJTPMS") return null
        val payload = advert.manufacturer.values.firstOrNull { it.size >= 4 }
            ?: return Reading(Format.DJTPMS, address ?: UNKNOWN_ID)
        val absolute = ((u8(payload, 2) shl 8) or u8(payload, 3)).toDouble()
        val gauge = absolute - ATMOSPHERE_KPA
        return Reading(
            format = Format.DJTPMS,
            sensorId = address ?: UNKNOWN_ID,
            pressureKpa = max(gauge, 0.0).takeIf { gauge <= MAX_PLAUSIBLE_KPA },
            temperatureC = payload[1].toDouble(),
            batteryVolts = (u8(payload, 0) / 10.0).takeIf { it in 1.5..4.0 }
        )
    }

    /**
     * The tire sensors that look like one vehicle's set with [sensor]: itself, and
     * every other tire sensor in [all] that is not [trusted] already, of the same
     * format, first heard within [SET_WINDOW_MS] of it and heard over the same stretch
     * of time, on a different wheel when both say which. A car's own four are heard
     * together from the moment it moves; a car alongside comes and goes on its own
     * clock. Closest in time first, [MAX_SET] at most, sorted by wheel. Empty when
     * [sensor] is not a tire sensor.
     *
     * A guess the owner confirms: the button that uses it names how many it marks,
     * and each can be taken back on its own.
     */
    fun sameVehicle(sensor: Detection, all: List<Detection>, trusted: Set<String> = emptySet()): List<Detection> {
        val own = sensor.tire ?: return emptyList()
        val wheels = HashSet<Int>().apply { own.wheel?.let(::add) }
        val others = all.asSequence()
            .filter { it.key != sensor.key && it.address != sensor.address && it.address !in trusted }
            .filter { it.tire?.format == own.format }
            .filter { abs(it.firstSeen - sensor.firstSeen) <= SET_WINDOW_MS }
            .filter { it.lastSeen >= sensor.firstSeen && it.firstSeen <= sensor.lastSeen }
            .sortedBy { abs(it.firstSeen - sensor.firstSeen) }
            .toList()
        val picked = ArrayList<Detection>(MAX_SET).apply { add(sensor) }
        for (d in others) {
            if (picked.size >= MAX_SET) break
            val wheel = d.tire?.wheel
            if (wheel != null && !wheels.add(wheel)) continue
            picked.add(d)
        }
        return picked.sortedWith(compareBy<Detection> { it.tire?.wheel ?: Int.MAX_VALUE }.thenBy { it.address })
    }

    /** What a sensor's ID reads as when the advertisement gave no address. */
    const val UNKNOWN_ID = "unknown"

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun u32le(d: ByteArray, i: Int): Long =
        (u8(d, i).toLong()) or (u8(d, i + 1).toLong() shl 8) or (u8(d, i + 2).toLong() shl 16) or (u8(d, i + 3).toLong() shl 24)

    private fun s32le(d: ByteArray, i: Int): Int = u32le(d, i).toInt()

    private fun hex(d: ByteArray, from: Int, count: Int): String =
        (from until from + count).joinToString(":") { String.format(Locale.US, "%02X", u8(d, it)) }
}
