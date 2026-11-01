package app.clairix.analysis

/**
 * Which logical devices in this scanner run belong to the owner.
 *
 * Trust is given to an address, which is all the owner ever sees ([app.clairix.TrustBook]),
 * but a device that rotates its address is still one device, and the scanner knows it
 * by one logical key across every change it links (the identity resolver's payload
 * match, or the cadence fingerprinter's stitch). So the key behind a trusted address
 * is trusted too, and an address the scanner later links to that key is the owner's
 * device under a new name: [check] reports it as [Check.learned], to be added to the
 * same trusted device, and the packet is not observed, alerted on or asked over DULT.
 *
 * Trust taken back (or lapsed) removes every address of the device, and the key goes
 * with it on its next packet. Keys mean something only within one run (the resolver
 * numbers them afresh), so nothing here is saved; the next run learns them again from
 * the trusted addresses. Thread-safe.
 */
class TrustedIdentities {

    data class Check(
        /** The packet is from one of the owner's own devices. */
        val trusted: Boolean,
        /** An address the owner did not mark but the scanner linked to a device they did. */
        val learned: String? = null,
        /** An address of that device the owner's trust already covers, to add [learned] to. */
        val knownAddress: String? = null
    )

    /** Logical key to an address of that device the owner trusts. */
    private val keys = HashMap<String, String>()

    /** Whether a packet from [address], which the scanner resolved to [key], is the owner's; [trusted] is every trusted address. */
    @Synchronized
    fun check(key: String, address: String, trusted: Set<String>): Check {
        if (address in trusted) {
            keys[key] = address
            return TRUSTED
        }
        val known = keys[key] ?: return NOT_TRUSTED
        if (known !in trusted) {
            // Taken back, or lapsed, since: the key is a stranger again.
            keys.remove(key)
            return NOT_TRUSTED
        }
        return Check(trusted = true, learned = address, knownAddress = known)
    }

    /** Forgets every key. Used by "clear all data". */
    @Synchronized
    fun clear() = keys.clear()

    private companion object {
        val TRUSTED = Check(trusted = true)
        val NOT_TRUSTED = Check(trusted = false)
    }
}
