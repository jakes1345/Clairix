package app.clairix.comms.lora

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The few Meshtastic messages Clairix needs, in protobuf wire format, written and
 * read by hand: a generated library for the whole Meshtastic schema would be most
 * of the app's protocol code for four messages.
 *
 * Field numbers are those of meshtastic/protobufs `mesh.proto`:
 *  - ToRadio:   packet = 1, want_config_id = 3
 *  - FromRadio: id = 1, packet = 2, my_info = 3, config_complete_id = 7,
 *               rebooted = 8, queueStatus = 11
 *  - MeshPacket: from = 1 (fixed32), to = 2 (fixed32), channel = 3, decoded = 4,
 *               encrypted = 5, id = 6 (fixed32), hop_limit = 9, want_ack = 10
 *  - Data:      portnum = 1, payload = 2
 *  - MyNodeInfo: my_node_num = 1;  QueueStatus: res = 1, free = 2, maxlen = 3,
 *               mesh_packet_id = 4
 *
 * The BLE service and characteristics are from the Meshtastic client API
 * (meshtastic.org/docs/development/device/client-api).
 */
object MeshtasticProto {

    val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    val TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    val FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    /** Meshtastic's broadcast node number. */
    const val BROADCAST: Int = -1 // 0xFFFFFFFF

    /** Constants.DATA_PAYLOAD_LEN: the most a Data.payload may hold. */
    const val DATA_PAYLOAD_LEN = 233

    /**
     * The port Clairix bundles travel on. 256-511 is the range portnums.proto sets
     * aside for private applications; 256 itself (PRIVATE_APP) is what every
     * experiment uses, so Clairix takes one of its own.
     */
    const val CLAIRIX_PORT = 400

    sealed interface FromRadio {
        /** A packet the radio decoded. [port] and [payload] are its Data. */
        data class Packet(val from: Int, val to: Int, val channel: Int, val id: Int, val port: Int, val payload: ByteArray) : FromRadio {
            override fun equals(other: Any?) = other is Packet && from == other.from && id == other.id && port == other.port && payload.contentEquals(other.payload)
            override fun hashCode() = 31 * (31 * from + id) + payload.contentHashCode()
        }
        data class MyInfo(val nodeNum: Int) : FromRadio
        data class ConfigComplete(val id: Int) : FromRadio
        /** How much room the radio has left for packets to send. */
        data class Queue(val res: Int, val free: Int, val maxLen: Int, val packetId: Int) : FromRadio
        data object Rebooted : FromRadio
        /** Anything else: node lists, configuration, logs, packets it could not decrypt. */
        data object Other : FromRadio
    }

    /** ToRadio{want_config_id}: asks the radio to send its state, then config_complete_id = [nonce]. */
    fun wantConfig(nonce: Int): ByteArray = Writer().varint(3, nonce.toLong() and 0xFFFFFFFFL).bytes()

    /**
     * ToRadio{packet}: [payload] on [port], to everyone on [channel], to be passed
     * on at most [hopLimit] times by the radios that hear it.
     */
    fun broadcast(payload: ByteArray, port: Int, packetId: Int, channel: Int = 0, hopLimit: Int = 3): ByteArray {
        require(payload.isNotEmpty() && payload.size <= DATA_PAYLOAD_LEN) { "payload must be 1-$DATA_PAYLOAD_LEN bytes" }
        require(hopLimit in 0..7) { "hop limit must be 0-7" }
        val data = Writer().varint(1, port.toLong()).bytes(2, payload).bytes()
        val packet = Writer()
            .fixed32(2, BROADCAST)
            .also { if (channel != 0) it.varint(3, channel.toLong()) }
            .bytes(4, data)
            .fixed32(6, packetId)
            .varint(9, hopLimit.toLong())
            .bytes()
        return Writer().bytes(1, packet).bytes()
    }

    /** Parses one FromRadio read; null for anything malformed. An empty read means the queue is empty. */
    fun parseFromRadio(bytes: ByteArray): FromRadio? = runCatching {
        var result: FromRadio = FromRadio.Other
        Reader(bytes).forEach { field, wire, r ->
            when {
                field == 2 && wire == WIRE_LEN -> result = parsePacket(r.lenBytes())
                field == 3 && wire == WIRE_LEN -> result = parseMyInfo(r.lenBytes())
                field == 7 && wire == WIRE_VARINT -> result = FromRadio.ConfigComplete(r.varint().toInt())
                field == 8 && wire == WIRE_VARINT -> { r.varint(); result = FromRadio.Rebooted }
                field == 11 && wire == WIRE_LEN -> result = parseQueue(r.lenBytes())
                else -> r.skip(wire)
            }
        }
        result
    }.getOrNull()

