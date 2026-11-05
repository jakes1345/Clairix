package app.clairix.analysis

import java.security.MessageDigest
import java.util.Locale

/**
 * The timeline's hash chain ([EventLog]): every entry, in the order it was written, stores the
 * SHA-256 of the entry written before it ([Link.prev]) and its own hash over that and its canonical
 * content ([hash]), so changing, removing or reordering an entry after it was written breaks the
 * chain where it happened ([verify]). The evidence report carries the newest hash (the head) and the
 * result, so whoever holds a report can later tell whether a timeline that still leads to that head
 * was altered after the export.
 *
 * What it does not prove: when an entry was written. Times are the phone's clock, and the chain has
 * no secret in it, so someone in control of the phone could rewrite the whole timeline and compute
 * a fresh chain before an export. It makes changes after an export detectable, not the phone honest.
 *
 * Entries also leave the timeline for ordinary reasons: the retention the owner chose and the cap of
 * 500. Each run of entries removed that way is recorded as a [Bridge] from the hash before the run to
 * the last hash in it, with how many it held, so verification can tell those gaps from an entry
 * removed by hand. Entries written before the chain existed are chained once, oldest first, from a
 * fixed starting value ([GENESIS]); nothing is lost.
 *
 * Pure: tested on the JVM.
 */
object TimelineChain {

    /** Where every chain starts: the SHA-256 of a fixed phrase, so anyone can recompute it. */
    val GENESIS: String = sha256Hex("clairix timeline genesis v1")

    /** An entry's place in the chain: its write order, the previous entry's hash and its own. */
    data class Link(val seq: Long, val prev: String, val hash: String)

    /**
     * An entry's content, as stored: kind and severity as the strings written, so an entry from a
     * newer build with a kind this one does not know still hashes as it was written.
     */
    data class Content(
        val id: String,
        val kind: String,
        val ts: Long,
        val title: String,
        val detail: String,
        val severity: String,
        val lat: Double?,
        val lon: Double?,
        val attachment: String?,
    )

    /** A run of [removed] entries the retention or the cap took, ending at the hash it is keyed by and starting after [from]. */
    data class Bridge(val from: String, val removed: Int)

    /** What [verify] found. */
    data class Verification(
        val entries: Int,
        val head: String,
        /** Problems in the chain, in words; empty when it is whole. */
        val problems: List<String>,
        /** Entries the retention or the cap removed since they were written, as recorded. */
        val removedByRetention: Int,
    ) {
        val intact: Boolean get() = problems.isEmpty()
    }

    /**
     * Content as one string with every field's length before it, so no choice of text in one field
     * can make two different entries read the same.
     */
    fun canonical(seq: Long, c: Content): String = buildString {
        fun field(name: String, value: String?) {
            append(name).append('=')
            if (value == null) append('-') else append(value.length).append(':').append(value)
            append('\n')
        }
        field("seq", seq.toString())
        field("id", c.id)
        field("kind", c.kind)
        field("ts", c.ts.toString())
        field("title", c.title)
        field("detail", c.detail)
        field("severity", c.severity)
        field("lat", c.lat?.toString())
        field("lon", c.lon?.toString())
        field("attachment", c.attachment)
    }

    /** The hash of an entry at [seq] with [content], after [prev]: SHA-256 over "prev\n" and [canonical], lower-case hex. */
    fun hash(prev: String, seq: Long, content: Content): String = sha256Hex(prev + "\n" + canonical(seq, content))

    /**
     * The bridges after removing the entries of [chain] (every entry, in write order) whose seq is in
     * [removed], from the [bridges] already recorded. Each run of removed entries becomes one bridge,
     * merged with a bridge it touches on either side, so there is never more than one hop between
     * two entries that remain.
     */
    fun bridged(chain: List<Link>, removed: Set<Long>, bridges: Map<String, Bridge>): Map<String, Bridge> {
        if (removed.isEmpty()) return bridges
        val out = HashMap(bridges)
        var start: String? = null
        var end = ""
        var count = 0
        fun close() {
            val from = start ?: return
            val onward = out.entries.firstOrNull { it.value.from == end }
            if (onward != null) out[onward.key] = Bridge(from, count + onward.value.removed) else out[end] = Bridge(from, count)
            start = null
            count = 0
        }
        for (link in chain) {
            if (link.seq !in removed) {
                close()
                continue
            }
            if (start == null) {
                val before = out.remove(link.prev)
                start = before?.from ?: link.prev
                count = before?.removed ?: 0
            }
            count++
            end = link.hash
        }
        close()
        return out
    }

    /**
     * Checks [chain] (every entry with its content, in any order; sorted here by seq) against the
     * recorded [bridges] and [head], the hash of the last entry written. Finds an entry whose
     * content no longer matches its hash, a link that does not lead from the entry before it (an
     * entry removed or moved), a repeated seq, an entry with no link at all, and a head that is not
     * where the chain ends.
     */
    fun verify(chain: List<Pair<Link?, Content>>, bridges: Map<String, Bridge>, head: String): Verification {
        val problems = ArrayList<String>()
        val unlinked = chain.count { it.first == null }
        if (unlinked > 0) problems += "$unlinked ${if (unlinked == 1) "entry carries" else "entries carry"} no link: added to the file by hand, or by something other than Clairix"
        val linked = chain.mapNotNull { (l, c) -> l?.let { it to c } }.sortedBy { it.first.seq }
        var last = GENESIS
        var lastSeq = Long.MIN_VALUE
        var removed = 0
        for ((link, content) in linked) {
            val at = "entry #${link.seq} (${content.id})"
            if (link.seq == lastSeq) problems += "$at shares its place in the order with another entry"
            if (hash(link.prev, link.seq, content) != link.hash) problems += "$at was changed after it was written"
            if (link.prev != last) {
                val bridge = bridges[link.prev]
                if (bridge != null && bridge.from == last) removed += bridge.removed
                else problems += "the entry written before $at is missing or out of order"
            }
            last = link.hash
            lastSeq = link.seq
        }
        if (head != last) {
            val bridge = bridges[head]
            if (bridge != null && bridge.from == last) removed += bridge.removed
            else problems += "the newest entries are missing, or the record of the newest entry was changed"
        }
        return Verification(chain.size, head, problems, removed)
    }

    /** What the report says the chain proves, and what it does not. */
    const val WHAT_IT_PROVES = "Each timeline entry stores the SHA-256 of the entry written before it, so an entry changed, " +
        "removed or moved after it was written breaks the chain. With this head, a later copy of the timeline (or a later " +
        "report that lists the same entries) can be checked for changes made after this export. It does not prove when " +
        "an entry was written: times are the phone's clock, and the chain holds no secret, so someone in control of the " +
        "phone could have rewritten the whole timeline before this export. Entries removed by the retention or the cap " +
        "are recorded as such; entries written before Clairix kept the chain start it from a fixed value."

    internal fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
}
