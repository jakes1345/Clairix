package app.clairix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.NfcScanner
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.EmptyState
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.ScreenList
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusHero
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.components.setSensitiveText
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.mono
import app.clairix.ui.theme.monoSmall
import kotlinx.coroutines.delay

/**
 * DESFire and MIFARE Plus are ISO 14443-4 and so technically emulable, but they
 * authenticate with a challenge the phone cannot answer; only the static exchanges
 * a reader happens not to challenge can be replayed. Plain ISO 14443-4 is the case
 * HCE actually covers.
 */
private val CardProfile.hceLimited: Boolean
    get() = this == CardProfile.MIFARE_DESFIRE || this == CardProfile.MIFARE_PLUS

private const val HCE_LIMITED_CAVEAT = "Static replay only — readers that challenge the card will reject it"

/**
 * The line shown on the tag row and in the one-time acknowledgement before the first
 * save: the vault is for the owner's own cards, and replaying someone else's
 * credential may be a crime.
 */
private const val VAULT_OWNERSHIP_NOTICE =
    "Only cards issued to you. Recording and replaying someone else's card or credential may be a crime."

/**
 * The NFC tab: the tags read this session and, in the full edition, the card
 * vault and card emulation. The Play edition is built with CARD_VAULT off, so
 * the tab there is the tag reader and skimmer check alone: no vault section, no
 * save or emulate controls, and no emulation hints on the tag rows.
 */
