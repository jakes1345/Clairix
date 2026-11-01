package app.clairix.comms

import org.json.JSONObject

/**
 * The ClairixCoin decisions the app makes, kept apart from the repository and
 * the screens. Pure Kotlin, so the JVM tests use exactly this code.
 */
object CoinRules {

    /**
     * Whether this phone shows ClairixCoin (the balance and the Pay button) and
     * asks the relay about it at all: only in an edition built with it
     * ([edition], `BuildConfig.CLAIRIX_COIN`, false in the Play edition), once
     * it has a Clairix number ([registered]), and only while the relay offers
     * it ([relayOffers]: what the relay's `GET /` last said, see [offered]).
     */
    fun shown(edition: Boolean, registered: Boolean, relayOffers: Boolean): Boolean = edition && registered && relayOffers

    /**
     * Whether the relay's description of itself ([root], the answer to its
     * `GET /`) leaves ClairixCoin on: everything but `"coin": false`, which a
     * relay says when its operator switched the coin off. A relay from before
     * the flag does not say, and offers the coin, as every relay did; if one
     * that says nothing has it off after all, its coin routes answer 404 and
     * the app forgets the coin then.
     */
    fun offered(root: JSONObject): Boolean = root.opt("coin") != false

    /**
     * Whether the relay's record of this account ([history]) confirms a payment
     * notice: a credit under the notice's [txid] for its [amount]. The relay
     * keeps no record of who paid whom; the payer is the contact whose
     * end-to-end encrypted notice named the txid. A credit this phone already
     * counted for another notice ([alreadyCounted]) confirms nothing again, so
     * one payment cannot be shown as received from two people.
     */
    fun confirms(history: List<RelayClient.CoinTx>, txid: String, amount: Long, alreadyCounted: Boolean): Boolean =
        !alreadyCounted && history.any { it.id == txid && it.direction == "in" && it.amount == amount }
}
