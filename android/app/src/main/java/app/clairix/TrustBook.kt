package app.clairix

import app.clairix.detect.Signatures
import org.json.JSONArray
import org.json.JSONObject

/**
 * The devices the owner has marked as theirs ("Mark as mine").
 *
 * A device is a set of addresses rather than one. A tag or a pair of earbuds that
 * changes its Bluetooth address is still the same device, and the scanner, which
 * links those changes to one logical device ([app.clairix.analysis.TrustedIdentities]),
 * adds each new address to the device it belongs to ([extend]). Taking trust back
 * removes the device with every address it was known by ([untrust]).
 *
 * A device whose address rotates by design is trusted for [ROTATING_TRUST_MS] at a
 * time and then asked about again ([expire]): the link between two of its addresses
 * rests on timing and radio cadence, and two units of one product look alike, so
 * trust that ran on for ever could end up on somebody else's tag. A device with a
 * fixed address (a car, headphones, a watch) stays trusted until the owner takes it back.
 *
 * Immutable: every change returns a new book, which [Registry] publishes and saves.
 */
internal class TrustBook private constructor(val devices: List<Device>) {

    data class Device(
        /** Every address it has been heard under since it was trusted, the first one first. */
        val addresses: List<String>,
        /** When the owner marked it. */
        val since: Long,
        /** When the trust lapses, or 0 for never. */
        val until: Long = 0L,
        /** What it was called when it was marked, for the Settings list. */
        val label: String? = null
    )

    /** Every trusted address, of every device. */
    val addresses: Set<String> = devices.flatMapTo(HashSet()) { it.addresses }

    /** The device [address] belongs to, if it is trusted. */
    fun device(address: String): Device? = devices.firstOrNull { address in it.addresses }

    /**
     * Marks [address] as the owner's: for a day when the device [rotating]ly changes
     * its address, for good otherwise. Marking an address already trusted starts its
     * device afresh from this address.
     */
    fun trust(address: String, label: String?, rotating: Boolean, now: Long): TrustBook {
        val rest = devices.filterNot { address in it.addresses }
        val until = if (rotating) now + ROTATING_TRUST_MS else 0L
        return TrustBook(rest + Device(listOf(address), now, until, label))
    }

    /**
     * Adds [address] to the trusted device that holds [knownAddress], keeping that
     * device's expiry: a linked rotation does not renew the day. Unchanged when no
     * device holds [knownAddress] or [address] is trusted already.
     */
    fun extend(address: String, knownAddress: String): TrustBook {
        if (address in addresses) return this
        val device = device(knownAddress) ?: return this
        val grown = device.copy(addresses = (device.addresses + address).takeLast(MAX_ADDRESSES))
        return TrustBook(devices.map { if (it === device) grown else it })
    }

    /** Takes trust back from the device [address] belongs to, every address of it included. */
    fun untrust(address: String): TrustBook {
        if (address !in addresses) return this
        return TrustBook(devices.filterNot { address in it.addresses })
    }

    /** Drops every device whose trust has lapsed by [now]; the same book when none has. */
    fun expire(now: Long): TrustBook {
        val lapsed: (Device) -> Boolean = { it.until != 0L && it.until <= now }
        if (devices.none(lapsed)) return this
        return TrustBook(devices.filterNot(lapsed))
    }

    fun toJson(): String {
        val array = JSONArray()
        for (d in devices) {
            array.put(JSONObject().apply {
                put("addresses", JSONArray(d.addresses))
                put("since", d.since)
                if (d.until != 0L) put("until", d.until)
                d.label?.let { put("label", it) }
            })
        }
        return array.toString()
    }

    companion object {
        /** How long a device that rotates its address stays trusted before Clairix asks again. */
        const val ROTATING_TRUST_MS = 24 * 3600_000L

        /** Addresses remembered per device: far more than a day's rotations, a guard against a runaway. */
        const val MAX_ADDRESSES = 32

        val EMPTY = TrustBook(emptyList())

        /**
         * Whether marking [d] as the owner's should hold for a day at a time: a tracker
         * type that rotates its address by design, a device the scanner has already seen
         * change address, or one the platform says uses a private (changing) address.
         */
        fun rotating(d: Detection): Boolean =
            Signatures.rotates(d.tracker) || d.rotations > 0 ||
                d.addressKind == "resolvable private (rotates)" || d.addressKind == "non-resolvable private"

        /**
         * The book saved as [json], plus any address in [legacy] (the plain address set
         * every version saves) that it does not hold, as a device trusted for good:
         * what an earlier version, which knew only addresses, left behind.
         */
        fun from(json: String?, legacy: Set<String>): TrustBook {
            val devices = ArrayList<Device>()
            if (json != null) runCatching {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val list = o.optJSONArray("addresses") ?: continue
                    val addresses = (0 until list.length()).mapNotNull { list.optString(it, null)?.takeIf(String::isNotBlank) }
                    if (addresses.isEmpty()) continue
                    devices.add(
                        Device(
                            addresses = addresses,
                            since = o.optLong("since", 0L),
                            until = o.optLong("until", 0L),
                            label = if (o.has("label")) o.optString("label") else null
                        )
                    )
                }
            }
            val known = devices.flatMapTo(HashSet()) { it.addresses }
            for (address in legacy) if (address !in known) devices.add(Device(listOf(address), since = 0L))
            return TrustBook(devices)
        }
    }
}