@Composable
internal fun NfcScreen(
    onAddToVault: (NfcTag, String, Boolean) -> Unit,
    onRemoveVault: (String) -> Unit,
    onEmulate: (VaultCard?) -> Unit,
    onUnlockVault: () -> Unit,
    onLockVault: () -> Unit,
    onEraseVault: () -> Unit,
    onAcknowledgeOwnership: () -> Unit
) {
    val tags       by Registry.nfc.collectAsStateWithLifecycle()
    val vaultState by Registry.vault.collectAsStateWithLifecycle()
    val armed      by Registry.emulating.collectAsStateWithLifecycle()
    val replace    by Registry.replacePrompt.collectAsStateWithLifecycle()
    val ownershipAck   by Registry.ownershipAck.collectAsStateWithLifecycle()
    val captureArm     by Registry.vaultCaptureArm.collectAsStateWithLifecycle()
    val pendingCapture by Registry.pendingVaultCapture.collectAsStateWithLifecycle()
    val emulId = armed?.id
    // Cards exist here only while the vault is unlocked.
    val vault = (vaultState as? VaultState.Unlocked)?.cards.orEmpty()
    val vaultOpen = vaultState is VaultState.Unlocked
    var confirmErase by remember { mutableStateOf(false) }

    val vaultEnabled = BuildConfig.CARD_VAULT

    // The one-time ownership acknowledgement, shown before the first save. The action
    // the owner was about to take is held and run once they confirm.
    var ackAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingAck = ackAction
    if (vaultEnabled && pendingAck != null) {
        ClairixDialog(
            title = stringResource(R.string.drive_only_cards_issued_to_you),
            text = VAULT_OWNERSHIP_NOTICE,
            confirmLabel = stringResource(R.string.drive_i_understand),
            confirmTone = Tone.Caution,
            onConfirm = { ackAction = null; onAcknowledgeOwnership(); pendingAck() },
            dismissLabel = stringResource(R.string.settings_cancel),
            onDismiss = { ackAction = null }
        )
    }
    // Runs [action] straight away once the owner has acknowledged ownership, or shows
    // the acknowledgement first and runs it on confirm.
    val guardOwnership: (() -> Unit) -> Unit = { action ->
        if (ownershipAck) action() else ackAction = action
    }

    // The vault refused to overwrite a stored card with this UID; ask.
    val pendingReplace = replace
    if (vaultEnabled && pendingReplace != null) {
        ClairixDialog(
            title = stringResource(R.string.drive_replace_the_stored_card),
            text = stringResource(R.string.drive_is_already_in_the_vault_with, pendingReplace.existing.label, pendingReplace.existing.uid, pendingReplace.label),
            confirmLabel = stringResource(R.string.drive_replace),
            confirmTone = Tone.Caution,
            onConfirm = { onAddToVault(pendingReplace.tag, pendingReplace.label, true) },
            dismissLabel = stringResource(R.string.drive_keep_existing),
            onDismiss = { Registry.setReplacePrompt(null) }
        )
    }

    if (vaultEnabled && confirmErase) {
        ClairixDialog(
            title = stringResource(R.string.drive_erase_the_vault),
            text = stringResource(R.string.drive_the_stored_cards_cant_be_read),
            confirmLabel = stringResource(R.string.drive_erase_vault),
            confirmTone = Tone.Danger,
            onConfirm = { confirmErase = false; onEraseVault() },
            onDismiss = { confirmErase = false }
        )
    }

    val suspicious = tags.count { it.suspicious }

    ScreenList {
        // ── Status first ──
        item(key = "hero") {
            StatusHero(
                eyebrow = "NFC",
                headline = when {
                    suspicious > 0 -> if (suspicious == 1) stringResource(R.string.drive_suspicious_tag) else stringResource(R.string.drive_suspicious_tags, suspicious)
                    tags.isNotEmpty() -> stringResource(R.string.drive_tags_clean)
                    else -> stringResource(R.string.drive_tap_to_check)
                },
                body = when {
                    suspicious > 0 -> stringResource(R.string.drive_a_tag_below_doesnt_behave_like)
                    tags.isNotEmpty() -> "${plural(tags.size, "tag")} read this session. None look like a skimmer or tracker tag."
                    else -> stringResource(R.string.drive_hold_the_back_of_the_phone)
                },
                tone = when {
                    suspicious > 0 -> Tone.Danger
                    tags.isNotEmpty() -> Tone.Safe
                    else -> Tone.Neutral
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Active emulation banner
        val armedCard = armed
        if (vaultEnabled && armedCard != null) {
            item(key = "emul_banner") {
                Notice(
                    Tone.Info,
                    stringResource(R.string.drive_tag_reading_is_paused_while_emulating, armedCard.label),
                    title = stringResource(R.string.drive_emulating_a_card),
                    action = { GhostButton(stringResource(R.string.drive_stop_emulating), onClick = { onEmulate(null) }, tone = Tone.Danger) }
                )
            }
        }

        // ── Findings: what was read ──
        item(key = "divider") {
            SectionHeader(
                stringResource(R.string.drive_scanned_tags),
                action = if (tags.isNotEmpty()) {
                    { GhostButton(stringResource(R.string.comms_clear), onClick = { Registry.clearNfc() }, tone = Tone.Neutral) }
                } else null
            )
        }
        if (tags.isEmpty()) {
            item(key = "scan_empty") {
                EmptyState(
                    title = stringResource(R.string.drive_no_tags_read_yet),
                    body = stringResource(R.string.drive_any_nfc_tag_or_card_the),
                    icon = ClairixIcons.Nfc
                )
            }
        } else {
            items(tags, key = { "t_${it.uid}" }) { tag ->
                NfcTagRow(
                    t = tag,
                    vaultEnabled = vaultEnabled,
                    inVault = vault.any { it.uid == tag.uid },
                    vaultOpen = vaultOpen,
                    armedForCapture = captureArm == tag.uid,
                    captureReady = pendingCapture?.uid == tag.uid,
                    onAddToVault = { label -> onAddToVault(tag, label, false) },
                    onUnlockVault = onUnlockVault,
                    guardOwnership = guardOwnership
                )
            }
        }

        // ── The vault (full edition only) ──
        if (vaultEnabled) vaultSection(
            vaultState = vaultState,
            emulId = emulId,
            onEmulate = onEmulate,
            onRemoveVault = onRemoveVault,
            onUnlockVault = onUnlockVault,
            onLockVault = onLockVault,
            onConfirmErase = { confirmErase = true }
        )
    }
}

/** The card vault's rows on the NFC tab: its state, and the stored cards once it is open. */
private fun LazyListScope.vaultSection(
    vaultState: VaultState,
    emulId: String?,
    onEmulate: (VaultCard?) -> Unit,
    onRemoveVault: (String) -> Unit,
    onUnlockVault: () -> Unit,
    onLockVault: () -> Unit,
    onConfirmErase: () -> Unit
) {
    item(key = "vault_hdr") {
        SectionHeader(
            stringResource(R.string.drive_card_vault),
            supporting = if (vaultState is VaultState.Unlocked) null else stringResource(R.string.drive_hardware_encrypted),
            action = if (vaultState is VaultState.Unlocked) {
                { GhostButton(stringResource(R.string.drive_lock), onClick = onLockVault, tone = Tone.Neutral) }
            } else null
        )
    }
    when (val state = vaultState) {
        VaultState.Locked, VaultState.Unlocking -> item(key = "vault_locked") {
            val c = ClairixTheme.colors
            ClairixCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = c.accent)
                    Text(stringResource(R.string.drive_vault_locked), style = MaterialTheme.typography.titleMedium, color = c.text)
                }
                Text(
                    stringResource(R.string.drive_stored_cards_are_encrypted_with_a),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSubtle
                )
                PrimaryButton(
                    if (state == VaultState.Unlocking) stringResource(R.string.drive_waiting_for_you_to_confirm) else stringResource(R.string.drive_unlock_vault),
                    onClick = onUnlockVault,
                    enabled = state == VaultState.Locked,
                    loading = state == VaultState.Unlocking,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is VaultState.Failed -> item(key = "vault_failed") {
            Notice(
                Tone.Danger,
                state.message,
                title = stringResource(R.string.drive_vault_unavailable),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.retryable) GhostButton(stringResource(R.string.drive_try_again), onClick = onUnlockVault)
                        if (state.erasable) GhostButton(stringResource(R.string.drive_erase_vault), onClick = onConfirmErase, tone = Tone.Danger)
                    }
                }
            )
        }
        is VaultState.Unlocked -> if (state.cards.isEmpty()) {
            item(key = "vault_empty") {
                EmptyState(
                    title = stringResource(R.string.drive_no_cards_stored),
                    body = stringResource(R.string.drive_scan_a_card_and_tap_save),
                    icon = Icons.Filled.Lock
                )
            }
        } else {
            items(state.cards, key = { "v_${it.id}" }) { card ->
                VaultCardRow(card = card, emulId = emulId, onEmulate = onEmulate, onRemove = { onRemoveVault(card.id) })
            }
        }
    }
}

