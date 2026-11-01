package app.clairix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.security.DiscreetMode
import app.clairix.security.LauncherPlan
import app.clairix.ui.components.ChoiceChips
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.theme.ClairixTheme

/** What the Discreet mode switch's subtitle says, for an edition that may or may not disguise its icon. */
internal fun discreetModeSubtitle(on: Boolean, disguiseAllowed: Boolean, disguiseLabel: String): String {
    val icon = when {
        !disguiseAllowed -> "The Clairix icon and name stay on the home screen: Google Play does not allow an app " +
            "to disguise its icon, so this edition does the rest without it. "
        on -> "The home screen shows \"$disguiseLabel\" instead of Clairix. "
        else -> "The home screen can show a plain \"Compass\" or \"Notes\" instead of Clairix. "
    }
    val rest = if (on) {
        "Alerts say only \"New notification\" and running services only \"Running\", never what was found, whatever " +
            "the alert details setting says; messages and incoming and missed calls name no one and quote nothing; notification categories " +
            "have plain names; the lock screen shows a plain prompt and the keypad, no logo; and Clairix's own " +
            "explanation on the device-admin request does not name it. An SOS from your group still shows as an SOS."
    } else {
        "Alerts would say only \"New notification\" and running services only \"Running\", messages and incoming calls would " +
            "name no one, notification categories would have plain names, the lock screen would show a plain prompt and " +
            "the keypad, no logo, and Clairix's own explanation on the device-admin request would not name it."
    }
    return icon + rest
}

/** What discreet mode cannot hide, said under the switch in every edition. */
internal const val DISCREET_LIMITS =
    "Android's own screens still name Clairix: Settings, Apps, the device-admin list, the device-admin " +
        "request (its heading and icon, above Clairix's explanation) and the notification settings page all show " +
        "the package's name, which no app can change. When the icon changes, the old one " +
        "may disappear from the home screen: add the new one from the app list. Notifications already showing change " +
        "when they next update."

/** How to leave in a hurry, in every edition. */
internal const val QUICK_EXIT_TEXT =
    "Quick exit, on or off: hold the mark at the top left, or press Back on the first screen. Clairix goes to the " +
        "background at once and, with a passcode set, locks."

/** Settings → Discreet mode: the switch, the disguise where the edition allows one, and what it cannot hide. */
internal fun LazyListScope.discreetModeItems() {
    item(key = "§discreet-hdr") { SectionHeader("Discreet mode") }
    item(key = "§discreet") { DiscreetModeCard() }
}

@Composable
private fun DiscreetModeCard() {
    val context = LocalContext.current
    val state by DiscreetMode.state.collectAsStateWithLifecycle()
    val c = ClairixTheme.colors
    ClairixCard(spacing = 8.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        ToggleRow(
            title = "Discreet mode",
            subtitle = discreetModeSubtitle(state.on, DiscreetMode.disguiseAllowed, state.disguise.label),
            checked = state.on,
            onCheckedChange = { v -> DiscreetMode.set(context, v, state.disguise) }
        )
        if (DiscreetMode.disguiseAllowed) {
            Text("Icon and name on the home screen", style = MaterialTheme.typography.titleSmall, color = c.text)
            ChoiceChips(
                choices = LauncherPlan.DISGUISES,
                selected = state.disguise,
                label = { it.label },
                onSelect = { choice -> DiscreetMode.set(context, state.on, choice) }
            )
        }
        Hairline()
        Text(DISCREET_LIMITS, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        Text(QUICK_EXIT_TEXT, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
    }
}
