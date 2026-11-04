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

    private val events = ArrayList<TimelineEvent>()

    init {
        val root = store.load { JSONObject().put("events", JSONArray()) }
        val arr = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            events.add(fromJson(o))
        }
        events.sortByDescending { it.ts }
    }

    @Synchronized
    fun snapshot(): List<TimelineEvent> = ArrayList(events)

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
            val recent = events.firstOrNull { it.id.startsWith("$dedupeKey@") }
            if (recent != null && event.ts - recent.ts < dedupeWindowMs) return false
        }
        // Newest first by when it happened, not by when it was written: a scanner stop
        // dated from its last heartbeat is written after events that came later.
        val at = events.indexOfFirst { it.ts <= event.ts }
        events.add(if (at < 0) events.size else at, event)
        val overflow = ArrayList<TimelineEvent>()
        while (events.size > cap) overflow.add(events.removeAt(overflowIndex()))
        persist()
        dropped(overflow)
        return true
    }

    /** Writes any pending changes, under the same lock [record] mutates the JSON under. */
    @Synchronized
    fun flush() {
        store.flush()
    }

    @Synchronized
    fun clear() {
        val gone = ArrayList(events)
        events.clear()
        // The owner's clear: the file goes even if this run could not read it.
        store.discardUnread()
        persist()
        dropped(gone)
    }

    /** The oldest event that is not kept ([isKept]), or the oldest of all when every one is. */
    private fun overflowIndex(): Int {
        val oldestOrdinary = events.indexOfLast { !isKept(it) }
        return if (oldestOrdinary >= 0) oldestOrdinary else events.size - 1
    }

    /** Deletes events before [cutoff], other than kept ones ([isKept]); returns how many. */
    @Synchronized
    fun pruneOlderThan(cutoff: Long): Int {
        val gone = events.filter { it.ts < cutoff && !isKept(it) }
        if (gone.isEmpty()) return 0
        events.removeAll { it.ts < cutoff && !isKept(it) }
        persist()
        dropped(gone)
        return gone.size
    }

    private fun dropped(gone: List<TimelineEvent>) {
        if (gone.isNotEmpty()) runCatching { onDropped(gone) }
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in events) arr.put(toJson(e))
        store.json.put("events", arr)
        // Mutating the JSON in place leaves the store unaware it has changed, and
        // flush() short-circuits when it is not dirty — so without this the timeline
        // was rebuilt in memory on every event and never once written to disk.
        store.markDirty()
        store.flush()
    }

    private fun toJson(e: TimelineEvent) = JSONObject().apply {
        put("id", e.id)
        put("kind", e.kind.name)
        put("ts", e.ts)
        put("title", e.title)
        put("detail", e.detail)
        put("severity", e.severity.name)
        if (e.lat != null) put("lat", e.lat)
        if (e.lon != null) put("lon", e.lon)
        if (e.attachment != null) put("attachment", e.attachment)
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
