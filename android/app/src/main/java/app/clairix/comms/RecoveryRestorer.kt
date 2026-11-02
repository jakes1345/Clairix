package app.clairix.comms

import uniffi.clairix_comms_crypto.CryptoException
import uniffi.clairix_comms_crypto.Identity
import uniffi.clairix_comms_crypto.RecoveryProblem
import uniffi.clairix_comms_crypto.recoveryOpen
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/**
 * Makes and restores recovery files for [CommsRepository], which holds its
 * lock around every call here except [seal].
 *
 * Restoring asks the relay first and writes nothing on this phone until the
 * relay has taken the identity back: either the old mailbox (the same number,
 * [RestorePlan.Resume]) or a new registration of the same keys
 * ([RestorePlan.Reregister]), after which every contact is sent the move
 * notice. Contacts see the same identity keys either way, so the same safety
 * numbers, and nothing asks them to verify again. No Olm session comes back:
 * every conversation starts a new one, the first time either side writes.
 */
internal class RecoveryRestorer(
    private val config: CommsConfig,
    private val identities: IdentityStore,
    private val transportKeys: TransportKeyStore,
    private val store: () -> CommsStore,
    /** Why this phone must not register at a relay address (see CommsRepository.listedKeyProblem); null when it may. */
    private val listedKeyProblem: (String) -> String?,
    /** A client for one relay mailbox: (identity, relay URL, number). */
    private val relayFor: (Identity, String, String) -> RelayClient,
    private val oneTimeKeys: UInt
) {

    /** What a recovery file saved now would hold besides the identity. */
    fun record(now: Long): RecoveryRecord {
        val url = checkNotNull(config.relayUrl) { "This phone has no Clairix number to save" }
        val number = checkNotNull(config.number) { "This phone has no Clairix number to save" }
        val s = store()
        val groups = s.groups()
        return RecoveryRecord(
            savedAt = now,
            relayUrl = url,
            number = number,
            name = config.displayName,
            listed = config.listed,
            contacts = (s.contacts() + s.requestContacts() + s.blockedContacts()).distinctBy { it.number },
            contactPasses = s.passes(),
            groups = groups,
            members = groups.flatMap { s.groupMembers(it.id) },
            blocked = s.blockedKeys(),
            timers = s.timers()
        )
    }

    /**
     * The recovery file: [record] and this phone's identity, encrypted under
     * [code]. Argon2id makes this take about a second; not under the lock.
     */
    fun seal(record: RecoveryRecord, code: String): ByteArray {
        val identity = checkNotNull(identities.get()) { "No identity on this phone" }
        return identity.recoveryFile(record.encode(), code)
    }

    /**
     * Restores [file] opened with [code]. [secret] or [invite] admit the keys
     * to a relay again when the old mailbox is gone; neither is needed when
     * it is still there.
     */
    fun restore(file: ByteArray, code: String, secret: String, invite: PairingCode?): RestoreOutcome {
        if (config.isRegistered) {
            return RestoreOutcome.Failed("This phone already has a Clairix number. A recovery file is restored at setup, on a phone without one.")
        }
        if (Vault.sealed.value) return RestoreOutcome.Failed("Unlock the phone, then try again.")
        if (invite != null && invite.invite == null) return RestoreOutcome.Failed("That code is not an invite")
        val opened = runCatching { recoveryOpen(file, code) }.getOrElse { return RestoreOutcome.Failed(openProblem(it)) }
        val identity = opened.identity
        val record = runCatching { RecoveryRecord.decode(opened.appData) }.getOrElse {
            identity.destroy()
            return RestoreOutcome.Failed("The file opened, but Clairix cannot use what is in it (${it.message}).")
        }
        val outcome = runCatching { bringBack(identity, record, secret, invite) }.getOrElse { e ->
            undo()
            RestoreOutcome.Failed(e.message ?: "The restore did not finish")
        }
        if (outcome !is RestoreOutcome.Restored) identity.destroy()
        return outcome
    }

    private fun bringBack(identity: Identity, record: RecoveryRecord, secret: String, invite: PairingCode?): RestoreOutcome =
        when (val plan = RestorePlan.of(record, probe(identity, record), invite?.relayUrl)) {
            is RestorePlan.Retry -> RestoreOutcome.Unreachable(record.relayUrl, plan.reason)
            is RestorePlan.Suspended -> RestoreOutcome.Suspended(plan.relayUrl, plan.reason)
            is RestorePlan.Resume -> {
                resume(identity, plan)
                commit(identity, record, plan.relayUrl, plan.number, moved = false)
            }
            is RestorePlan.Reregister -> reregister(identity, record, plan, secret, invite)
        }

    /**
     * Asks the relay for the old mailbox, signed with the restored identity.
     * A refusal is asked once more: the first answer taught the client the
     * relay's clock, and a phone whose clock is off is refused the first time.
     */
    private fun probe(identity: Identity, record: RecoveryRecord): RelayProbe {
        val relay = relayFor(identity, record.relayUrl, record.number)
        repeat(2) { attempt ->
            try {
                relay.me()
                return RelayProbe.Found
            } catch (e: RelayException) {
                if (attempt == 1 || e.code != HTTP_UNAUTHORIZED) return RelayProbe.fromRefusal(e.code, e.message ?: "no answer")
            } catch (e: IOException) {
                return RelayProbe.Unreachable(e.message ?: e.javaClass.simpleName)
            }
        }
        return RelayProbe.Unreachable("no answer")
    }

    /**
     * The old mailbox is still there: the relay is given keys only this
     * phone holds the secrets of, in place of the old phone's. One-time keys
     * replace the whole pool, a new fallback key and sealing prekey go with
     * them, a new transport key shuts the old phone's out of the mailbox, and
     * the old phone's push endpoint is taken away, so a lost phone is not
     * woken each time something arrives.
     */
    private fun resume(identity: Identity, plan: RestorePlan.Resume) {
        val relay = relayFor(identity, plan.relayUrl, plan.number)
        identity.generateOneTimeKeys(oneTimeKeys)
        relay.putKeys(identity.publicBundle(), replace = true)
        identity.markKeysPublished()
        transportKeys.destroy()
        relay.putTransport(transportKeys.get().publicKey())
        transportKeys.setPublished(true)
        runCatching { relay.setPush(null) }
    }

    /**
     * The old mailbox is gone: the same keys are registered again, which
     * gives a new number, and contacts are told it with the move notice. A
     * relay that wants an invite or its enrollment secret says so, and
     * nothing on the phone changes.
     */
    private fun reregister(identity: Identity, record: RecoveryRecord, plan: RestorePlan.Reregister, secret: String, invite: PairingCode?): RestoreOutcome {
        listedKeyProblem(plan.relayUrl)?.let { return RestoreOutcome.Failed(it) }
        identity.generateOneTimeKeys(oneTimeKeys)
        transportKeys.destroy()
        val transportKey = runCatching { transportKeys.get().publicKey() }.getOrNull()
        val relay = relayFor(identity, plan.relayUrl, record.number)
        val number = try {
            relay.register(plan.relayUrl, identity, secret.takeIf { invite == null }, record.listed, invite?.invite, transportKey).number
        } catch (e: RelayException) {
            val reason = e.message ?: "The relay did not take the keys back"
            return if (e.code == HTTP_FORBIDDEN || e.code == HTTP_TOO_MANY) RestoreOutcome.NeedsAdmission(plan.relayUrl, reason) else RestoreOutcome.Failed(reason)
        }
        identity.markKeysPublished()
        if (transportKey != null) transportKeys.setPublished(true)
        return runCatching { commit(identity, record, plan.relayUrl, number, moved = true) }.getOrElse { e ->
            // The account just made would hold these keys and block the next try.
            runCatching { relayFor(identity, plan.relayUrl, number).wipe() }
            throw e
        }
    }

    /** Writes everything on this phone, once the relay has the identity. */
    private fun commit(identity: Identity, record: RecoveryRecord, url: String, number: String, moved: Boolean): RestoreOutcome {
        identities.adopt(identity)
        val s = store()
        s.clearAll()
        for (c in record.contacts) s.upsertContact(c)
        for ((peer, pass) in record.contactPasses) {
            runCatching { Base64.getDecoder().decode(pass.first) }.getOrNull()?.let { s.setPass(peer, it, pass.second) }
        }
        for (g in record.groups) {
            s.insertGroup(g)
            s.setRoster(g.id, g.rosterVersion, g.rosterSeen)
        }
        for (m in record.members) s.insertGroupMember(m)
        for (b in record.blocked) s.addBlocked(b.ed25519, b.curve25519, b.number, b.relay, b.name, b.ts)
        for ((peer, timer) in record.timers) s.setTimer(peer, timer.first, timer.second)
        val now = System.currentTimeMillis()
        config.saveRegistration(url, number, record.name, record.listed)
        identity.publicBundle().sealingPrekey?.let { config.setPrekeyPublished(it.createdMs) }
        config.setFallbackRotatedAt(now)
        // A new pass key, owed to every contact: each gets it over a new
        // session, so their next message reaches this phone on a session it has.
        config.setPassKey(ByteArray(PASS_KEY_BYTES).also { SecureRandom().nextBytes(it) }, now)
        s.owePassToAll()
        if (moved) {
            s.moveSelf(record.number, number, RelayClient.originOf(record.relayUrl) ?: record.relayUrl, RelayClient.originOf(url) ?: url)
            config.setMovedAt(now)
        }
        config.setOnline(true)
        config.markOnlineDefaultApplied()
        return RestoreOutcome.Restored(number, sameNumber = !moved, contacts = record.contacts.count { !it.blocked && !it.request }, groups = record.groups.size)
    }

    /** A restore that failed part way leaves this phone as it was: no identity, no number, no contacts. */
    private fun undo() {
        runCatching { identities.destroy() }
        runCatching { transportKeys.destroy() }
        runCatching { store().clearAll() }
        if (config.isRegistered) config.clear()
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY = 429
        const val PASS_KEY_BYTES = 32

        /** What to tell the owner when [e] kept the file from opening. */
        fun openProblem(e: Throwable): String = when ((e as? CryptoException.Recovery)?.problem) {
            RecoveryProblem.NOT_A_RECOVERY_FILE -> "This is not a Clairix recovery file."
            RecoveryProblem.NEWER_VERSION -> "This recovery file was made by a newer version of Clairix. Update Clairix, then try again."
            RecoveryProblem.CODE_MISTYPED -> "That is not a recovery code. Check each group of four against what you wrote down."
            RecoveryProblem.WRONG_CODE_OR_DAMAGED -> "That code does not open this file, or the file was changed after it was saved. Check it is the file that goes with this code."
            RecoveryProblem.UNREADABLE -> "The file opened, but what is in it is not a Clairix identity."
            null -> "The file could not be opened: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
