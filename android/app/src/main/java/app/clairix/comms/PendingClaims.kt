package app.clairix.comms

import org.json.JSONObject

/**
 * The claim ticket of the one-time key each peer's newest session was built
 * on, until the relay has accepted an envelope that presents it.
 *
 * The relay spends a claimed one-time key only when a send presents its
 * ticket; a ticket it never sees lets the key go back into the pool, where it
 * could be handed to someone else while this phone's session still uses it.
 * So the ticket is kept per peer, on disk, and presented on every envelope to
 * that peer until one is accepted: a send that failed and was retried, a
 * resync that could not go at once, or a process that died in between all
 * still deliver it. A newer session for the same peer replaces it (the older
 * session never reached the relay, so its key was never used).
 *
 * Pure Kotlin and org.json; [StoredPendingClaims] persists it in the app.
 */
open class PendingClaims {

    protected data class Entry(val claim: String, val atMs: Long)

    protected val entries = LinkedHashMap<String, Entry>()

    /** Called after every change, under the book's lock. */
    protected open fun changed() {}

    /** The ticket for [peer]'s newest session, or (with null) none: a session on the fallback key has no ticket. */
    @Synchronized
    fun put(peer: String, claim: String?, nowMs: Long) {
        if (claim == null || !TICKET.matches(claim)) entries.remove(peer) else entries[peer] = Entry(claim, nowMs)
        prune(nowMs)
        changed()
    }

    /** The ticket to present on the next envelope to [peer], if one is still waiting. */
    @Synchronized
    fun get(peer: String, nowMs: Long): String? {
        if (prune(nowMs)) changed()
        return entries[peer]?.claim
    }

    /** The relay accepted an envelope to [peer] that presented [claim]: it is spent. */
    @Synchronized
    fun accepted(peer: String, claim: String) {
        if (entries[peer]?.claim == claim) {
            entries.remove(peer)
            changed()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        changed()
    }

    @Synchronized
    fun encode(): String {
        val o = JSONObject()
        for ((peer, e) in entries) o.put(peer, JSONObject().put("c", e.claim).put("t", e.atMs))
        return o.toString()
    }

    @Synchronized
    fun load(json: String) {
        entries.clear()
        runCatching {
            val o = JSONObject(json)
            for (peer in o.keys()) {
                val e = o.optJSONObject(peer) ?: continue
                val claim = e.optString("c")
                if (TICKET.matches(claim)) entries[peer] = Entry(claim, e.optLong("t", 0L))
            }
        }
    }

    /** Drops tickets older than the relay remembers them; true when any went. */
    private fun prune(nowMs: Long): Boolean {
        val before = entries.size
        entries.entries.removeAll { nowMs - it.value.atMs > MAX_AGE_MS }
        while (entries.size > MAX_PEERS) entries.remove(entries.keys.first())
        return entries.size != before
    }

    companion object {
        val TICKET = Regex("^[A-Za-z0-9_-]{43}$")
        /** The relay keeps a lapsed ticket this long (comms-logic.ts OTK_LAPSED_TICKET_TTL_MS). */
        const val MAX_AGE_MS = 30L * 24 * 3600_000L
        const val MAX_PEERS = 500
    }
}
