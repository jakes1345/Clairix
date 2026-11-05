package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * A durable, newest-first log of the events worth remembering: a device
 * confirmed following, an IMSI-catcher finding, a scanned NFC tag, and when
 * scanning started and stopped. This is what turns "something is wrong right
 * now" into "this has happened three times this week, here and here" — the
 * record you can actually act on or hand to someone.
 *
 * It is capped so the file cannot grow without bound, dropping the oldest
 * entries first. What the owner saved on purpose is not aged out by the
 * retention and is the last to go at the cap ([isKept]): an app's evidence
 * saved before it was removed ([KEPT]), and a photo kept from the camera sweep
 * (an event with an [TimelineEvent.attachment]), neither of which can be
 * collected again. It leaves only with [clear] ("Clear all data", a wipe), or
 * at the cap once nothing else is left.
 *
 * Every entry is hash-chained to the one written before it ([TimelineChain]), so a
 * change, removal or reordering after it was written shows in [verify]; the
 * retention's and the cap's removals are recorded as bridges, and a timeline
 * stored before the chain existed is chained once on load, oldest first, from
 * [TimelineChain.GENESIS].
 */
class EventLog(
    private val store: Store,
    private val cap: Int = 500,
    /**
     * Told about every event that leaves the log, by [clear], [pruneOlderThan] or
     * the cap, so a file kept with one ([TimelineEvent.attachment]) goes with it.
     * Called under the log's lock.
     */
    private val onDropped: (List<TimelineEvent>) -> Unit = {}
) {

    /** An event with its place in the chain; [link] is null for an entry stored without one. */
    private class Entry(val event: TimelineEvent, val content: TimelineChain.Content, val link: TimelineChain.Link?)

    /** Newest first by when it happened, as [snapshot] gives them. */
    private val events = ArrayList<Entry>()

    /** The hash of the last entry written, which the next one links to. */
    private var head = TimelineChain.GENESIS
    private var nextSeq = 1L
    private var bridges: Map<String, TimelineChain.Bridge> = emptyMap()

    init {
        val root = store.load { JSONObject().put("events", JSONArray()) }
        val arr = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            events.add(entryFromJson(o))
        }
        events.sortByDescending { it.event.ts }
        val chain = root.optJSONObject("chain")
        if (chain != null) {
            head = chain.optString("head", TimelineChain.GENESIS)
            nextSeq = chain.optLong("next_seq", 1L)
            bridges = bridgesFromJson(chain.optJSONArray("bridges"))
        }
        if (events.isNotEmpty() && events.none { it.link != null }) migrate()
    }

    /**
     * Chains a timeline stored before the chain existed: oldest first by time (ties in the order
     * stored), from [TimelineChain.GENESIS], and writes it straight back. Nothing is dropped.
     */
    private fun migrate() {
        head = TimelineChain.GENESIS
        nextSeq = 1L
        bridges = emptyMap()
        val oldestFirst = events.withIndex().sortedWith(compareBy<IndexedValue<Entry>> { it.value.event.ts }.thenByDescending { it.index })
        val chained = HashMap<Entry, Entry>()
        for ((_, e) in oldestFirst) chained[e] = link(e.event, e.content)
        for (i in events.indices) events[i] = chained.getValue(events[i])
        persist()
    }

    /** [event] as the next entry in the chain. */
    private fun link(event: TimelineEvent, content: TimelineChain.Content = contentOf(event)): Entry {
        val seq = nextSeq++
        val hash = TimelineChain.hash(head, seq, content)
        val entry = Entry(event, content, TimelineChain.Link(seq, head, hash))
        head = hash
        return entry
    }

    /** Checks the chain as it stands ([TimelineChain.verify]). */
    @Synchronized
    fun verify(): TimelineChain.Verification =
        TimelineChain.verify(events.map { it.link to it.content }, bridges, head)

    /** Every event, newest first, with its place in the chain (null for an entry stored without one). */
    @Synchronized
    fun chained(): List<Pair<TimelineEvent, TimelineChain.Link?>> = events.map { it.event to it.link }

    /** [chained] and [verify] taken together, so nothing recorded in between can set them apart. */
    @Synchronized
    fun chainedAndVerified(): Pair<List<Pair<TimelineEvent, TimelineChain.Link?>>, TimelineChain.Verification> = chained() to verify()

    @Synchronized
    fun snapshot(): List<TimelineEvent> = events.map { it.event }

    /**
     * Records an event. [dedupeKey], when given, suppresses a repeat of the same
     * logical event within [dedupeWindowMs] — so a device that stays "following"
     * across many scan cycles produces one timeline entry, not hundreds.
     */
    @Synchronized
    fun record(
        event: TimelineEvent,
        dedupeKey: String? = null,
        dedupeWindowMs: Long = 30 * 60 * 1000L
    ): Boolean {
        if (dedupeKey != null) {
            val recent = events.firstOrNull { it.event.id.startsWith("$dedupeKey@") }
            if (recent != null && event.ts - recent.event.ts < dedupeWindowMs) return false
        }
        // Newest first by when it happened, not by when it was written: a scanner stop
        // dated from its last heartbeat is written after events that came later. The chain
        // follows the order written.
        val at = events.indexOfFirst { it.event.ts <= event.ts }
        events.add(if (at < 0) events.size else at, link(event))
        val overflow = ArrayList<Entry>()
        while (events.size > cap) overflow.add(events.removeAt(overflowIndex()))
        remove(overflow)
        persist()
        dropped(overflow.map { it.event })
        return true
    }

    /** Writes any pending changes, under the same lock [record] mutates the JSON under. */
    @Synchronized
    fun flush() {
        store.flush()
    }

    @Synchronized
    fun clear() {
        val gone = events.map { it.event }
        events.clear()
        // A fresh start: nothing is left to link to.
        head = TimelineChain.GENESIS
        nextSeq = 1L
        bridges = emptyMap()
        // The owner's clear: the file goes even if this run could not read it.
        store.discardUnread()
        persist()
        dropped(gone)
    }

    /** The oldest event that is not kept ([isKept]), or the oldest of all when every one is. */
    private fun overflowIndex(): Int {
        val oldestOrdinary = events.indexOfLast { !isKept(it.event) }
        return if (oldestOrdinary >= 0) oldestOrdinary else events.size - 1
    }

    /** Deletes events before [cutoff], other than kept ones ([isKept]); returns how many. */
    @Synchronized
    fun pruneOlderThan(cutoff: Long): Int {
        val gone = events.filter { it.event.ts < cutoff && !isKept(it.event) }
        if (gone.isEmpty()) return 0
        events.removeAll(gone.toSet())
        remove(gone)
        persist()
        dropped(gone.map { it.event })
        return gone.size
    }

    /** Records [gone], already taken out of [events], as removed by the retention or the cap ([TimelineChain.bridged]). */
    private fun remove(gone: List<Entry>) {
        val seqs = gone.mapNotNullTo(HashSet()) { it.link?.seq }
        if (seqs.isEmpty()) return
        val all = (events + gone).mapNotNull { it.link }.sortedBy { it.seq }
        bridges = TimelineChain.bridged(all, seqs, bridges)
    }

    private fun dropped(gone: List<TimelineEvent>) {
        if (gone.isNotEmpty()) runCatching { onDropped(gone) }
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in events) arr.put(toJson(e))
        store.json.put("events", arr)
        store.json.put("chain", JSONObject().apply {
            put("v", 1)
            put("head", head)
            put("next_seq", nextSeq)
            put("bridges", JSONArray().also { a ->
                bridges.forEach { (to, b) -> a.put(JSONObject().put("to", to).put("from", b.from).put("removed", b.removed)) }
            })
        })
        // Mutating the JSON in place leaves the store unaware it has changed, and
        // flush() short-circuits when it is not dirty — so without this the timeline
        // was rebuilt in memory on every event and never once written to disk.
        store.markDirty()
        store.flush()
    }

    /** As stored: the content as written ([Entry.content], so an unknown kind survives) and the link. */
    private fun toJson(e: Entry) = JSONObject().apply {
        val c = e.content
        put("id", c.id)
        put("kind", c.kind)
        put("ts", c.ts)
        put("title", c.title)
        put("detail", c.detail)
        put("severity", c.severity)
        if (c.lat != null) put("lat", c.lat)
        if (c.lon != null) put("lon", c.lon)
        if (c.attachment != null) put("attachment", c.attachment)
        e.link?.let {
            put("seq", it.seq)
            put("prev", it.prev)
            put("hash", it.hash)
        }
    }

    private fun entryFromJson(o: JSONObject): Entry {
        val event = fromJson(o)
        val content = TimelineChain.Content(
            id = event.id, kind = o.optString("kind"), ts = event.ts, title = event.title, detail = event.detail,
            severity = o.optString("severity"), lat = event.lat, lon = event.lon, attachment = event.attachment,
        )
        val link = if (o.has("hash")) TimelineChain.Link(o.optLong("seq"), o.optString("prev"), o.optString("hash")) else null
        return Entry(event, content, link)
    }

    private fun bridgesFromJson(a: JSONArray?): Map<String, TimelineChain.Bridge> {
        if (a == null) return emptyMap()
        val out = HashMap<String, TimelineChain.Bridge>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out[o.optString("to")] = TimelineChain.Bridge(o.optString("from"), o.optInt("removed"))
        }
        return out
    }

    private fun fromJson(o: JSONObject) = TimelineEvent(
        id = o.optString("id"),
        // Stored by name. A kind this build does not know (one written by a newer build)
        // reads as a plain platform alert, not as a tracker following.
        kind = runCatching { EventKind.valueOf(o.optString("kind")) }.getOrDefault(EventKind.PLATFORM),
        ts = o.optLong("ts"),
        title = o.optString("title"),
        detail = o.optString("detail"),
        severity = runCatching { Severity.valueOf(o.optString("severity")) }.getOrDefault(Severity.MEDIUM),
        lat = if (o.has("lat")) o.optDouble("lat") else null,
        lon = if (o.has("lon")) o.optDouble("lon") else null,
        // Absent from every event stored before attachments existed.
        attachment = if (o.has("attachment")) o.optString("attachment").takeIf { it.isNotEmpty() } else null
    )

    companion object {
        /** [event]'s content as it will be stored and hashed. */
        internal fun contentOf(event: TimelineEvent) = TimelineChain.Content(
            id = event.id, kind = event.kind.name, ts = event.ts, title = event.title, detail = event.detail,
            severity = event.severity.name, lat = event.lat, lon = event.lon, attachment = event.attachment,
        )

        /** Kinds the owner saved on purpose, which the retention does not delete. */
        val KEPT: Set<EventKind> = setOf(EventKind.EVIDENCE)

        /**
         * Saved on purpose: a [KEPT] kind, or an event with a file attached, which only
         * "Keep a photo of this" in the camera sweep makes. The photo is the record of
         * a room that may not be visited again, so it outlasts the retention and the
         * cap's ordinary entries, as saved evidence does.
         */
        fun isKept(e: TimelineEvent): Boolean = e.kind in KEPT || e.attachment != null
    }
}
