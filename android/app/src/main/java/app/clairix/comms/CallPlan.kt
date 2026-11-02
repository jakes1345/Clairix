package app.clairix.comms

import kotlinx.coroutines.Dispatchers
import org.webrtc.PeerConnection
import kotlinx.coroutines.withContext

/**
 * A call this phone was asked to place that waits for the owner: the relay
 * offered no TURN server for it and no relay-only choice is made, so a
 * direct call would tell [contact] this phone's network address
 * ([RelayOnlyCalls.Route.ASK], [CallManager.directCallAsk]). [relayUsuallyOffersTurn] is true when the
 * relay offered TURN for an earlier call, so its call server failed this
 * time rather than being absent.
 */
data class DirectCallAsk(val contact: Contact, val relayUsuallyOffersTurn: Boolean)

/** The servers one call is set up with, and the route [RelayOnlyCalls.route] chose for them. */
internal class CallPlan(val servers: List<RelayClient.IceServer>, val route: RelayOnlyCalls.Route, val relayKnownTurn: Boolean?) {
    /**
     * The WebRTC configuration for this plan, or a refusal (thrown, with the reason
     * for the call screen) when the call may be neither relay-only nor direct.
     */
    fun rtcConfiguration(): PeerConnection.RTCConfiguration {
        // With relay-only, the connection gathers no host or reflexive
        // candidates at all, so the other phone is told nothing but the TURN
        // server's address. A call that cannot be relay-only and may not go
        // direct is refused here, with the reason on the call screen.
        RelayOnlyCalls.refusal(route)?.let { reason ->
            CommsLog.add("Call not connected: $reason")
            throw IllegalStateException(reason)
        }
        val turnOffered = servers.any { s -> s.urls.any { it.startsWith("turn") } }
        if (servers.isEmpty()) {
            CommsLog.add("No call servers available: the call connects only when both phones can reach each other directly")
        } else if (!turnOffered) {
            CommsLog.add("No TURN relay configured: calls connect only when both networks allow a direct path")
        }
        val relayOnly = route == RelayOnlyCalls.Route.RELAY_ONLY
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        return config
    }

    companion object {
        /**
         * Fetches the relay's call servers and decides how the call is set up
         * ([RelayOnlyCalls.route]). Only the servers the relay hands out are used.
         * There is no built-in fallback: a third party's STUN server would learn
         * this phone's address on every call, which the relay's own TURN never does.
         */
        suspend fun plan(answering: Boolean, directConfirmed: Boolean): CallPlan {
            val known = CommsRepository.relayTurnOffered()
            val fetched = withContext(Dispatchers.IO) {
                runCatching { CommsRepository.relayClient().turn() }
                    .onFailure { CommsLog.add("Could not fetch call servers from the relay (${it.message})") }
            }
            val servers = fetched.getOrDefault(emptyList())
            val turnOffered = servers.any { s -> s.urls.any { it.startsWith("turn") } }
            // Remembered from an answer, never from a failure: it says what the
            // default does on this relay, and whether a missing TURN server this
            // time is a failure on a relay that has one.
            if (fetched.isSuccess) CommsRepository.noteRelayTurn(turnOffered)
            val relayKnownTurn = if (fetched.isSuccess) turnOffered else known
            val route = RelayOnlyCalls.route(
                choice = CommsRepository.relayOnlyCallsChoice(),
                offeredNow = turnOffered,
                relayKnownTurn = relayKnownTurn,
                torOn = TorTransport.enabled,
                answering = answering,
                directConfirmed = directConfirmed,
            )
            return CallPlan(servers, route, relayKnownTurn)
        }
    }
}
