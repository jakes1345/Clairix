package app.clairix.comms

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Single-use delivery tokens waiting to be spent on sends.
 *
 * The relay hands them out in batches over the signed channel and takes one,
 * with no identity, for every envelope (comms-worker/src/comms-logic.ts). They
 * are spent in random order, never in the order they were issued, and fetched
 * when the pool runs low rather than when a message is about to go, so the
 * relay cannot match a send to the batch request that came just before it.
 */
interface TokenPool {
    /** Removes and returns an unexpired token, or null when there is none. */
    fun take(nowMs: Long): String?
    fun add(tokens: List<String>, expiresAtMs: Long)
    /** Unexpired tokens left. */
    fun size(nowMs: Long): Int
    fun clear()
}

/**
 * The pool's logic over a plain list; [MemoryTokenPool] keeps it in memory and
 * the app's pool persists it after every change.
 */
open class MemoryTokenPool(private val random: java.util.Random = SecureRandom()) : TokenPool {

    protected data class Entry(val token: String, val expiresAtMs: Long)

    protected val entries = ArrayList<Entry>()

    /** Called after every change, under the pool's lock. */
    protected open fun changed() {}

    @Synchronized
    override fun take(nowMs: Long): String? {
        val before = entries.size
        entries.removeAll { it.expiresAtMs <= nowMs + EXPIRY_MARGIN_MS }
        if (entries.isEmpty()) {
            if (before != 0) changed()
            return null
        }
        val picked = entries.removeAt(random.nextInt(entries.size))
        changed()
        return picked.token
    }

    @Synchronized
    override fun add(tokens: List<String>, expiresAtMs: Long) {
        val known = entries.mapTo(HashSet()) { it.token }
        for (t in tokens) if (TOKEN.matches(t) && known.add(t)) entries += Entry(t, expiresAtMs)
        while (entries.size > MAX_TOKENS) entries.removeAt(0)
        changed()
    }

    @Synchronized
    override fun size(nowMs: Long): Int = entries.count { it.expiresAtMs > nowMs + EXPIRY_MARGIN_MS }

    @Synchronized
    override fun clear() {
        entries.clear()
        changed()
    }

    /** The pool as JSON, for a pool that persists it. */
    @Synchronized
    fun encode(): String {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("t", e.token).put("e", e.expiresAtMs))
        return arr.toString()
    }

    /** Replaces the pool with what [encode] wrote; anything unreadable is dropped. */
    @Synchronized
    fun load(json: String) {
        entries.clear()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t")
                if (TOKEN.matches(t)) entries += Entry(t, o.optLong("e", 0L))
            }
        }
    }

    companion object {
        /** 32 random bytes, base64url, as the relay issues them. */
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
        /** A token about to expire is not worth a request that would then be refused. */
        const val EXPIRY_MARGIN_MS = 10 * 60_000L
        const val MAX_TOKENS = 1000
    }
}
