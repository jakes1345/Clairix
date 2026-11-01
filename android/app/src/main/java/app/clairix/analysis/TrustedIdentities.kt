package app.clairix.analysis

import app.clairix.detect.CadenceFingerprinter

/**
 * Whether a packet comes from one of the owner's own devices.
 *
 * Trust is given to an address, which is all the owner ever sees ([app.clairix.TrustBook]).
 * A device that rotates its address keeps that trust only across a change the scanner
 * can prove is the same unit: a [CadenceFingerprinter.Match.certain] stitch of the new
 * address onto one the owner already trusts. [check] then reports the new address as
 * [Check.learned], to be added to the same trusted device, and the packet is not
 * observed, alerted on or asked over DULT.
 *
 * Every weaker link is left out on purpose. The identity resolver joins two addresses
 * whose coarse fingerprints match, and every AirTag fingerprints alike; a cadence
 * stitch between two units of one product ([CadenceFingerprinter.Match.indistinguishable])
 * may have swapped them. Either can join a tag planted in the car to the owner's own
 * tag left at the door, and carrying trust across such a link would silence the
 * planted tag for the rest of the day. Such an address stays a stranger, watched like
 * any other, and the owner can mark it again if it is theirs.
 */
object TrustedIdentities {

    data class Check(
        /** The packet is from one of the owner's own devices. */
        val trusted: Boolean,
        /** An address the owner did not mark but the scanner proved is a device they did. */
        val learned: String? = null,
        /** The trusted address it was proved to follow, to add [learned] to. */
        val knownAddress: String? = null
    )

    private val TRUSTED = Check(trusted = true)
    private val NOT_TRUSTED = Check(trusted = false)

    /**
     * The address [stitch] proves the packet's address follows, when the proof is good
     * enough to carry trust: a certain cadence stitch onto the logical device [key] the
     * scanner filed the packet under. Null for no stitch or a weaker one.
     */
    fun provenPredecessor(stitch: CadenceFingerprinter.Match?, key: String): String? =
        stitch?.takeIf { it.certain && !it.indistinguishable && it.logicalId == key }?.from

    /**
     * Whether a packet from [address] is the owner's; [trusted] is every trusted address
     * and [provenFrom] the [provenPredecessor] of its address, if any.
     */
    fun check(address: String, trusted: Set<String>, provenFrom: String?): Check = when {
        address in trusted -> TRUSTED
        provenFrom != null && provenFrom in trusted -> Check(trusted = true, learned = address, knownAddress = provenFrom)
        else -> NOT_TRUSTED
    }
}
