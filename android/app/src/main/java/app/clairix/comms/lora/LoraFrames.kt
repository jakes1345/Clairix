package app.clairix.comms.lora

import app.clairix.comms.mesh.MeshProtocol
import java.nio.ByteBuffer

/**
 * Mesh bundles cut to fit LoRa packets, and put back together.
 *
 * A Meshtastic packet holds at most [MeshtasticProto.DATA_PAYLOAD_LEN] bytes, so a
 * bundle goes out as up to [MAX_FRAGMENTS] packets. Each starts with a 12-byte header:
 *
 *     0     magic 0xC7
 *     1     version (1)
 *     2-9   the first 8 bytes of the bundle id (sha-256 of the envelope)
 *     10    fragment index, from 0
 *     11    fragment count
 *
 * The fragments' bodies, joined in order, are the bundle: its routing tag (16
 * bytes), hops (1), the hour it was dated to, in hours since 1970 (4), and the
 * envelope. The receiver recomputes the id from the envelope and drops the bundle
 * unless it matches the header, so a fragment from another bundle that happened to
 * share the 8-byte prefix can't corrupt one.
 *
 * Nothing here is secret beyond what the Bluetooth mesh already shows: the envelope
 * is sealed end to end, and the tag only means something to its two people.
 */
object LoraFrames {

    const val MAGIC: Byte = 0xC7.toByte()
    const val VERSION: Byte = 1
    const val HEADER = 12
    const val PREFIX = 8
    const val MAX_FRAGMENTS = 8
    const val BODY_PER_FRAGMENT = MeshtasticProto.DATA_PAYLOAD_LEN - HEADER
    private const val META = MeshProtocol.TAG_LEN + 1 + 4
    private const val HOUR_MS = 3_600_000L

    /** The largest envelope that fits in [MAX_FRAGMENTS]; texts and SOS alerts do, media does not. */
    const val MAX_ENVELOPE = MAX_FRAGMENTS * BODY_PER_FRAGMENT - META

    /** How many packets [b] takes, or 0 when it is too big for LoRa. */
    fun fragmentCount(b: MeshProtocol.Bundle): Int {
        if (b.data.size > MAX_ENVELOPE) return 0
        return (META + b.data.size + BODY_PER_FRAGMENT - 1) / BODY_PER_FRAGMENT
    }

    /** [b] as LoRa payloads, or an empty list when it is too big. */
    fun split(b: MeshProtocol.Bundle): List<ByteArray> {
        val count = fragmentCount(b)
        if (count == 0) return emptyList()
        val body = ByteBuffer.allocate(META + b.data.size)
            .put(b.tag, 0, MeshProtocol.TAG_LEN)
            .put(b.hops.coerceIn(0, 255).toByte())
            .putInt((b.createdMs / HOUR_MS).toInt())
            .put(b.data)
            .array()
        return List(count) { i ->
            val from = i * BODY_PER_FRAGMENT
            val n = minOf(BODY_PER_FRAGMENT, body.size - from)
            ByteBuffer.allocate(HEADER + n)
                .put(MAGIC).put(VERSION).put(b.id, 0, PREFIX)
                .put(i.toByte()).put(count.toByte())
                .put(body, from, n)
                .array()
        }
    }

    /**
     * Collects fragments as they arrive, in any order and with repeats (every radio
     * in range rebroadcasts), and hands back each bundle once when its last piece
     * lands. Bounded: at most [maxPending] bundles in progress, each dropped after
     * [timeoutMs]; the oldest goes first when full. Not thread safe.
     */
    class Reassembler(
        private val maxPending: Int = 32,
        private val timeoutMs: Long = 15 * 60_000L,
        private val rememberDone: Int = 512,
    ) {
        private class Pending(val count: Int, val startedMs: Long) {
            val parts = arrayOfNulls<ByteArray>(count)
            var have = 0
        }

        private val pending = LinkedHashMap<String, Pending>()
        private val done = object : LinkedHashMap<String, Unit>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > rememberDone
        }

        val inProgress: Int get() = pending.size

        /** Adds one LoRa payload; returns the bundle it completes, if it does. */
        fun add(payload: ByteArray, now: Long): MeshProtocol.Bundle? {
            if (payload.size <= HEADER || payload[0] != MAGIC || payload[1] != VERSION) return null
            val index = payload[10].toInt() and 0xFF
            val count = payload[11].toInt() and 0xFF
            if (count !in 1..MAX_FRAGMENTS || index >= count) return null
            if (payload.size - HEADER > BODY_PER_FRAGMENT) return null
            val prefix = MeshProtocol.hex(payload.copyOfRange(2, 2 + PREFIX))
            if (prefix in done) return null
            expire(now)
            // Keyed by the prefix alone: Meshtastic keeps the first sender's node number
            // on rebroadcasts, and two phones bridging the same bundle send the same bytes.
            val p = pending.getOrPut(prefix) {
                while (pending.size >= maxPending) pending.remove(pending.keys.first())
                Pending(count, now)
            }
            if (p.count != count) return null
            if (p.parts[index] == null) {
                p.parts[index] = payload.copyOfRange(HEADER, payload.size)
                p.have++
            }
            if (p.have < count) return null
            pending.remove(prefix)
            done[prefix] = Unit
            return parse(prefix, p.parts.map { it!! })
        }

        private fun expire(now: Long) {
            pending.values.removeAll { now - it.startedMs > timeoutMs }
        }

        private fun parse(prefix: String, parts: List<ByteArray>): MeshProtocol.Bundle? {
            val total = parts.sumOf { it.size }
            if (total <= META) return null
            // All but the last fragment are full; anything else was assembled from two bundles.
            if (parts.dropLast(1).any { it.size != BODY_PER_FRAGMENT }) return null
            val body = ByteBuffer.allocate(total).also { buf -> parts.forEach { buf.put(it) } }.array()
            val b = ByteBuffer.wrap(body)
            val tag = ByteArray(MeshProtocol.TAG_LEN).also { b.get(it) }
            val hops = b.get().toInt() and 0xFF
            val createdMs = (b.int.toLong() and 0xFFFFFFFFL) * HOUR_MS
            val data = ByteArray(b.remaining()).also { b.get(it) }
            val id = MeshProtocol.idOf(data)
            if (MeshProtocol.hex(id.copyOf(PREFIX)) != prefix) return null
            // The LoRa crossing counts as one hop, like a Bluetooth meeting.
            return MeshProtocol.Bundle(id, tag, hops + 1, createdMs, data)
        }
    }
}
