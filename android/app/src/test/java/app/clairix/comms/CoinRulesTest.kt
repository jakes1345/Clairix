package app.clairix.comms

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClairixCoin on the phone: the balance and the Pay button only where the relay
 * offers the coin, and a payment notice confirmed by the relay's record of the
 * payment's id and amount, which names nobody.
 */
class CoinRulesTest {

    @Test
    fun theCoinShowsOnlyInTheFullEditionOnceRegisteredOnARelayThatOffersIt() {
        assertTrue(CoinRules.shown(edition = true, registered = true, relayOffers = true))
        // The Play edition is built without it, whatever the relay offers.
        assertFalse(CoinRules.shown(edition = false, registered = true, relayOffers = true))
        // No Clairix number yet: no relay to ask.
        assertFalse(CoinRules.shown(edition = true, registered = false, relayOffers = true))
        // A relay whose operator has not turned it on, or that has not said.
        assertFalse(CoinRules.shown(edition = true, registered = true, relayOffers = false))
        assertFalse(CoinRules.shown(edition = false, registered = false, relayOffers = false))
    }

    @Test
    fun aRelayOffersTheCoinOnlyWhenItsDescriptionSaysCoinTrue() {
        val described = JSONObject(
            """{"service":"clairix-comms","version":5,"ok":true,"alternates":[],"onion":null,"relayKey":null,"federation":"open","tokens":null,"coin":true}"""
        )
        assertTrue(CoinRules.offered(described))
        // Only a relay whose operator switched the coin off says so.
        assertFalse(CoinRules.offered(JSONObject().put("coin", false)))
        // A relay from before the flag says nothing, and offers the coin, as every relay did.
        assertTrue(CoinRules.offered(JSONObject("""{"service":"clairix-comms","version":4,"ok":true}""")))
        assertTrue(CoinRules.offered(JSONObject("""{"coin":null}""")))
    }

    private val txid = "0f0e0d0c-0b0a-4908-8706-050403020100"
    private val history = listOf(
        RelayClient.CoinTx("1f0e0d0c-0b0a-4908-8706-050403020100", 3, 1_790_000_000_000, "out"),
        RelayClient.CoinTx(txid, 7, 1_790_000_000_000, "in"),
        RelayClient.CoinTx("2f0e0d0c-0b0a-4908-8706-050403020100", 9, 1_789_996_400_000, "in"),
    )

    @Test
    fun aNoticeIsConfirmedByACreditUnderItsTxidForItsAmount() {
        assertTrue(CoinRules.confirms(history, txid, 7, alreadyCounted = false))
    }

    @Test
    fun aNoticeForAnotherAmountOrAPaymentTheRelayDoesNotHoldIsNotConfirmed() {
        assertFalse(CoinRules.confirms(history, txid, 8, alreadyCounted = false))
        assertFalse(CoinRules.confirms(history, "3f0e0d0c-0b0a-4908-8706-050403020100", 7, alreadyCounted = false))
        assertFalse(CoinRules.confirms(emptyList(), txid, 7, alreadyCounted = false))
    }

    @Test
    fun aPaymentThisPhoneMadeIsNoProofOfOneItReceived() {
        assertFalse(CoinRules.confirms(history, "1f0e0d0c-0b0a-4908-8706-050403020100", 3, alreadyCounted = false))
    }

    @Test
    fun oneCreditConfirmsOneNoticeOnly() {
        // The relay no longer says who paid, so a second contact naming the same
        // payment (its id leaked, or the two together) is not thanked for it too.
        assertFalse(CoinRules.confirms(history, txid, 7, alreadyCounted = true))
    }
}
