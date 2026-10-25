package app.clairix.comms.mesh

/**
 * One meeting between two phones, the same for whichever started it. The one that
 * connected says hello with the ids it holds; the other answers with its own, then
 * sends what the first lacks and says it is done; the first sends what the second
 * lacks and says it is done. Bundles addressed to this phone's owner are handed over
 * and not carried further; the rest are kept for the next meeting.
 *
 * [send] takes a whole frame; the transport splits it. Not thread safe: the transport
 * calls it from one thread per connection.
 */
class MeshSession(
    private val store: MeshStore,
    private val ownTags: () -> Set<String>,
    private val deliver: (MeshProtocol.Bundle) -> Unit,
    private val send: (ByteArray) -> Unit,
    private val now: () -> Long,
    private val initiator: Boolean
) {
    private var sentHello = false
    private var sentDone = false
    private var gotDone = false

    var received = 0
        private set
    var forwarded = 0
        private set
    var deliveredHere = 0
        private set

    val finished: Boolean get() = sentDone && gotDone

    fun start() { if (initiator) hello() }

    private fun hello() {
        send(MeshProtocol.encode(MeshProtocol.Frame.Hello(store.knownIds(now()))))
        sentHello = true
    }

    /** Handles one whole frame. Returns false when the peer sent something malformed and the meeting should end. */
    fun onFrame(bytes: ByteArray): Boolean {
        when (val f = MeshProtocol.decode(bytes)) {
            null -> return false
            is MeshProtocol.Frame.Hello -> {
                if (sentDone) return false
                if (!sentHello) hello()
                for (b in store.missingFrom(f.ids, now())) {
                    send(MeshProtocol.encode(MeshProtocol.Frame.Carry(b)))
                    forwarded++
                }
                send(MeshProtocol.encode(MeshProtocol.Frame.Done))
                sentDone = true
            }
            is MeshProtocol.Frame.Carry -> {
                received++
                val b = f.bundle
                if (MeshProtocol.hex(b.tag) in ownTags()) {
                    if (store.markDelivered(b, now())) {
                        deliveredHere++
                        deliver(b)
                    }
                } else {
                    store.add(b, now())
                }
            }
            MeshProtocol.Frame.Done -> gotDone = true
        }
        return true
    }
}
