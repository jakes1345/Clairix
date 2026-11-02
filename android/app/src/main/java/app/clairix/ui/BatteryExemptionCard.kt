package app.clairix.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.Tone
import java.util.Locale

/*
 * Battery exemption: the one thing an owner can do so that Android, and the
 * maker's own battery manager on top of it, leaves Clairix running in the
 * background. The scanner on the Scan hub and the relay connection in Comms
 * both depend on it, so the check, the request and the card live here and each
 * screen shows the card with its own reason.
 */

/**
 * Whether Android exempts Clairix from battery optimisation, re-read whenever the
 * screen resumes (the owner comes back from the system settings page).
 */
@Composable
fun rememberBatteryUnrestricted(): State<Boolean> {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember { mutableStateOf(isBatteryUnrestricted(context, power)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted.value = isBatteryUnrestricted(context, power)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return unrestricted
}

private fun isBatteryUnrestricted(context: Context, power: PowerManager?): Boolean =
    runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)

/**
 * The maker's own switch that also has to be set, where the phone has one.
 * Android's exemption is not enough on these: their battery managers put an app
 * to sleep from a list of their own, under a menu that is theirs, not Android's.
 * Null for a phone with no such layer.
 */
internal fun makerBackgroundHint(manufacturer: String?): String? =
    when (manufacturer?.trim()?.lowercase(Locale.US)) {
        "samsung" ->
            "On this Samsung phone also open Settings > Battery > Background usage limits and add Clairix to " +
                "Never sleeping apps (older One UI versions put Battery under Battery and device care, and the names " +
                "vary a little between versions)."
        "xiaomi", "redmi", "poco" ->
            "On this Xiaomi phone also turn on Autostart for Clairix under Settings > Apps > Manage apps > Clairix, " +
                "and set its Battery saver to No restrictions."
        else -> null
    }

/**
 * What else to set beyond Android's own exemption, for the Comms screen: the
 * maker's switch where Clairix knows its path ([makerBackgroundHint]), and
 * otherwise where such switches are kept, without guessing at what a maker
 * calls them.
 */
internal fun makerBackgroundSteps(manufacturer: String?): String =
    makerBackgroundHint(manufacturer)
        ?: ("Some phone makers add a battery manager of their own on top of Android's, with menus of their own: " +
            "look on Clairix's App info page and in the phone's battery settings for anything about background " +
            "activity, starting automatically or sleeping apps, and allow it for Clairix.")

/**
 * Asks the owner to let Clairix run unrestricted: Android's one-tap dialog, and
 * the maker's own switch where the phone has one. [reason] says what stops
 * working otherwise, in the words of the screen showing the card.
 */
@Composable
fun BatteryExemptionCard(reason: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hint = remember { makerBackgroundHint(Build.MANUFACTURER) }
    Notice(
        Tone.Caution,
        text = if (hint == null) reason else "$reason $hint",
        modifier = modifier,
        title = "Let Clairix run in the background",
        action = { GhostButton("Allow", onClick = { openBatterySettings(context) }) }
    )
}

/**
 * Asks Android to let Clairix run unrestricted. The direct request shows a
 * one-tap system dialog; where a phone does not offer it, the battery
 * optimisation list opens instead so the owner can find Clairix there.
 */
fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(list) }
}
