package app.clairix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.mono
import java.util.Locale

/**
 * The basic Clairix surface: a chamfered card on the theme's surface colour.
 *
 * With a [tone] the card carries a short status bar at its leading edge; with
 * [emphasis] as well, the whole card takes the tone's tinted container and a
 * toned border instead, for the one finding a screen leads with.
 */
@Composable
fun ClairixCard(
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    emphasis: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = ClairixTheme.colors
    val shape = ClairixShapes.Card
    val filled = emphasis && tone != null
    val background = if (filled) tone!!.container() else colors.surface
    val stroke = if (filled) tone!!.color().copy(alpha = 0.55f) else colors.border
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .border(1.dp, stroke, shape)
            .then(
                if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .height(IntrinsicSize.Min)
    ) {
        if (tone != null && !filled) {
            Box(
                Modifier
                    .padding(start = 12.dp, top = 16.dp, bottom = 16.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(tone.color())
            )
        }
        Column(
            Modifier.weight(1f).padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

/** A short status label, set in the brand's caps label style on the tone's tint. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val fg = tone.color()
    Row(
        modifier
            .clip(ClairixShapes.Tag)
            .background(tone.container())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).background(fg, ClairixShapes.Tag))
        Text(
            text.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * A section heading: the accent tick, then the title in the caps label style.
 * [supporting] sits at the far end in quiet text; [action] replaces it with a
 * control, such as a ghost button.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    tone: Tone = Tone.Accent,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.width(12.dp).height(3.dp).background(tone.color()))
        Text(
            title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = if (tone == Tone.Accent) ClairixTheme.colors.textMuted else tone.color(),
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        when {
            action != null -> action()
            supporting != null -> Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.textSubtle,
                maxLines = 1
            )
        }
    }
}

/** A label on the left and a machine value on the right, for detail lists. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    mono: Boolean = true
) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            modifier = Modifier.padding(end = 16.dp)
        )
        Text(
            value,
            style = if (mono) MaterialTheme.typography.mono else MaterialTheme.typography.bodyMedium,
            color = valueColor ?: ClairixTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * An inline message in a tone: an icon, an optional bold [title], then the
 * text. Warnings and danger take the warning icon, everything else the info icon.
 */
@Composable
fun Notice(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    val fg = tone.color()
    val shape = ClairixShapes.Card
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.container(), shape)
            .border(1.dp, fg.copy(alpha = 0.4f), shape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon ?: if (tone == Tone.Caution || tone == Tone.Danger) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
            if (action != null) action()
        }
    }
}

/** One number and what it counts, for a row of figures at the top of a screen. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    onClick: (() -> Unit)? = null
) {
    val shape = ClairixShapes.Card
    Column(
        modifier
            .clip(shape)
            .background(ClairixTheme.colors.surface, shape)
            .border(1.dp, ClairixTheme.colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 88.dp)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = if (tone == Tone.Neutral) ClairixTheme.colors.text else tone.color(),
            maxLines = 1
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle, maxLines = 2)
    }
}

/** A hairline between rows inside a card. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ClairixTheme.colors.border))
}

/**
 * An icon in an octagonal well, the shape of the mark. Used to lead list rows
 * and category tiles.
 */
@Composable
fun IconWell(icon: ImageVector, tone: Tone, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier
            .size(size)
            .background(tone.container(), ClairixShapes.Octagon),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(size * 0.5f))
    }
}
