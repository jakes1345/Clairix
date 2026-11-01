package app.clairix.analysis

import java.util.Locale

/**
 * What the network sweep covered, and whether its "nothing found" means
 * anything. Kept free of Android types so it can be tested.
 *
 * A sweep can finish without having been able to look: on a guest network that
 * hides devices from each other (client isolation, usual in hotels and rentals)
 * only the router answers, so an empty result says nothing about the room. On a
 * network wider than a /24 only the block around the phone is probed. On a
 * Wi-Fi that gives the phone no IPv4 address nothing can be probed at all. Each
 * of those is said in words instead of "No camera-like hosts found".
 */
internal object NetworkSweepCoverage {

    /**
     * The widest block the sweep probes, as a prefix length: 254 hosts is a sweep,
     * 65 000 is a denial of service on the venue's router.
     */
    const val SWEPT_PREFIX = 24

    /** The phone joined a Wi-Fi that gave it no IPv4 address, so there was nothing to probe. */
    const val NO_IPV4 =
        "This Wi-Fi gives the phone no IPv4 address, and the sweep needs one, so nothing on it was checked. Use the Optical and Wireless tabs."

    /** The owner pressed Cancel, or the sweep was cut short some other way. */
    const val STOPPED = "The sweep was stopped before it finished, so it proves nothing about this network."

    private const val ISOLATED_TAIL =
        "This network probably hides devices from each other (client isolation, usual on hotel and guest Wi-Fi), " +
            "so a camera on it cannot be seen from here. Use the Optical and Wireless tabs."

    /**
     * The block a sweep probes for a phone at [ip] on a network of [networkPrefix].
     * Addresses are IPv4 as signed ints, most significant byte first.
     */
    data class Range(
        /** The prefix length the Wi-Fi gave the phone. */
        val networkPrefix: Int,
        /** The prefix actually swept: the network's own, or [SWEPT_PREFIX] when the network is wider. */
        val sweptPrefix: Int,
        /** First address of the swept block. */
        val base: Int,
        /** The addresses probed: every host address in the block except the phone's own. */
        val hosts: List<Int>
    ) {
        /** "192.168.1.0/24": what was swept. */
        val subnet: String get() = "${ipString(base)}/$sweptPrefix"

        /** Every address on the whole network, network and broadcast addresses included. */
        val networkSize: Long get() = 1L shl (32 - networkPrefix)

        /** True when the network is wider than the block that was swept. */
        val narrowed: Boolean get() = networkPrefix < sweptPrefix
    }

    fun range(ip: Int, networkPrefix: Int): Range {
        val prefix = networkPrefix.coerceIn(0, 32)
        val swept = maxOf(prefix, SWEPT_PREFIX)
        val mask = if (swept >= 32) -1 else (-1 shl (32 - swept))
        val base = ip and mask
        // swept is at least 24, so the block holds at most 256 addresses.
        val count = 1 shl (32 - swept)
        // The block's first and last addresses are the network and broadcast addresses.
        val hosts = if (count <= 2) emptyList() else (1 until count - 1).map { base + it }.filter { it != ip }
        return Range(prefix, swept, base, hosts)
    }

    /**
     * Why a finished sweep of [range] proves nothing, or null when an empty result
     * means something. [alive] holds the addresses that answered the first pass;
     * [gateway] is the default route's address, when the network has one.
     *
     * Conclusive means another device answered: more than one address, or one that
     * is not the router. Only the router, or nobody, is what an isolated network
     * looks like from inside it.
     */
    fun inconclusiveReason(range: Range, alive: Set<String>, gateway: String?): String? {
        val reasons = ArrayList<String>(2)
        val routerProbed = gateway != null && range.hosts.any { ipString(it) == gateway }
        when {
            range.hosts.isEmpty() ->
                reasons += "This Wi-Fi gives the phone a /${range.networkPrefix} address with no neighbours, so there is nothing on it the sweep can reach. Use the Optical and Wireless tabs."
            alive.isEmpty() ->
                reasons += (if (routerProbed) "Nothing on this network answered, not even the router. " else "Nothing on this network answered. ") + ISOLATED_TAIL
            alive.size == 1 && gateway != null && gateway in alive ->
                reasons += "Only the router answered. $ISOLATED_TAIL"
        }
        if (range.narrowed) {
            reasons += "This network is a /${range.networkPrefix} (${grouped(range.networkSize)} addresses); " +
                "only the ${range.hosts.size} addresses in the /${range.sweptPrefix} around your phone (${range.subnet}) were checked."
        }
        return if (reasons.isEmpty()) null else reasons.joinToString(" ")
    }

    /** What the Network tab says when there are no findings to list, for sweep state [p]. */
    fun emptyText(p: NetworkScanProgress): String = when {
        p.running -> "Sweeping…"
        p.inconclusiveReason != null -> "Sweep inconclusive"
        p.lastScanTs == 0L -> "Not swept yet"
        else -> "No camera-like hosts found on the last sweep"
    }

    fun ipToInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    fun ipString(i: Int): String = "${(i ushr 24) and 255}.${(i ushr 16) and 255}.${(i ushr 8) and 255}.${i and 255}"

    private fun grouped(n: Long): String = String.format(Locale.US, "%,d", n)
}