@Composable
private fun VaultCardRow(
    card: VaultCard,
    emulId: String?,
    onEmulate: (VaultCard?) -> Unit,
    onRemove: () -> Unit
) {
    val isEmulating = card.id == emulId
    val c = ClairixTheme.colors
    ClairixCard(tone = if (isEmulating) Tone.Info else Tone.Safe, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                card.label, style = MaterialTheme.typography.titleMedium, color = c.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            when {
                isEmulating -> StatusPill(stringResource(R.string.safety_active), Tone.Info)
                card.profile.hceLimited -> StatusPill(stringResource(R.string.drive_replay_only), Tone.Caution)
                card.profile.hceCapable -> StatusPill(stringResource(R.string.drive_can_emulate), Tone.Safe)
                else -> StatusPill(stringResource(R.string.drive_stored), Tone.Neutral)
            }
        }
        Text(stringResource(R.string.drive_uid, card.profile.label, card.uid), style = MaterialTheme.typography.monoSmall, color = c.textMuted)
        if (!card.profile.hceCapable) {
            Text(
                stringResource(R.string.drive_this_card_type_uses_its_own),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSubtle
            )
        } else if (card.profile.hceLimited) {
            Text(HCE_LIMITED_CAVEAT, style = MaterialTheme.typography.bodySmall, color = c.caution)
        }
        Text(stringResource(R.string.drive_saved, fmtTime(card.addedTs)), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.profile.hceCapable) {
                SecondaryButton(
                    if (isEmulating) stringResource(R.string.comms_stop) else stringResource(R.string.drive_emulate),
                    onClick = { onEmulate(if (isEmulating) null else card) },
                    tone = if (isEmulating) Tone.Danger else Tone.Info,
                    modifier = Modifier.weight(1f)
                )
            }
            GhostButton(stringResource(R.string.settings_remove), onClick = onRemove, tone = Tone.Neutral)
        }
    }
}

