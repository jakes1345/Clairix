package app.clairix

import android.content.ComponentName
import android.content.Context

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
}
