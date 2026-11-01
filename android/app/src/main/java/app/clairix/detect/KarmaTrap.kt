package app.clairix.detect

import android.net.wifi.WifiManager

/**
 * Whether the Karma trap is out: the made-up network name registered and Android
 * actually probing for it. The Wi-Fi tab shows this, so it has to be true.
 *
 * Android answers `addNetworkSuggestions` with success before the owner has
 * answered its "allow Clairix to suggest networks?" question, and it does not probe
 * for a suggestion it has not been allowed to use. An earlier version marked the trap
 * armed on that first success, so a phone whose owner tapped "Don't allow" was shown
 * as protected. Armed now needs both: the suggestion in place, and the approval
 * Android reports to [WifiManager.addSuggestionUserApprovalStatusListener].
 */
data class KarmaTrap(
    val state: State = State.OFF,
    /** When the trap was last armed after being down; 0 unless [state] is [State.ARMED]. */
    val since: Long = 0L,
    /** The platform's status code when it refused the suggestion, for the message. */
    val code: Int? = null
) {
    enum class State {
        /** The scanner is not running, so the name is withdrawn and nothing is probed. */
        OFF,
        /** This phone offers apps no Wi-Fi service. */
        UNAVAILABLE,
        /** Added, but Android has not been allowed to use Clairix's suggestions yet. */
        PENDING,
        /** Added and allowed: the phone asks for the name when it scans. */
        ARMED,
        /** The owner said no to Clairix suggesting networks (or turned it off since). */
        DISALLOWED,
        /** Android refused the suggestion for another reason; [code] says which. */
        REFUSED
    }

    val armed: Boolean get() = state == State.ARMED

    /**
     * The line the Wi-Fi tab leads with. [sinceText] is [since] already formatted
     * for the reader; it is used only when armed.
     */
    fun headline(sinceText: String): String = when (state) {
        State.ARMED -> "Trap armed since $sinceText"
        State.PENDING, State.DISALLOWED -> "Trap not armed: allow Clairix to suggest networks"
        State.REFUSED -> "Trap not armed: Android refused it"
        State.UNAVAILABLE -> "Trap not armed: no Wi-Fi service for apps"
        State.OFF -> "Trap off while the scanner is off"
    }

    /** What the state means and, when the trap is down, what turns it on. */
    fun detail(): String = when (state) {
        State.ARMED ->
            "While the scanner runs, the phone asks for a made-up network name, a new one every six hours. " +
                "Only a box that answers to any name (a Karma or Pineapple kit) answers it, and it would be listed above."
        State.PENDING ->
            "Android asks whether Clairix may suggest Wi-Fi networks when scanning starts. Until you allow it, " +
                "the phone does not ask for the made-up name. The name is added untrusted, with auto-join off and a " +
                "password nobody knows, so the phone does not join what answers."
        State.DISALLOWED ->
            "Suggestions from Clairix are turned off. To set the trap: Settings → Apps → Special app access → " +
                "Wi-Fi control → Clairix, then allow it when Android asks. Nothing else in Clairix needs this."
        State.REFUSED -> when (code) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN ->
                "A device policy on this phone stops apps adding Wi-Fi networks."
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
                "Android's limit on suggested networks was reached. Clairix tries again every few minutes."
            else -> "Android refused the suggestion" + (code?.takeIf { it >= 0 }?.let { " (code $it)" } ?: "") +
                ". Clairix tries again every few minutes."
        }
        State.UNAVAILABLE -> "Android gives apps no Wi-Fi service on this phone, so there is nothing to set the trap with."
        State.OFF ->
            "The made-up name is withdrawn when scanning stops, so the phone does not keep asking for it with " +
                "nobody reading the answers. Start scanning to set it again."
    }

    companion object {
        /**
         * The trap as it stands, from what the platform last said. [addStatus] is what
         * `addNetworkSuggestions` returned for the name in place (null before the first
         * try this run); [approval] is the last `STATUS_SUGGESTION_APPROVAL_` value the
         * listener reported (null until it has). [previous] carries the arming time
         * across a rotation, when the name changes but the trap never went down.
         */
        fun judge(
            previous: KarmaTrap,
            running: Boolean,
            wifiAvailable: Boolean,
            addStatus: Int?,
            approval: Int?,
            now: Long
        ): KarmaTrap {
            if (!running) return KarmaTrap(State.OFF)
            if (!wifiAvailable) return KarmaTrap(State.UNAVAILABLE)
            // A "Don't allow" (or Wi-Fi control turned off in Settings) also makes Android
            // drop the suggestion, so it outranks an earlier successful add.
            if (addStatus == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ||
                approval == WifiManager.STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER
            ) return KarmaTrap(State.DISALLOWED, code = addStatus)
            if (addStatus == null) return KarmaTrap(State.PENDING)
            if (!added(addStatus)) return KarmaTrap(State.REFUSED, code = addStatus)
            val allowed = approval == WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER ||
                approval == WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_CARRIER_PRIVILEGE
            if (!allowed) return KarmaTrap(State.PENDING)
            val since = if (previous.state == State.ARMED && previous.since > 0L) previous.since else now
            return KarmaTrap(State.ARMED, since = since)
        }

        /** Whether an add left the suggestion in place: success, or (before Android 11) "already there". */
        fun added(status: Int): Boolean =
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
    }
}
