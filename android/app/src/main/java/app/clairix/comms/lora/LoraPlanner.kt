package app.clairix.comms.lora

import app.clairix.comms.mesh.MeshProtocol

/**
 * Which carried bundles go out over LoRa, and when. Apart from the radio, so the
 * rules can be tested.
 *
 * LoRa airtime is shared by everyone on the channel and limited by law in some
 * regions (Europe's 868 MHz band allows a 1-10% duty cycle depending on the
 * sub-band; Meshtastic's firmware enforces its own limit too). A full Meshtastic
 * packet on the default LongFast preset is on the air for well over a second, so
 * the planner sends at most [budget] packets in any [windowMs]:
 *
 *  - A bundle heard over LoRa is never sent back onto LoRa by this phone: the
 *    Meshtastic radios that heard it already rebroadcast it, up to their hop limit.
 *    It is still carried, and handed on over Bluetooth like any other.
 *  - Each other bundle goes out at most [maxSends] times, [resendAfterMs] apart,
 *    in case the first went unheard.
 *  - Fewest hops first (closest to whoever wrote it), then newest; a bundle that
 *    does not fit the remaining budget waits rather than being cut.
 *  - Nothing while the radio reports its send queue full.
 */
class LoraPlanner(
    private val budget: Int = 10,
    private val windowMs: Long = 10 * 60_000L,
    private val maxSends: Int = 2,
    private val resendAfterMs: Long = 30 * 60_000L,
    private val remember: Int = 1_024,
) {
    private val heardOnLora = lru<Unit>()
    private val sends = lru<IntArray>() // [count, lastSentSeconds]
    private val sentAt = ArrayDeque<Long>()

    @Volatile var radioQueueFull: Boolean = false

    /** Marks a bundle that arrived over LoRa, so it isn't sent back onto it. */
    fun heard(b: MeshProtocol.Bundle) { synchronized(this) { heardOnLora[b.idHex] = Unit } }

    /** Packets that may still go out in the current window. */
    fun remaining(now: Long): Int = synchronized(this) {
        while (sentAt.isNotEmpty() && now - sentAt.first() >= windowMs) sentAt.removeFirst()
        budget - sentAt.size
    }

    /** The bundles to send now, out of [carried], within the budget. */
    fun pick(carried: List<MeshProtocol.Bundle>, now: Long): List<MeshProtocol.Bundle> = synchronized(this) {
        if (radioQueueFull) return emptyList()
        var left = remaining(now)
        if (left <= 0) return emptyList()
        val out = ArrayList<MeshProtocol.Bundle>()
        val due = carried.asSequence()
            .filter { it.idHex !in heardOnLora }
            .filter { it.hops < MeshProtocol.MAX_HOPS }
            .filter { LoraFrames.fragmentCount(it) > 0 }
            .filter { b ->
                val s = sends[b.idHex] ?: return@filter true
                s[0] < maxSends && now / 1000 - s[1] >= resendAfterMs / 1000
            }
            .sortedWith(compareBy<MeshProtocol.Bundle> { it.hops }.thenByDescending { it.createdMs })
        for (b in due) {
            val n = LoraFrames.fragmentCount(b)
            if (n > left) continue
            out += b
            left -= n
            if (left == 0) break
        }
        out
    }

    /** Records that [b] went out as [fragments] packets. */
    fun sent(b: MeshProtocol.Bundle, fragments: Int, now: Long) = synchronized(this) {
        val s = sends.getOrPut(b.idHex) { IntArray(2) }
        s[0]++
        s[1] = (now / 1000).toInt()
        repeat(fragments) { sentAt.addLast(now) }
    }

    private fun <V> lru() = object : LinkedHashMap<String, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > remember
    }
}
