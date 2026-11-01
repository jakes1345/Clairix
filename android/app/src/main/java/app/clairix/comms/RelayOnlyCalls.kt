package app.clairix.comms

/**
 * Whether a call's audio may go only through the relay's TURN server
 * ("Relay-only calls" in Comms settings), and what happens when it cannot.
 *
 * A direct call hands each phone the other's network address, answered or
 * not, and someone compelled to take calls from the person they are hiding
 * from should not have to know to turn that off. So until the owner chooses
 * either way, relay-only is on whenever the relay offers a TURN server for
 * the call. An owner who switched it on keeps it on, and then a call cannot be
 * placed while the relay has no TURN server; one who switched it off gets
 * direct calls whenever both networks allow them.
 *
 * A call is never moved to a direct path without the owner knowing ([route]):
 * - with Tor on, a direct call would hand the contact the address Tor hides,
 *   so calls go only through TURN whatever the choice, and without a TURN
 *   server the call is refused outright and the owner told why;
 * - with no choice made and no TURN server for this call, a call this phone
 *   places waits for the owner to confirm, for that call alone, that the
 *   contact may learn the phone's address ([Route.ASK]). A call being
 *   answered connects directly only on a relay that has never offered TURN;
 *   on one known to offer it, a passing failure of its call server ends the
 *   call instead of falling back.
 *
 * Pure Kotlin, so the JVM tests use exactly this code.
 */
object RelayOnlyCalls {

    /** How a call is set up. */
    enum class Route {
        /** Only through the relay's TURN server: the contact never learns this phone's address. */
        RELAY_ONLY,

        /** Directly when both networks allow it: the contact learns this phone's address. */
        DIRECT,

        /** Not placed yet: the owner is asked, for this call, whether to call directly. */
        ASK,

        /** Refused: Tor is on, and a direct call would give the contact the address Tor hides. */
        REFUSE_TOR,

        /** Refused: the owner switched relay-only on, and the relay has no TURN server for this call. */
        REFUSE_NO_TURN,

        /** Refused: the relay offers TURN, but its call server did not answer for this call. */
        REFUSE_TURN_UNAVAILABLE,
    }

    /** [choice] is the owner's (null while they have not made one); [relayOffersTurn], whether the relay offers TURN for this call. */
    fun on(choice: Boolean?, relayOffersTurn: Boolean): Boolean = choice ?: relayOffersTurn

    /**
     * How to set up a call.
     *
     * [choice] is the owner's relay-only choice (null while none is made);
     * [offeredNow], whether this call's servers include TURN; [relayKnownTurn],
     * whether the relay offered TURN for an earlier call (null before the
     * first); [torOn], whether Tor is on; [answering], whether this phone is
     * answering rather than placing the call; [directConfirmed], whether the
     * owner has confirmed a direct call for this call.
     */
    fun route(
        choice: Boolean?,
        offeredNow: Boolean,
        relayKnownTurn: Boolean?,
        torOn: Boolean,
        answering: Boolean,
        directConfirmed: Boolean,
    ): Route = when {
        offeredNow && (torOn || choice != false) -> Route.RELAY_ONLY
        torOn -> Route.REFUSE_TOR
        choice == true -> Route.REFUSE_NO_TURN
        choice == false -> Route.DIRECT
        directConfirmed -> Route.DIRECT
        answering && relayKnownTurn == true -> Route.REFUSE_TURN_UNAVAILABLE
        answering -> Route.DIRECT
        else -> Route.ASK
    }

    /** What the owner is told when a call is refused, or null for a route that sets the call up. */
    fun refusal(route: Route): String? = when (route) {
        Route.REFUSE_TOR ->
            "Tor is on, and the relay offered no TURN server for this call, so a direct call would give the contact this phone's " +
                "network address, which Tor hides. Calls need the relay's TURN server while Tor is on"
        Route.REFUSE_NO_TURN -> "Relay-only calls need the relay's call servers, and none are available right now"
        Route.REFUSE_TURN_UNAVAILABLE -> "The relay's call server did not answer, so the call was not connected directly; try again in a moment"
        Route.ASK -> "Not placed: a direct call would share this phone's network address with the contact"
        Route.RELAY_ONLY, Route.DIRECT -> null
    }
}
