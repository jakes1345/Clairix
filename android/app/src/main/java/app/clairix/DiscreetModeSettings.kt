package app.clairix

import android.content.res.Resources
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.security.DiscreetMode
import app.clairix.security.LauncherPlan
import app.clairix.ui.appResources
import app.clairix.ui.components.ChoiceChips
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.theme.ClairixTheme

/** What the Discreet mode switch's subtitle says, for an edition that may or may not disguise its icon. */
internal fun discreetModeSubtitle(res: Resources, on: Boolean, disguiseAllowed: Boolean, disguiseLabel: String): String {
    val icon = when {
        !disguiseAllowed -> res.getString(R.string.settings_discreet_icon_play)
        on -> res.getString(R.string.settings_discreet_icon_on, disguiseLabel)
        else -> res.getString(R.string.settings_discreet_icon_off)
    }
    val rest = res.getString(if (on) R.string.settings_discreet_rest_on else R.string.settings_discreet_rest_off)
    return icon + rest
}

/** Settings → Discreet mode: the switch, the disguise where the edition allows one, and what it cannot hide. */
internal fun LazyListScope.discreetModeItems() {
    item(key = "§discreet-hdr") { SectionHeader(stringResource(R.string.settings_discreet_mode)) }
    item(key = "§discreet") { DiscreetModeCard() }
}

@Composable
private fun DiscreetModeCard() {
    val context = LocalContext.current
    val state by DiscreetMode.state.collectAsStateWithLifecycle()
    val c = ClairixTheme.colors
    ClairixCard(spacing = 8.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        ToggleRow(
            title = stringResource(R.string.settings_discreet_mode),
            subtitle = discreetModeSubtitle(appResources(), state.on, DiscreetMode.disguiseAllowed, state.disguise.label),
            checked = state.on,
            onCheckedChange = { v -> DiscreetMode.set(context, v, state.disguise) }
        )
        if (DiscreetMode.disguiseAllowed) {
            Text(stringResource(R.string.settings_icon_and_name_on_the_home), style = MaterialTheme.typography.titleSmall, color = c.text)
            ChoiceChips(
                choices = LauncherPlan.DISGUISES,
                selected = state.disguise,
                label = { it.label },
                onSelect = { choice -> DiscreetMode.set(context, state.on, choice) }
            )
        }
        Hairline()
        Text(stringResource(R.string.settings_discreet_limits), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        Text(stringResource(R.string.settings_quick_exit), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
    }
}
