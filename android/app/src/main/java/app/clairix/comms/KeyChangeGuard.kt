package app.clairix.comms

import org.json.JSONObject

/**
 * What happens to a payload from a contact whose keys changed.
 *
 * When a contact's keys change (they reinstalled, or someone is in between),
 * nothing from the new keys is shown or acted on until the owner looks: it is
 * held in quarantine, behind a "tap to review" in the conversation, until the
 * owner verifies the new keys (safety number or QR code) or explicitly accepts
 * them. Some payloads are never held because holding them means nothing or
 * acting on them later would be wrong:
 *
 *  - group control (roster changes, invitations) from changed keys is dropped,
 *    and never applied even once the keys are accepted;
 *  - a resync asks this phone to resend to the new keys, which it must not do
 *    before the owner looks; dropped;
 *  - receipts can only concern messages this phone sent to those keys, and it
 *    sends nothing to keys the owner has not looked at; dropped;
 *  - call signals go to [CallManager], which refuses to ring for changed keys
 *    and logs a missed call instead, so the owner sees someone tried.
 *
 * Pure Kotlin and org.json, so the JVM tests use exactly this code.
 */
object KeyChangeGuard {

    enum class Action { PROCESS, QUARANTINE, DROP }

    /** Most payloads held per contact; past it the oldest go (a 5 MB video is 172). */
    const val MAX_HELD_PER_CONTACT = 400

    private val NEVER_HELD = setOf(CommsWire.T_GROUP_CTRL, CommsWire.T_RESYNC, CommsWire.T_RECEIPT)

    /** What to do with [payload] from [contact] as it arrives. */
    fun inbound(contact: Contact, payload: JSONObject): Action {
        if (!contact.keyChanged) return Action.PROCESS
        val type = CommsWire.type(payload)
        return when {
            type == CommsWire.T_CALL -> Action.PROCESS
            type in NEVER_HELD -> Action.DROP
            else -> Action.QUARANTINE
        }
    }

    /**
     * Whether a held payload may be processed now that the owner verified or
     * accepted [contact]'s keys: only one that came from exactly those keys,
     * and never group control (which is not held in the first place, but a
     * held row is re-checked rather than trusted).
     */
    fun releasable(contact: Contact, heldFromCurve25519: String, payload: JSONObject): Boolean =
        !contact.keyChanged && heldFromCurve25519 == contact.curve25519 && CommsWire.type(payload) !in NEVER_HELD

    /**
     * Whether a group-control payload from [contact] may be applied, given the
     * identity key the group's roster recorded for them ([rosterEd25519], blank
     * when it recorded none). Control from changed keys never is; nor is control
     * signed by keys other than the ones the group knows for that member, which
     * the owner re-establishes by verifying the contact.
     */
    fun mayApplyGroupControl(contact: Contact, rosterEd25519: String?): Boolean =
        !contact.keyChanged && (rosterEd25519.isNullOrBlank() || rosterEd25519 == contact.ed25519)

    /** The conversation line that marks the change. */
    fun systemLine(contactLabel: String): String = "Safety number with $contactLabel changed"
}
