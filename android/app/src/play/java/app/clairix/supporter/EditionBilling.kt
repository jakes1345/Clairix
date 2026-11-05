package app.clairix.supporter

import android.app.Activity
import android.content.Context
import app.clairix.comms.RelayException
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The Play edition sells Supporter through Google Play Billing ([PlaySupporterBilling]). */
object EditionBilling {
    fun create(context: Context, exchange: suspend (productId: String, purchaseToken: String) -> Result<Int>): SupporterBilling =
        PlaySupporterBilling(context, exchange)
}

/**
 * Clairix Supporter through Google Play Billing, in the Play edition only.
 *
 * The product is a one-time, consumable in-app product the maintainer
 * creates in Play Console as [PRODUCT_SUPPORTER], named "Clairix Supporter"
 * (docs/SUPPORTER.md). A completed purchase is handed to [exchange], which
 * sends its token to the relay on a request that names nobody; the relay
 * checks it with Google and answers with blind Supporter credentials. Only
 * then is the purchase consumed, so one the app could not exchange (offline,
 * closed half-way) is found again by [settlePending] and exchanged later.
 * A purchase the relay says it has already exchanged is consumed too: its
 * credentials were issued before.
 */
class PlaySupporterBilling(
    context: Context,
    private val exchange: suspend (productId: String, purchaseToken: String) -> Result<Int>,
) : SupporterBilling, PurchasesUpdatedListener {

    override val sells: Boolean = true

    private val _state = MutableStateFlow<SupporterPurchase>(SupporterPurchase.Idle)
    override val state: StateFlow<SupporterPurchase> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    @Volatile private var details: ProductDetails? = null

    private suspend fun connected(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun productDetails(): ProductDetails? {
        details?.let { return it }
        if (!connected()) return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_SUPPORTER)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        ).build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                val d = found.productDetailsList.firstOrNull { it.productId == PRODUCT_SUPPORTER }
                    .takeIf { result.responseCode == BillingClient.BillingResponseCode.OK }
                if (d != null) details = d
                if (cont.isActive) cont.resume(d)
            }
        }
    }

    override suspend fun price(): String? = productDetails()?.oneTimePurchaseOfferDetails?.formattedPrice

    override fun buy(activity: Activity) {
        _state.value = SupporterPurchase.Working
        scope.launch {
            val d = productDetails()
            if (d == null) {
                _state.value = SupporterPurchase.Failed("Google Play is not available right now.")
                return@launch
            }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                .build()
            activity.runOnUiThread {
                val result = client.launchBillingFlow(activity, params)
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    _state.value = SupporterPurchase.Failed("Google Play could not start the purchase.")
                }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { settle(purchases.orEmpty()) }
            BillingClient.BillingResponseCode.USER_CANCELED -> _state.value = SupporterPurchase.Idle
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> settlePending()
            else -> _state.value = SupporterPurchase.Failed("The purchase did not go through.")
        }
    }

    override fun settlePending() {
        scope.launch {
            if (!connected()) return@launch
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            val owned = suspendCancellableCoroutine { cont ->
                client.queryPurchasesAsync(params) { result, list ->
                    if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList())
                }
            }
            if (owned.isNotEmpty()) settle(owned)
        }
    }

    /** Exchanges each completed Supporter purchase with the relay, then consumes it. */
    private suspend fun settle(purchases: List<Purchase>) {
        val ours = purchases.filter { PRODUCT_SUPPORTER in it.products }
        if (ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }) _state.value = SupporterPurchase.Pending
        var added = 0
        var failure: String? = null
        for (p in ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
            _state.value = SupporterPurchase.Working
            exchange(PRODUCT_SUPPORTER, p.purchaseToken)
                .onSuccess { n ->
                    added += n
                    consume(p)
                }
                .onFailure { e ->
                    // Exchanged before (the app stopped before consuming): the credentials were issued then.
                    if ((e as? RelayException)?.code == HTTP_CONFLICT) consume(p)
                    else failure = "Paid, but your relay could not be reached to finish. It will try again: ${e.message}"
                }
        }
        val failed = failure
        _state.value = when {
            failed != null -> SupporterPurchase.Failed(failed)
            added > 0 -> SupporterPurchase.Added(added)
            else -> _state.value.takeIf { it is SupporterPurchase.Pending } ?: SupporterPurchase.Idle
        }
    }

    private suspend fun consume(p: Purchase) {
        suspendCancellableCoroutine { cont ->
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun close() {
        scope.cancel()
        client.endConnection()
    }

    companion object {
        /**
         * The Play Console product id: a one-time, consumable in-app product
         * named "Clairix Supporter". The relay accepts the same id
         * (comms-worker/src/supporter.ts PLAY_PRODUCT_IDS).
         */
        const val PRODUCT_SUPPORTER = "clairix_supporter"
        private const val HTTP_CONFLICT = 409
    }
}
