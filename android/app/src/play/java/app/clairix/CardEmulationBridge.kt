package app.clairix

import android.content.ComponentName
import android.content.Context

/**
 * The Play edition has no card emulation: no HostApduService, no manifest entry
 * and no AID resource. This stands in for the full edition's bridge (src/fullSuite)
 * so the suite's NFC code (src/suite) compiles unchanged; with no component, the
 * NFC tab never asks Android for card emulation and stays a tag reader.
 */
internal object CardEmulationBridge {

    @Suppress("UNUSED_PARAMETER")
    fun component(context: Context): ComponentName? = null

    @Suppress("UNUSED_PARAMETER")
    fun load(hexPairs: List<Pair<String, String>>): Boolean = false

    fun clear() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun disarm(context: Context) = Unit
}
