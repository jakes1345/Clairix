package app.clairix

import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.clairix.analysis.CardVault
import app.clairix.analysis.NfcScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * The NFC tab's side of [MainActivity] in the whole app: reader mode, tags that
 * arrive by intent, the card vault and, in the full edition, card emulation.
 * One per Activity instance; MainActivity forwards its lifecycle (see [NfcHost]).
 */
internal class NfcVaultHost(private val activity: AppCompatActivity) : NfcHost {

    private var nfcAdapter: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null

    // Constructing the vault touches nothing; the Keystore is only reached inside
    // its methods, which never throw — so no keystore state can take down launch.
    private val cardVault by lazy { CardVault(activity.applicationContext) }

    /**
     * The card-emulation service, or null in an edition built without the card
     * vault (the Play edition), where there is no service to route readers to.
     */
    private val hceComponent: ComponentName? by lazy {
        if (BuildConfig.CARD_VAULT) CardEmulationBridge.component(activity) else null
    }

    /** Whether reader mode is currently enabled on the adapter by this activity. */
    private var readerModeOn = false

    /**
     * True between onResume and onPause. The lifecycle's own state cannot be used
     * for this from inside onResume: androidx only marks the Activity RESUMED after
     * onResume returns, so a guard on it there always failed and reader mode was
     * never switched on.
     */
    private var resumed = false

