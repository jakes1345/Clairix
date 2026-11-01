package app.clairix.comms

/**
 * Envelopes in format 2, sealed to a contact's long-term key, are what this
 * app sends to a contact whose sealing prekey it has never held: apps before
 * 3.0.0 Beta 7, and also an up-to-date contact whose session with this phone
 * began before Beta 7 and who has not sealed to this phone's prekey since, so
 * that neither phone ever asked for the other's. From the relay's
 * LEGACY_CUTOFF on it answers format 2 with 426 (comms-worker/src/index.ts,
 * refuseLegacyEnvelope). For the second kind of contact that refusal would be
 * final for every message and every SOS, though both phones are up to date,
 * so the send asks once for the contact's keys and, when they bring a prekey,
 * seals the envelope again in format 3 ([CommsRepository]).
 */
internal object LegacyEnvelope {

    /** The relay's answer to a format-2 envelope, or to the old kind of token, from its cutoff on. */
    const val UPGRADE_REQUIRED = 426

    /**
     * Whether a send the relay refused with [status] is tried once more after
     * the contact's keys are looked up: only for a 426, only when no prekey of
     * theirs is held (with one, the envelope was format 3 already and what the
     * relay refused was something else, the old kind of token), and only once
     * per send ([alreadyLookedUp]).
     */
    fun lookUpPrekeyAndRetry(status: Int, prekeyHeld: Boolean, alreadyLookedUp: Boolean): Boolean =
        status == UPGRADE_REQUIRED && !prekeyHeld && !alreadyLookedUp
}
