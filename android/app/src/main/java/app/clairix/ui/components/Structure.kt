package app.clairix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clairix.ui.theme.ClairixTheme
import java.util.Locale

// ── Bars ─────────────────────────────────────────────────────────────────────

/**
 * The app bar. A top-level screen shows the logo mark and its title in the
 * display face; a nested screen swaps the mark for a back arrow. [actions] sit
 * at the end, each at least 48 dp.
 */
@Composable
fun ClairixTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val c = ClairixTheme.colors
    Column(modifier.fillMaxWidth().background(c.base).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
                }
            } else {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    LogoMark(size = 28.dp, contentDescription = null)
                }
            }
            Column(
                Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading(); contentDescription = title }
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSubtle, maxLines = 1)
                }
            }
            actions()
        }
    }
}

/** One destination in [ClairixBottomBar]. */
class BottomBarItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val badge: Boolean,
    val onClick: () -> Unit
)

/**
 * The bottom navigation. The selected tab carries the accent and a chamfered
 * bar on its top edge; a tab with something new carries a danger dot.
 */
@Composable
fun ClairixBottomBar(items: List<BottomBarItem>, modifier: Modifier = Modifier) {
    val c = ClairixTheme.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val fg = if (item.selected) c.accent else c.textSubtle
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
                        .semantics {
                            contentDescription = item.label
                            if (item.badge) stateDescription = "New activity"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(3.dp)
                            .background(if (item.selected) c.accent else c.surface, ClairixShapes.Tag)
                    )
                    Spacer(Modifier.height(10.dp))
                    Box {
                        Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
                        if (item.badge) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(start = 0.dp)
                                    .size(9.dp)
                                    .border(2.dp, c.surface, ClairixShapes.Tag)
                                    .background(c.danger, ClairixShapes.Tag)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
                }
            }
        }
    }
}

// ── Lists ────────────────────────────────────────────────────────────────────

/**
 * The scrolling body every screen shares: 16 dp side margins, 8 dp between
 * items, and room at the bottom so the last card clears the floating SOS button.
 */
@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: Int = 96,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/**
 * A list row: optional icon well, a title and a supporting line, then a trailing
 * slot (a pill, a value) and a chevron when the row opens something.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.Neutral,
    subtitleTone: Tone? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = ClairixTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .rowClick(onClick, onClickLabel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (icon != null) IconWell(icon, iconTone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleTone?.color() ?: c.textSubtle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) trailing()
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.textSubtle)
        }
    }
}

/** A group of [ListRow]s in one chamfered card, divided by hairlines. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ClairixTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ClairixShapes.Card)
            .background(c.surface, ClairixShapes.Card)
            .border(1.dp, c.border, ClairixShapes.Card),
        content = content
    )
}

// ── States ───────────────────────────────────────────────────────────────────

/** What a screen shows when there is nothing to list: what it means and what to do. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.Neutral,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val c = ClairixTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ClairixShapes.Card)
            .border(1.dp, c.border, ClairixShapes.Card)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) {
            IconWell(icon, tone, size = 56.dp)
            Spacer(Modifier.height(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(actionLabel, onAction, tone = Tone.Accent)
        }
    }
}

/** Work in progress: what is happening, and a bar that runs until it is done. */
@Composable
fun LoadingBlock(text: String, modifier: Modifier = Modifier, progress: Float? = null) {
    ClairixCard(modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        ClairixProgressBar(progress)
    }
}

/**
 * A flat, square-ended progress bar. With [progress] null it runs
 * indeterminately. [tone] colours the fill.
 */
@Composable
fun ClairixProgressBar(progress: Float?, modifier: Modifier = Modifier, tone: Tone = Tone.Accent) {
    val c = ClairixTheme.colors
    val bar = modifier.fillMaxWidth().height(6.dp)
    if (progress == null) {
        LinearProgressIndicator(
            modifier = bar,
            color = tone.color(),
            trackColor = c.border,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = bar,
            color = tone.color(),
            trackColor = c.border,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

/**
 * The app's dialog: a sentence-case title, plain body text, and a verb on the
 * confirm button in [confirmTone]. A destructive confirm takes [Tone.Danger].
 */
@Composable
fun ClairixDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    confirmTone: Tone = Tone.Accent,
    dismissLabel: String? = "Cancel",
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val c = ClairixTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = ClairixShapes.Sheet,
        containerColor = c.surfaceRaised,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = if (text == null && content == null) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
                    content?.invoke(this)
                }
            }
        },
        confirmButton = {
            ClairixButton(confirmLabel, onConfirm, kind = ButtonKind.Primary, tone = confirmTone, enabled = confirmEnabled)
        },
        dismissButton = dismissLabel?.let { label ->
            { GhostButton(label, onDismiss, tone = Tone.Neutral) }
        }
    )
}

// ── Hero ─────────────────────────────────────────────────────────────────────

/**
 * The status that leads a screen: a caps eyebrow, a big display headline in the
 * tone, and one calm line underneath on what it means. Attitude up top, clarity
 * below. [trailing] takes the logo mark or a stat; [footer] takes actions.
 */
@Composable
fun StatusHero(
    eyebrow: String,
    headline: String,
    body: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val c = ClairixTheme.colors
    val fg = if (tone == Tone.Neutral) c.text else tone.color()
    Column(
        modifier
            .fillMaxWidth()
            .clip(ClairixShapes.Hero)
            .background(c.surface, ClairixShapes.Hero)
            .border(1.dp, if (tone == Tone.Neutral) c.border else fg.copy(alpha = 0.45f), ClairixShapes.Hero)
            .semantics(mergeDescendants = true) {}
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(fg, ClairixShapes.Tag))
                    Text(
                        eyebrow.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted
                    )
                }
                // Short verdicts get the full display size; longer ones step down
                // so they wrap to two lines at most on a phone.
                Text(
                    headline.uppercase(Locale.getDefault()),
                    style = if (headline.length <= 10) MaterialTheme.typography.displayMedium
                    else MaterialTheme.typography.headlineLarge,
                    color = fg,
                    modifier = Modifier.stencilSlit(c.surface, thickness = 3.dp)
                )
            }
            if (trailing != null) trailing()
        }
        Text(body, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        footer?.invoke(this)
    }
}