    override fun onCreate() {
        activity.lifecycleScope.launch {
            // A card the guard disarmed (screen off, time up) while this screen
            // is up: back to reader mode, as STOP would have done.
            Registry.emulating.collect { if (it == null) applyNfcMode() }
        }

        // The Play edition reads tags but has no card emulation, so it never asks for it.
        nfcAdapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
        if (hceComponent != null) {
            cardEmulation = nfcAdapter?.let { runCatching { CardEmulation.getInstance(it) }.getOrNull() }
        }

        // Emulation state lives only in this process, so if nothing is armed now,
        // any AIDs still registered belong to a previous process — they survive
        // reboots — and would keep routing readers to the service. Drop them.
        if (Registry.emulating.value == null) clearHceAids()

        // Seed whether the vault-ownership notice has been acknowledged, so the
        // NFC tab can gate the first save without reading preferences on the main
        // thread. Only the editions with the vault (CARD_VAULT) ever ask.
        if (BuildConfig.CARD_VAULT) Registry.setOwnershipAck(cardVault.hasOwnershipAck())

        // Results of BiometricPrompt arrive through the Registry, so that whichever
        // Activity instance is on screen when they land carries the operation out.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Registry.vaultAuth.collect { pending ->
                    if (pending != null) Registry.takeVaultAuth()?.let { handleAuthResult(it) }
                }
            }
        }

        // A tag tapped while Clairix was not in reader mode (backgrounded, or picked
        // from the system's NFC chooser) arrives as the launching intent.
        onIntent(activity.intent)
    }

    override fun onResume() {
        resumed = true
        applyNfcMode()
    }

    override fun onPause() {
        resumed = false
        nfcAdapter?.let { adapter ->
            if (readerModeOn) runCatching { adapter.disableReaderMode(activity) }
        }
        readerModeOn = false
        runCatching { cardEmulation?.unsetPreferredService(activity) }
    }

    @Composable
    override fun Screen() = NfcScreen(
        onAddToVault = { tag, label, replace -> addToVault(tag, label, replace) },
        onRemoveVault = { id -> removeFromVault(id) },
        onEmulate = { card -> if (card == null) stopEmulation() else armEmulation(card) },
        onUnlockVault = { unlockVault() },
        onLockVault = { Registry.lockVault() },
        onEraseVault = { eraseVault() },
        onAcknowledgeOwnership = {
            cardVault.setOwnershipAck()
            Registry.setOwnershipAck(true)
        }
    )

    /**
     * The manifest registers for NDEF, TECH and TAG discovery, which puts Clairix in
     * the system's NFC chooser and delivers tags tapped while it is backgrounded.
     * Those intents were never read, so choosing Clairix dropped the tag on the floor.
     */
    override fun onIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        // Consumed: a later onCreate (recreation) must not scan the same tag again.
        intent.action = null
        Registry.requestTab(ROUTE_NFC)
        // Parsing talks to the tag over the air and must not block the main thread.
        activity.lifecycleScope.launch(Dispatchers.IO) { scanTag(tag) }
    }

    /**
     * Reads a tapped tag. An ordinary scan only identifies the card and keeps no APDU
     * pairs; a tap while a "Record for vault" re-tap is armed (full edition only)
     * records the ISO 14443-4 conversation and stages it as a pending vault capture,
     * whose pairs never enter the scanned-tag list.
     */
    private fun scanTag(tag: Tag) {
        val mifare = NfcScanner.readsMifareClassic(activity)
        if (BuildConfig.CARD_VAULT && Registry.vaultCaptureArm.value != null) {
            val capture = try {
                NfcScanner.capture(tag, mifare)
            } catch (_: Exception) { Registry.cancelVaultCapture(); return }
            Registry.cancelVaultCapture()
            Registry.addNfc(capture.tag)
            runCatching { recordNfc(capture.tag) }
            // A payment card held to the phone instead stages nothing: it is never savable.
            Registry.setPendingVaultCapture(NfcScanner.stagedCapture(capture))
        } else {
            val parsed = try {
                NfcScanner.parse(tag, mifare)
            } catch (_: Exception) { return }
            Registry.addNfc(parsed)
            runCatching { recordNfc(parsed) }
        }
    }

    // ── NFC: reader mode versus card emulation ───────────────────────────────

    /**
     * Reader mode and card emulation are mutually exclusive: while reader mode is on,
     * the controller polls for tags and never answers a reader as a card. It used to
     * be switched on unconditionally in onResume, so emulation could not work at all
     * while Clairix was on screen. Now, while a card is armed, reader mode stays off
     * and Clairix is made the preferred HCE service for as long as it is in front.
     */
    private fun applyNfcMode() {
        // Reader mode is only valid on a resumed, live activity; the flag is kept by
        // hand because the lifecycle is still STARTED while onResume runs.
        if (!resumed || activity.isFinishing) return
        val adapter = nfcAdapter ?: return
        if (Registry.emulating.value != null) {
            if (readerModeOn) runCatching { adapter.disableReaderMode(activity) }
            readerModeOn = false
            hceComponent?.let { component -> runCatching { cardEmulation?.setPreferredService(activity, component) } }
        } else {
            runCatching { cardEmulation?.unsetPreferredService(activity) }
            if (!readerModeOn) {
                readerModeOn = runCatching {
                    adapter.enableReaderMode(
                        activity, readerCallback,
                        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                        null
                    )
                }.isSuccess
            }
        }
    }

    /** Runs on an NFC binder thread, so the timeline's disk write is fine here. */
    private val readerCallback = NfcAdapter.ReaderCallback { tag -> scanTag(tag) }

    private fun recordNfc(t: NfcTag) {
        val now = System.currentTimeMillis()
        val status = Registry.status.value
        val detail = buildString {
            append("UID ").append(t.uid)
            if (t.atqa != null && t.sak != null) append(" · ATQA ${t.atqa} SAK ${t.sak}")
            if (t.skimmerFlags.isNotEmpty()) append(" — flags: ").append(t.skimmerFlags.joinToString("; "))
            else if (t.suspicious) append(" — ").append(t.note)
        }
        TimelineLog.record(
            activity.applicationContext,
            TimelineEvent(
                id = "nfc@${t.uid}@$now",
                kind = EventKind.NFC_TAG,
                ts = now,
                title = if (t.suspicious) "Suspicious NFC tag: ${t.type}" else "NFC tag scanned: ${t.type}",
                detail = detail,
                severity = if (t.suspicious) Severity.HIGH else Severity.LOW,
                lat = status.lat, lon = status.lon
            ),
            dedupeKey = "nfc@${t.uid}"
        )
    }

    // ── Card emulation ───────────────────────────────────────────────────────

    private fun armEmulation(card: VaultCard) {
        if (!BuildConfig.CARD_VAULT) return
        // Emulating replays the card's stored credential, so it needs the vault open
        // — the card has to come from an unlocked vault, not a stale UI row.
        val state = Registry.vault.value
        if (state !is VaultState.Unlocked || state.cards.none { it.id == card.id }) {
            unlockVault()
            return
        }
        val emulation = cardEmulation
        val component = hceComponent
        if (emulation == null || component == null) {
            toast("This phone does not support NFC card emulation")
            return
        }
        val aids = card.apduPairs.mapNotNull { (cmd, _) ->
            val bytes = cmd.replace(" ", "")
            if (bytes.length >= 10 && (
                bytes.startsWith("00A40400", ignoreCase = true) ||
                bytes.startsWith("00A40404", ignoreCase = true))) {
                val len = bytes.substring(8, 10).toIntOrNull(16) ?: 0
                bytes.substring(10, minOf(10 + len * 2, bytes.length))
            } else null
        }.distinct().ifEmpty { listOf("F000000000") }

        val loaded = CardEmulationBridge.load(card.apduPairs)
        val registered = loaded && runCatching {
            emulation.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, aids)
        }.getOrDefault(false)
        if (!registered) {
            CardEmulationBridge.clear()
            clearHceAids()
            // The pairs and AIDs are gone, so whatever was armed before — switching
            // from card A to card B that then failed — is gone with them. Say so,
            // or the banner keeps announcing A with nothing behind it.
            Registry.setEmulating(null)
            applyNfcMode()
            toast("Android refused this card's application IDs — it cannot be emulated")
            return
        }
        Registry.setEmulating(ArmedCard(card.id, card.label))
        EmulationGuard.watch(activity)
        applyNfcMode()
    }

    private fun stopEmulation() {
        EmulationGuard.unwatch(activity)
        CardEmulationBridge.clear()
        clearHceAids()
        Registry.setEmulating(null)
        applyNfcMode()
    }

    /** Removes the dynamically registered AIDs, which otherwise persist across reboots. */
    private fun clearHceAids() {
        val component = hceComponent ?: return
        runCatching { cardEmulation?.removeAidsForService(component, CardEmulation.CATEGORY_OTHER) }
    }

    // ── Vault ────────────────────────────────────────────────────────────────

    /**
     * Decrypted cards are only ever shown to an Activity that is on screen. onStop
     * re-locks the vault, and a decryption or save still in flight at that moment
     * used to publish Unlocked(cards) straight after it, leaving the cards readable
     * from the background. Anything that would publish Unlocked goes through here.
     */
    private fun publishUnlocked(cards: List<VaultCard>) {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Registry.publishVault(VaultState.Unlocked(cards))
        }
        // Otherwise onStop has locked it; the cards are discarded with the coroutine.
    }

    /** Authenticates, then decrypts and publishes the vault. */
    private fun unlockVault(attempt: Int = 0) {
        if (!BuildConfig.CARD_VAULT) return
        Registry.publishVault(VaultState.Unlocking)
        authenticate(VaultAuthPurpose.Unlock(attempt))
    }

    private fun loadVault(attempt: Int) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { cardVault.load() }
            when (result) {
                is CardVault.LoadResult.Loaded -> publishUnlocked(result.cards)
                is CardVault.LoadResult.Failed ->
                    if (result.failure is CardVault.Failure.AuthRequired && attempt == 0) {
                        // The auth did not reach the key (it can lapse between the prompt
                        // and the decrypt). One re-prompt — but only while the Activity
                        // is in front to show it; otherwise "Unlocking" would sit there
                        // forever with no prompt behind it.
                        if (resumed) unlockVault(attempt + 1) else Registry.publishVault(VaultState.Locked)
                    } else {
                        Registry.publishVault(failedState(result.failure))
                    }
            }
        }
    }

    private fun addToVault(tag: NfcTag, label: String, replace: Boolean) {
        if (!BuildConfig.CARD_VAULT) return
        // A payment card is never stored or emulated, however the save was reached.
        if (NfcScanner.isPaymentProfile(tag.profile)) return
        // Emulation pairs come only from a matching "Record for vault" re-tap, held in
        // the pending capture; a card saved without one (e.g. MIFARE Classic, kept for
        // identification) stores none.
        val pending = Registry.pendingVaultCapture.value?.takeIf { it.uid == tag.uid }
        val card = VaultCard(
            id        = UUID.randomUUID().toString(),
            uid       = tag.uid,
            label     = label,
            profile   = tag.profile,
            addedTs   = System.currentTimeMillis(),
            apduPairs = pending?.apduPairs ?: emptyList()
        )
        Registry.setReplacePrompt(null)
        writeVault(VaultOp.Add(card, replace))
    }

    private fun removeFromVault(id: String) {
        if (!BuildConfig.CARD_VAULT) return
        writeVault(VaultOp.Remove(id))
    }

    /**
     * Runs a vault write. The key's authentication lasts 30 seconds, so a write some
     * time after unlocking needs the user again: on AuthRequired this prompts and
     * retries once. Any other failure leaves the stored vault untouched and says why.
     *
     * The operation is data ([VaultOp]) rather than a closure so the retry can be
     * carried out by whichever Activity instance receives the prompt's result.
     */
    private fun writeVault(op: VaultOp, attempt: Int = 0) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (op) {
                    is VaultOp.Add -> cardVault.add(op.card, op.replace)
                    is VaultOp.Remove -> cardVault.remove(op.id)
                }
            }
            when (result) {
                is CardVault.WriteResult.Saved -> {
                    // Always publish the fresh list: + VAULT is only offered while the
                    // vault is open, and a save that went through the prompt has just
                    // proved the user's identity. Either way the tag row's IN VAULT
                    // state comes from this list, so it has to be current.
                    publishUnlocked(result.cards)
                    when (op) {
                        is VaultOp.Add -> {
                            // The capture (if any) is now in the encrypted card; drop the copy.
                            Registry.setPendingVaultCapture(null)
                            toast("Saved \"${op.card.label}\" to the vault")
                        }
                        is VaultOp.Remove -> if (Registry.emulating.value?.id == op.id) stopEmulation()
                    }
                }
                is CardVault.WriteResult.Failed -> when (val f = result.failure) {
                    CardVault.Failure.AuthRequired ->
                        if (attempt == 0 && resumed) {
                            authenticate(VaultAuthPurpose.Write(op, attempt + 1))
                        } else {
                            toast("Vault not changed — authentication did not take effect")
                        }
                    CardVault.Failure.NoLockScreen -> toast(NO_LOCK_SCREEN)
                    is CardVault.Failure.Duplicate -> {
                        // Only an Add can be refused this way; the UI asks before
                        // overwriting, and the answer comes back with replace = true.
                        val add = op as? VaultOp.Add ?: return@launch
                        val tag = Registry.nfc.value.firstOrNull { it.uid == add.card.uid }
                        if (tag != null) {
                            Registry.setReplacePrompt(ReplacePrompt(tag, add.card.label, f.existing))
                        } else {
                            toast("\"${f.existing.label}\" is already in the vault with this UID")
                        }
                    }
                    else -> {
                        toast("Vault not changed — it could not be read")
                        Registry.publishVault(failedState(f))
                    }
                }
            }
        }
    }

    /**
     * Deletes an unreadable vault at the user's explicit request (behind a confirm
     * dialog). Confirms identity first when the phone can, so a borrowed, unlocked
     * phone cannot be used to wipe it.
     */
    private fun eraseVault() {
        if (!BuildConfig.CARD_VAULT) return
        if (BiometricManager.from(activity).canAuthenticate(VAULT_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            authenticate(VaultAuthPurpose.Erase)
        } else {
            doErase()
        }
    }

    private fun doErase() {
        activity.lifecycleScope.launch {
            val erased = withContext(Dispatchers.IO) { cardVault.erase() }
            if (erased) {
                if (Registry.emulating.value != null) stopEmulation()
                publishUnlocked(emptyList())
                toast("Vault erased")
            } else {
                toast("The vault could not be erased")
            }
        }
    }

    /** Carries out the operation a completed BiometricPrompt was shown for. */
    private fun handleAuthResult(result: VaultAuthResult) {
        val purpose = result.purpose
        when (val outcome = result.outcome) {
            VaultAuthOutcome.Succeeded -> when (purpose) {
                is VaultAuthPurpose.Unlock -> loadVault(purpose.attempt)
                is VaultAuthPurpose.Write -> writeVault(purpose.op, purpose.attempt)
                VaultAuthPurpose.Erase -> doErase()
            }
            is VaultAuthOutcome.Failed -> when (purpose) {
                is VaultAuthPurpose.Unlock -> Registry.publishVault(
                    if (outcome.message == null) VaultState.Locked
                    else VaultState.Failed(outcome.message, retryable = true, erasable = false)
                )
                is VaultAuthPurpose.Write ->
                    toast(outcome.message ?: "Vault not changed — authentication cancelled")
                VaultAuthPurpose.Erase -> outcome.message?.let { toast(it) }
            }
        }
    }

    private fun failedState(f: CardVault.Failure): VaultState.Failed = when (f) {
        CardVault.Failure.AuthRequired -> VaultState.Failed(
            "Authentication did not reach the vault key. Try unlocking again.",
            retryable = true, erasable = false
        )
        CardVault.Failure.KeyInvalidated -> VaultState.Failed(
            "Android has permanently invalidated the vault key — this happens when the screen " +
                "lock is removed or reset. The stored cards can no longer be decrypted by anyone. " +
                "Nothing has been deleted; erase the vault to start a new one.",
            retryable = false, erasable = true
        )
        CardVault.Failure.NoLockScreen -> VaultState.Failed(
            NO_LOCK_SCREEN, retryable = true, erasable = false
        )
        // A refused overwrite is answered with a prompt, never a failed vault; this
        // only exists to keep the mapping total.
        is CardVault.Failure.Duplicate -> VaultState.Failed(
            "\"${f.existing.label}\" is already stored with this UID.",
            retryable = true, erasable = false
        )
        is CardVault.Failure.Error -> VaultState.Failed(
            "The vault could not be decrypted (${f.cause.javaClass.simpleName}). " +
                "Nothing has been changed or overwritten.",
            retryable = true, erasable = true
        )
    }

    /**
     * Shows BiometricPrompt allowing a strong biometric or the device PIN, pattern or
     * password — the same set the vault key accepts. The result is published to the
     * Registry as a [VaultAuthResult] for [purpose], not handed to a closure: the
     * callback then holds nothing of this Activity instance, and the instance alive
     * when the result lands (this one, or its replacement after a recreation) acts
     * on it.
     */
    private fun authenticate(purpose: VaultAuthPurpose) {
        fun fail(message: String?) =
            Registry.publishVaultAuth(VaultAuthResult(purpose, VaultAuthOutcome.Failed(message)))

        when (val can = BiometricManager.from(activity).canAuthenticate(VAULT_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> { fail(NO_LOCK_SCREEN); return }
            else -> {
                fail("This phone cannot confirm your identity right now (code $can).")
                return
            }
        }
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Registry.publishVaultAuth(VaultAuthResult(purpose, VaultAuthOutcome.Succeeded))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    Registry.publishVaultAuth(
                        VaultAuthResult(purpose, VaultAuthOutcome.Failed(if (cancelled) null else errString.toString()))
                    )
                }
                // onAuthenticationFailed is a single rejected attempt; the prompt
                // stays up for another try, so there is nothing to do.
            }
        )
        // Each operation says what it is: the erase and save prompts used to wear
        // the unlock's "view and use stored cards" copy.
        val (title, subtitle) = when (purpose) {
            is VaultAuthPurpose.Unlock -> "Unlock card vault" to "Confirm it's you to view and use stored cards"
            VaultAuthPurpose.Erase -> "Erase card vault" to "This permanently deletes every stored card"
            is VaultAuthPurpose.Write -> when (purpose.op) {
                is VaultOp.Add -> "Save card to vault" to "Confirm it's you to store this card"
                is VaultOp.Remove -> "Remove card from vault" to "Confirm it's you to change stored cards"
            }
        }
        // No negative button: with DEVICE_CREDENTIAL allowed the prompt supplies its
        // own "use PIN" path, and BiometricPrompt rejects a negative button then.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(VAULT_AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { fail("Could not show the ${title.lowercase(Locale.US)} prompt: ${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val VAULT_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        const val NO_LOCK_SCREEN =
            "Set a screen lock (PIN, pattern or password) in Android settings to use the vault."
    }
}