@Composable
private fun NfcTagRow(
    t: NfcTag,
    vaultEnabled: Boolean,
    inVault: Boolean,
    vaultOpen: Boolean,
    armedForCapture: Boolean,
    captureReady: Boolean,
    onAddToVault: (String) -> Unit,
    onUnlockVault: () -> Unit,
    guardOwnership: (() -> Unit) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(t.uid) { mutableStateOf(false) }
    var addingLabel by remember(t.uid) { mutableStateOf(false) }
    var labelText by remember(t.uid) { mutableStateOf("") }
    LaunchedEffect(copied) { if (copied) { delay(1500L); copied = false } }
    // A re-tap has recorded this card: open the label field with a sensible default.
    LaunchedEffect(captureReady) {
        if (captureReady && labelText.isBlank()) labelText = t.profile.label
    }
    // The label field is shown either for a recorded ISO-DEP card (captureReady) or
    // for a card saved for identification only (addingLabel).
    val namingCard = captureReady || addingLabel
    val c = ClairixTheme.colors

    val isPayment = NfcScanner.isPaymentProfile(t.profile)
    val tone = when {
        t.suspicious -> Tone.Danger
        isPayment    -> Tone.Caution
        else         -> Tone.Safe
    }

    ClairixCard(tone = tone, emphasis = t.suspicious) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.uid, style = MaterialTheme.typography.mono, color = c.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            StatusPill(
                when {
                    t.suspicious -> stringResource(R.string.drive_suspect)
                    isPayment    -> stringResource(R.string.drive_payment)
                    else         -> stringResource(R.string.drive_clean)
                },
                tone
            )
        }

        // Profile + ATQA/SAK
        val techLine = buildString {
            append(t.type)
            if (t.atqa != null && t.sak != null) append(stringResource(R.string.drive_atqa_sak, t.atqa, t.sak))
            if (t.paymentNetwork != null) append(" · ${t.paymentNetwork}")
        }
        Text(techLine, style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(t.note, style = MaterialTheme.typography.bodyMedium, color = if (t.suspicious) c.danger else c.textSubtle)

        // Skimmer flags
        t.skimmerFlags.forEach { flag -> WarningLine(flag, Tone.Danger) }
        if (isPayment) {
            Text(
                stringResource(R.string.drive_public_card_details_only_nothing_secret),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )
        }
        if (t.payload != null) {
            Text(stringResource(R.string.drive_data, t.payload.take(80)), style = MaterialTheme.typography.monoSmall, color = c.textMuted)
        }

        // HCE capability hint, only where there is a vault to emulate from: the
        // Play edition reads and checks tags but does not store or emulate them.
        if (vaultEnabled && !isPayment) {
            if (t.profile.hceLimited) {
                Text(stringResource(R.string.drive_replay_only_record_it_to_the, HCE_LIMITED_CAVEAT), style = MaterialTheme.typography.bodySmall, color = c.caution)
            } else if (t.profile.hceCapable) {
                Text(stringResource(R.string.drive_your_phone_can_stand_in_for), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
            }
        }

        // The ownership line, wherever the row offers a way into the vault.
        if (vaultEnabled && !isPayment && !inVault) {
            WarningLine(VAULT_OWNERSHIP_NOTICE, Tone.Caution)
        }

        // Waiting for the re-tap that records the card for the vault.
        if (vaultEnabled && armedForCapture && !captureReady) {
            Notice(
                Tone.Info,
                stringResource(R.string.drive_hold_the_card_to_the_back),
                title = stringResource(R.string.drive_ready_to_record),
                action = { GhostButton(stringResource(R.string.settings_cancel), onClick = { Registry.cancelVaultCapture() }, tone = Tone.Neutral) }
            )
        }

        // Name-and-save inline input, for a recorded card or an identification-only save.
        if (vaultEnabled && namingCard) {
            ClairixTextField(
                value = labelText,
                onValueChange = { labelText = it },
                label = stringResource(R.string.drive_card_label),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    stringResource(R.string.settings_save),
                    onClick = {
                        if (labelText.isNotBlank()) {
                            onAddToVault(labelText.trim())
                            addingLabel = false; labelText = ""
                        }
                    },
                    enabled = labelText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
                GhostButton(
                    stringResource(R.string.settings_cancel),
                    onClick = {
                        if (captureReady) Registry.setPendingVaultCapture(null)
                        addingLabel = false; labelText = ""
                    },
                    tone = Tone.Neutral
                )
            }
        }

        Text(fmtTime(t.ts), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Whether the tag is already stored is only known while the vault
            // is open, so a locked vault offers the unlock, not the save: with
            // the key's auth window still warm, a save from here used to go
            // through without a prompt and replace the stored card unseen.
            // Without a vault (the Play edition) the row offers only Copy UID.
            // An ISO-DEP card the phone can emulate is recorded by an explicit
            // re-tap (so the APDU capture never runs on an ordinary scan); a card
            // kept for identification only is saved straight away. Both pass the
            // one-time ownership acknowledgement first.
            if (vaultEnabled) {
                when {
                    !vaultOpen && !isPayment -> GhostButton(stringResource(R.string.drive_unlock_to_save), onClick = onUnlockVault)
                    isPayment || inVault || namingCard || armedForCapture -> if (inVault) StatusPill(stringResource(R.string.drive_in_vault), Tone.Safe)
                    t.profile.hceCapable ->
                        GhostButton(stringResource(R.string.drive_record_for_vault), onClick = { guardOwnership { Registry.armVaultCapture(t.uid) } })
                    else ->
                        GhostButton(stringResource(R.string.drive_save_to_vault), onClick = { guardOwnership { addingLabel = true; labelText = t.profile.label } })
                }
            }
            Spacer(Modifier.weight(1f))
            GhostButton(
                if (copied) stringResource(R.string.scan_copied) else stringResource(R.string.drive_copy_uid),
                onClick = { clipboard.setSensitiveText("Card UID", t.uid); copied = true },
                tone = if (copied) Tone.Safe else Tone.Neutral
            )
        }
    }
}
