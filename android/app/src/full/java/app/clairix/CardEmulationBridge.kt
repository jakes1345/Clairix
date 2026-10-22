package app.clairix

import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation

/**
 * The full edition's link between the NFC tab and [CardEmulationService].
 *
 * MainActivity only ever talks to the service through this object, so the Play
 * edition can swap in its own (src/play), which has no service behind it, and
 * the service class, its manifest entry and its AID resource stay out of that
 * build entirely.
 */
internal object CardEmulationBridge {

    /** The service Android routes reader commands to while a card is armed. */
    fun component(context: Context): ComponentName =
        ComponentName(context, CardEmulationService::class.java)

    /** Loads a card's recorded command/response pairs; false if they cannot be parsed. */
    fun load(hexPairs: List<Pair<String, String>>): Boolean =
        runCatching { CardEmulationService.loadPairs(hexPairs) }.isSuccess

    /** Forgets the loaded pairs, so a reader that still reaches the service gets nothing. */
    fun clear() {
        CardEmulationService.activePairs = emptyList()
    }

    /**
     * Stops an armed card answering readers at all: the pairs are forgotten and
     * the AIDs registered for it are withdrawn (they otherwise persist across
     * reboots). Safe from any context; used when the app locks or wipes.
     */
    fun disarm(context: Context) {
        clear()
        val adapter = NfcAdapter.getDefaultAdapter(context.applicationContext) ?: return
        runCatching { CardEmulation.getInstance(adapter).removeAidsForService(component(context), CardEmulation.CATEGORY_OTHER) }
    }
}