    private fun parsePacket(bytes: ByteArray): FromRadio {
        var from = 0; var to = 0; var channel = 0; var id = 0
        var data: Pair<Int, ByteArray?>? = null
        Reader(bytes).forEach { field, wire, r ->
            when {
                field == 1 && wire == WIRE_I32 -> from = r.fixed32()
                field == 2 && wire == WIRE_I32 -> to = r.fixed32()
                field == 3 && wire == WIRE_VARINT -> channel = r.varint().toInt()
                field == 6 && wire == WIRE_I32 -> id = r.fixed32()
                field == 4 && wire == WIRE_LEN -> data = parseData(r.lenBytes())
                else -> r.skip(wire)
            }
        }
        // No decoded Data: encrypted for a channel this radio lacks.
        val (port, payload) = data ?: return FromRadio.Other
        return if (payload == null) FromRadio.Other else FromRadio.Packet(from, to, channel, id, port, payload)
    }

    /** Data{portnum, payload}. */
    private fun parseData(bytes: ByteArray): Pair<Int, ByteArray?> {
        var port = -1
        var payload: ByteArray? = null
        Reader(bytes).forEach { f, w, d ->
            when {
                f == 1 && w == WIRE_VARINT -> port = d.varint().toInt()
                f == 2 && w == WIRE_LEN -> payload = d.lenBytes()
                else -> d.skip(w)
            }
        }
        return port to payload
    }

    private fun parseMyInfo(bytes: ByteArray): FromRadio {
        var num = 0
        Reader(bytes).forEach { field, wire, r -> if (field == 1 && wire == WIRE_VARINT) num = r.varint().toInt() else r.skip(wire) }
        return FromRadio.MyInfo(num)
    }

    private fun parseQueue(bytes: ByteArray): FromRadio {
        var res = 0; var free = 0; var max = 0; var pid = 0
        Reader(bytes).forEach { field, wire, r ->
            if (wire != WIRE_VARINT) { r.skip(wire); return@forEach }
            val v = r.varint().toInt()
            when (field) { 1 -> res = v; 2 -> free = v; 3 -> max = v; 4 -> pid = v }
        }
        return FromRadio.Queue(res, free, max, pid)
    }

    // ── Protobuf wire format ──────────────────────────────────────────────

    private const val WIRE_VARINT = 0
    private const val WIRE_I64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_I32 = 5

    private class Writer {
        private val out = ByteArrayOutputStream()
        private fun raw(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        private fun key(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())
        fun varint(field: Int, v: Long) = apply { key(field, WIRE_VARINT); raw(v) }
        fun fixed32(field: Int, v: Int) = apply {
            key(field, WIRE_I32)
            for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF)
        }
        fun bytes(field: Int, b: ByteArray) = apply { key(field, WIRE_LEN); raw(b.size.toLong()); out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {
        private var at = 0
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                require(at < b.size && shift < 64) { "truncated varint" }
                val x = b[at++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }
        fun fixed32(): Int {
            require(at + 4 <= b.size) { "truncated fixed32" }
            var v = 0
            for (i in 0 until 4) v = v or ((b[at + i].toInt() and 0xFF) shl (8 * i))
            at += 4
            return v
        }
        fun lenBytes(): ByteArray {
            val n = varint()
            require(n >= 0 && at + n <= b.size) { "truncated field" }
            return b.copyOfRange(at, at + n.toInt()).also { at += n.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_I64 -> { require(at + 8 <= b.size); at += 8 }
                WIRE_LEN -> lenBytes()
                WIRE_I32 -> { require(at + 4 <= b.size); at += 4 }
                else -> throw IllegalArgumentException("wire type $wire")
            }
        }
        inline fun forEach(handle: (field: Int, wire: Int, r: Reader) -> Unit) {
            while (at < b.size) {
                val k = varint()
                val field = (k ushr 3).toInt()
                require(field > 0) { "field 0" }
                handle(field, (k and 7).toInt(), this)
            }
        }
    }
}
