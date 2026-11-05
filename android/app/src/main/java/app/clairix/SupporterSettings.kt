package app.clairix

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.CommsRepository
import app.clairix.comms.RelayClient
import app.clairix.comms.RelayException
import app.clairix.security.DiscreetMode
import app.clairix.supporter.EditionBilling
import app.clairix.supporter.Entitlement
import app.clairix.supporter.SupporterBilling
import app.clairix.supporter.SupporterCode
import app.clairix.supporter.SupporterPurchase
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.ClairixTopBar
import app.clairix.ui.components.ListGroup
import app.clairix.ui.components.ListRow
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.ScreenList
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.launch

/**
 * Settings → Supporter (docs/SUPPORTER.md): whether this phone is a
 * Supporter, a field to redeem a code, and, in the Play edition only and
 * never in discreet mode, a way to buy it. Plain words, no nudging: every
 * safety feature is free either way, and the page says so.
 */
internal fun LazyListScope.supporterItems(onOpen: () -> Unit) {
    item(key = "§supporter-hdr") { SectionHeader(stringResource(R.string.supporter_title)) }
    item(key = "§supporter") {
        ListGroup {
            ListRow(
                title = stringResource(R.string.supporter_redeem_a_code),
                subtitle = stringResource(
                    if (Entitlement.isSupporter()) R.string.supporter_row_supporter else R.string.supporter_row_free
                ),
                onClick = onOpen,
                onClickLabel = stringResource(R.string.supporter_open)
            )
        }
    }
}

/** What a redeem or purchase outcome says, in words for the page. */
internal fun supporterRedeemMessage(context: Context, error: Throwable): String = when ((error as? RelayException)?.code) {
    400 -> context.getString(R.string.supporter_error_typo)
    404 -> context.getString(R.string.supporter_error_unknown)
    409 -> context.getString(R.string.supporter_error_used)
    429 -> context.getString(R.string.supporter_error_too_many)
    RelayClient.TOKEN_KEY_MISMATCH -> context.getString(R.string.supporter_error_key)
    else -> context.getString(R.string.supporter_error_network)
}

@Composable
internal fun SupporterPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val scope = rememberCoroutineScope()
    val comms by CommsRepository.state.collectAsStateWithLifecycle()
    val discreet by DiscreetMode.state.collectAsStateWithLifecycle()
    // Bumped after a redeem or purchase so the count below is read again.
    var refresh by remember { mutableIntStateOf(0) }
    val left = remember(refresh, comms.registered) { Entitlement.credentialsLeft() }

    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val normalised = SupporterCode.normalise(code)
    val complete = code.count { it.isLetterOrDigit() } >= SupporterCode.LENGTH

    val billing: SupporterBilling = remember {
        EditionBilling.create(context) { product, token -> CommsRepository.redeemPlayPurchase(product, token) }
    }
    DisposableEffect(billing) { onDispose { billing.close() } }
    val purchase by billing.state.collectAsStateWithLifecycle()
    var offer by remember { mutableStateOf<RelayClient.SupporterOffer?>(null) }
    var price by remember { mutableStateOf<String?>(null) }
    // The purchase is offered only where the edition sells it, never in
    // discreet mode, and only when the relay can check a purchase.
    val canBuy = billing.sells && !discreet.on && offer?.play == true
    LaunchedEffect(comms.registered) {
        offer = CommsRepository.supporterOffer()
        if (billing.sells) billing.settlePending()
    }
    LaunchedEffect(canBuy) { if (canBuy) price = billing.price() }
    LaunchedEffect(purchase) { if (purchase is SupporterPurchase.Added) refresh++ }

    Column(Modifier.fillMaxWidth()) {
        ClairixTopBar(title = stringResource(R.string.supporter_title), onBack = onBack)
        ScreenList(bottomPadding = 32) {
            item(key = "status") {
                ClairixCard(spacing = 8.dp) {
                    Text(
                        if (left > 0) pluralStringResource(R.plurals.supporter_status_yes, left, left) else stringResource(R.string.supporter_status_no),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.text
                    )
                    Text(stringResource(R.string.supporter_what_it_is), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                    Text(stringResource(R.string.supporter_safety_free), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                }
            }
            item(key = "redeem-hdr") { SectionHeader(stringResource(R.string.supporter_redeem_a_code)) }
            item(key = "redeem") {
                ClairixCard(spacing = 8.dp) {
                    Text(stringResource(R.string.supporter_redeem_explainer), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                    ClairixTextField(
                        value = code,
                        onValueChange = { code = SupporterCode.formatTyping(it); message = null },
                        label = stringResource(R.string.supporter_code_label),
                        placeholder = "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX",
                        keyboardType = KeyboardType.Ascii,
                        isError = complete && normalised == null,
                        supportingText = if (complete && normalised == null) stringResource(R.string.supporter_error_typo) else null,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PrimaryButton(
                        text = stringResource(R.string.supporter_redeem),
                        onClick = {
                            val ready = normalised ?: return@PrimaryButton
                            busy = true
                            message = null
                            scope.launch {
                                CommsRepository.redeemSupporterCode(ready)
                                    .onSuccess { n ->
                                        code = ""
                                        message = context.resources.getQuantityString(R.plurals.supporter_redeemed, n, n)
                                    }
                                    .onFailure { message = supporterRedeemMessage(context, it) }
                                busy = false
                                refresh++
                            }
                        },
                        enabled = normalised != null && comms.registered && !busy,
                        loading = busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!comms.registered) {
                        Text(stringResource(R.string.supporter_needs_comms), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text) }
                    Text(stringResource(R.string.supporter_privacy), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
                }
            }
            if (canBuy) {
                item(key = "buy-hdr") { SectionHeader(stringResource(R.string.supporter_google_play)) }
                item(key = "buy") {
                    ClairixCard(spacing = 8.dp) {
                        Text(stringResource(R.string.supporter_play_explainer), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                        SecondaryButton(
                            text = price?.let { stringResource(R.string.supporter_buy_price, it) } ?: stringResource(R.string.supporter_buy),
                            onClick = { context.findActivity()?.let { billing.buy(it) } },
                            tone = Tone.Accent,
                            enabled = comms.registered && purchase !is SupporterPurchase.Working,
                            modifier = Modifier.fillMaxWidth()
                        )
                        purchaseText(purchase)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun purchaseText(p: SupporterPurchase): String? = when (p) {
    SupporterPurchase.Idle -> null
    SupporterPurchase.Working -> stringResource(R.string.supporter_purchase_working)
    SupporterPurchase.Pending -> stringResource(R.string.supporter_purchase_pending)
    is SupporterPurchase.Added -> pluralStringResource(R.plurals.supporter_redeemed, p.credentials, p.credentials)
    is SupporterPurchase.Failed -> p.reason
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
