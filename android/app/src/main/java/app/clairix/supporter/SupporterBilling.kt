package app.clairix.supporter

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Buying Clairix Supporter (docs/SUPPORTER.md), where the edition sells it.
 *
 * Only the Google Play edition does ([EditionBilling] in src/play, Google Play
 * Billing); the full and foss editions get [NoSupporterBilling] and carry no
 * billing code at all. A purchase never makes the phone a Supporter by
 * itself: it is exchanged with the relay, on a request that names nobody,
 * for the same blind credentials a code brings, and only once that has
 * worked is it consumed. [Entitlement] then answers from the credentials.
 */
interface SupporterBilling {

    /** Whether this build can sell Supporter at all. */
    val sells: Boolean

    /** Where a purchase has got to, for the Supporter page. */
    val state: StateFlow<SupporterPurchase>

    /** The product's price as Google Play shows it to this person, or null when it cannot be had now. */
    suspend fun price(): String?

    /** Opens Google Play's purchase sheet. The outcome arrives in [state]. */
    fun buy(activity: Activity)

    /** Exchanges any purchase made but not yet exchanged (the app was closed half-way, a payment cleared later). */
    fun settlePending()

    fun close()
}

/** Where a Supporter purchase has got to. */
sealed interface SupporterPurchase {
    data object Idle : SupporterPurchase
    data object Working : SupporterPurchase
    /** Paid, but the payment has not cleared yet (a cash payment, say); exchanged once it does. */
    data object Pending : SupporterPurchase
    data class Added(val credentials: Int) : SupporterPurchase
    data class Failed(val reason: String) : SupporterPurchase
}

/** The editions that sell nothing: full and foss. */
object NoSupporterBilling : SupporterBilling {
    override val sells: Boolean = false
    override val state: StateFlow<SupporterPurchase> = kotlinx.coroutines.flow.MutableStateFlow(SupporterPurchase.Idle)
    override suspend fun price(): String? = null
    override fun buy(activity: Activity) = Unit
    override fun settlePending() = Unit
    override fun close() = Unit
}
