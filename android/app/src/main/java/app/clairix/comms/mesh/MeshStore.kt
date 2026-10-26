package app.clairix.comms.mesh

import app.clairix.Store
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The bundles this phone is carrying, its own and other people's, sealed on disk like
 * every other store. Bounded by count, age and hops; the oldest go first when full.
 * Also remembers which of the owner's messages have already been put into the mesh,
 * so a message retried by the outbox is not sealed into a second, third, fourth bundle.
 */
class MeshStore(private val store: Store) {

    companion object {
        /** Bundles one tag may have here: one pair of people cannot fill the store, nor a stranger flood it under one tag. */
        const val MAX_PER_TAG = 50
    }

    private val lock = Any()
    private val bundles = LinkedHashMap<String, MeshProtocol.Bundle>()
    private val queued = HashMap<String, Long>()
    /** Bundles this phone has delivered to its owner, so another copy is not handed over again. */
    private val delivered = LinkedHashMap<String, Long>()
    /** The owner's own bundles: never pushed out to make room for what strangers hand over. */
    private val own = HashSet<String>()

    init { synchronized(lock) { load() } }

    val size: Int get() = synchronized(lock) { bundles.size }

    /**
     * Adds [b] unless it is known, expired, too big or has travelled too far, or
     * its tag already has [MAX_PER_TAG] bundles here. Returns true when it was new.
     * A bundle already handed to the owner is carried on like any other.
     */
    fun add(b: MeshProtocol.Bundle, now: Long): Boolean = synchronized(lock) {
        if (b.data.size > MeshProtocol.MAX_ENVELOPE || b.hops > MeshProtocol.MAX_HOPS) return false
        if (now - b.createdMs > MeshProtocol.TTL_MS || b.createdMs - now > 3_600_000L) return false
        val key = b.idHex
        if (key in bundles) return false
        if (bundles.values.count { it.tag.contentEquals(b.tag) } >= MAX_PER_TAG) return false
        bundles[key] = b
        evict()
        save()
        true
    }

    /** Over [MeshProtocol.MAX_STORE]: the oldest carried bundle goes, never one of the owner's own. */
    private fun evict() {
        while (bundles.size > MeshProtocol.MAX_STORE) {
            val victim = bundles.keys.firstOrNull { it !in own } ?: break
            bundles.remove(victim)
        }
    }

    fun ids(now: Long): List<ByteArray> = synchronized(lock) { prune(now); bundles.values.map { it.id } }

    /**
     * What a hello should list: what this phone carries, and nothing else. A
     * bundle handed to the owner stays among those carried and is passed on like
     * the rest, so no peer can tell which bundles were for this phone by seeing
     * one listed and never sent.
     */
    fun knownIds(now: Long): List<ByteArray> = synchronized(lock) {
        prune(now)
        bundles.values.map { it.id }.take(MeshProtocol.MAX_STORE)
    }

    /** What this phone holds that a peer holding [theirs] lacks, with one more hop on each. */
    fun missingFrom(theirs: Collection<ByteArray>, now: Long): List<MeshProtocol.Bundle> = synchronized(lock) {
        prune(now)
        val have = theirs.map { MeshProtocol.hex(it) }.toSet()
        bundles.values.filter { it.idHex !in have && it.hops < MeshProtocol.MAX_HOPS }.map { it.copy(hops = it.hops + 1) }
    }

    /**
     * Marks a bundle as handed to the owner: kept out of the store for the TTL, so copies
     * stop here. Returns false when it already had been, so a second copy is not handed over.
     */
    fun markDelivered(b: MeshProtocol.Bundle, now: Long): Boolean = synchronized(lock) {
        // Carried on either way, as if it were anyone's.
        if (b.idHex !in bundles) add(b, now)
        if (b.idHex in delivered) return false
        delivered[b.idHex] = now
        while (delivered.size > MeshProtocol.MAX_STORE) delivered.remove(delivered.keys.first())
        save()
        true
    }

    /** Whether the owner's message [id] to [peer] has already been put into the mesh. */
    fun isQueued(peer: String, id: String): Boolean = synchronized(lock) { "$peer/$id" in queued }

    /** Adds the owner's own bundle for message [id] to [peer], once. Returns false when it was already there. */
    fun addOwn(peer: String, id: String, b: MeshProtocol.Bundle, now: Long): Boolean = synchronized(lock) {
        val key = "$peer/$id"
        if (key in queued) return false
        queued[key] = now
        bundles[b.idHex] = b
        own += b.idHex
        evict()
        save()
        true
    }

    fun clear() = synchronized(lock) {
        bundles.clear(); queued.clear(); delivered.clear(); own.clear()
        store.replace(JSONObject())
    }

    private fun prune(now: Long) {
        val before = bundles.size + queued.size + delivered.size
        bundles.values.removeAll { now - it.createdMs > MeshProtocol.TTL_MS }
        own.retainAll(bundles.keys)
        queued.values.removeAll { now - it > MeshProtocol.TTL_MS }
        delivered.values.removeAll { now - it > MeshProtocol.TTL_MS }
        if (bundles.size + queued.size + delivered.size != before) save()
    }

    private fun load() {
        val root = store.load { JSONObject() }
        val b64 = Base64.getDecoder()
        root.optJSONArray("bundles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val data = runCatching { b64.decode(o.getString("d")) }.getOrNull() ?: continue
                val tag = runCatching { b64.decode(o.getString("t")) }.getOrNull() ?: continue
                val b = MeshProtocol.Bundle(MeshProtocol.idOf(data), tag, o.optInt("h"), o.optLong("c"), data)
                bundles[b.idHex] = b
            }
        }
        root.optJSONObject("queued")?.let { q -> q.keys().forEach { k -> queued[k] = q.optLong(k) } }
        root.optJSONObject("delivered")?.let { q -> q.keys().forEach { k -> delivered[k] = q.optLong(k) } }
        root.optJSONArray("own")?.let { arr -> for (i in 0 until arr.length()) own += arr.optString(i) }
        own.retainAll(bundles.keys)
    }

    private fun save() {
        val b64 = Base64.getEncoder()
        val arr = JSONArray()
        for (b in bundles.values) arr.put(JSONObject().put("d", b64.encodeToString(b.data)).put("t", b64.encodeToString(b.tag)).put("h", b.hops).put("c", b.createdMs))
        val root = JSONObject().put("bundles", arr)
            .put("queued", JSONObject().also { o -> queued.forEach { (k, v) -> o.put(k, v) } })
            .put("delivered", JSONObject().also { o -> delivered.forEach { (k, v) -> o.put(k, v) } })
            .put("own", JSONArray(own.toList()))
        store.replace(root)
    }
}
