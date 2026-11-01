package app.clairix.comms

/**
 * Whether a call's audio may go only through the relay's TURN server
 * ("Relay-only calls" in Comms settings).
 *
 * A direct call hands each phone the other's network address, answered or
 * not, and someone compelled to take calls from the person they are hiding
 * from should not have to know to turn that off. So until the owner chooses
 * either way, relay-only is on whenever the relay offers a TURN server for
 * the call, and off when it offers none (without one a relay-only call could
 * not connect at all). An owner who switched it on keeps it on, and then a
 * call cannot be placed while the relay has no TURN server; one who switched
 * it off gets direct calls whenever both networks allow them.
 *
 * Pure Kotlin, so the JVM tests use exactly this code.
 */
object RelayOnlyCalls {

    /** [choice] is the owner's (null while they have not made one); [relayOffersTurn], whether the relay offers TURN for this call. */
    fun on(choice: Boolean?, relayOffersTurn: Boolean): Boolean = choice ?: relayOffersTurn
}
