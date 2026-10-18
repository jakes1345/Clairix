package app.clairix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.ClairixThemeName

/** What each theme is, in the brand book's words. */
fun ClairixThemeName.blurb(): String = when (this) {
    ClairixThemeName.ACID -> "Black, bone and acid green. The brand's own colour."
    ClairixThemeName.INFRARED -> "Carbon and steel with an infrared red."
    ClairixThemeName.HAZARD -> "Workwear black, bone and hazard orange."
    ClairixThemeName.VOLT -> "Deep night violet with an electric signal."
}

/**
 * A live preview of one colour theme, for the picker in Settings > Appearance.
 * The miniature is drawn inside its own [ClairixTheme], so it shows exactly what
 * the app looks like in that theme whichever one is active. The whole swatch is
 * one radio button for accessibility.
 */
@Composable
fun ThemeSwatch(
    theme: ClairixThemeName,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The frame and label use the active theme, so selection reads consistently.
    val active = ClairixTheme.colors
    Column(
        modifier
            .clip(ClairixShapes.Card)
            .background(active.surface, ClairixShapes.Card)
            .border(if (selected) 2.dp else 1.dp, if (selected) active.accent else active.border, ClairixShapes.Card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics(mergeDescendants = true) {
                contentDescription = "${theme.label} theme. ${theme.blurb()}"
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ClairixTheme(theme = theme) {
            ThemeMiniature()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                theme.label,
                style = MaterialTheme.typography.titleMedium,
                color = active.text,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Box(
                    Modifier.size(24.dp).background(active.accent, ClairixShapes.Octagon),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = active.onAccent, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(theme.blurb(), style = MaterialTheme.typography.bodySmall, color = active.textSubtle, minLines = 2)
    }
}

/** A tiny scan screen: a status card, two finding rows and the main button. */
@Composable
private fun ThemeMiniature() {
    val c = ClairixTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ClairixShapes.Card)
            .background(c.base)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Status card: the accent tick and a headline bar.
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface, ClairixShapes.Tag)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.width(18.dp).height(3.dp).background(c.accent))
            Box(Modifier.width(56.dp).height(8.dp).background(c.text))
            Box(Modifier.width(80.dp).height(4.dp).background(c.textSubtle))
        }
        MiniRow(c.danger)
        MiniRow(c.safe)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(c.accent, ClairixShapes.Tag),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.width(32.dp).height(3.dp).background(c.onAccent))
        }
    }
}

@Composable
private fun MiniRow(status: androidx.compose.ui.graphics.Color) {
    val c = ClairixTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, ClairixShapes.Tag)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(5.dp).background(status))
        Box(Modifier.weight(1f).height(4.dp).padding(end = 24.dp).background(c.textMuted))
        Box(Modifier.width(16.dp).height(6.dp).background(status.copy(alpha = 0.35f)))
    }
}
