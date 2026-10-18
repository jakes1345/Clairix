package app.clairix

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.clairix.ui.components.ClairixTopBar
import app.clairix.ui.components.ScreenList
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * What the on-demand tool pages share beyond the component kit
 * (app.clairix.ui.components): the page skeleton, the time formats, the mapping
 * from a finding's severity to a status tone, and a ticking clock. The pages
 * themselves live with the category they belong to.
 */

private val tFullFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val tDayFmt  = SimpleDateFormat("MMM d yyyy", Locale.US)
private val tTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

internal fun tFull(ts: Long): String = tFullFmt.format(Date(ts))
internal fun tDay(ts: Long): String = tDayFmt.format(Date(ts))
internal fun tTime(ts: Long): String = tTimeFmt.format(Date(ts))

/** How worrying a finding of this severity is, as a status tone. */
internal fun Severity.tone(): Tone = when (this) {
    Severity.CRITICAL, Severity.HIGH -> Tone.Danger
    Severity.MEDIUM -> Tone.Caution
    Severity.LOW -> Tone.Neutral
}

/** How worrying this threat level is, as a status tone. NONE reads as checked and clear. */
internal fun Threat.tone(): Tone = when (this) {
    Threat.CRITICAL, Threat.HIGH -> Tone.Danger
    Threat.MEDIUM -> Tone.Caution
    Threat.LOW -> Tone.Neutral
    Threat.NONE -> Tone.Safe
}

/** A severity or threat name as people say it: "High", not "HIGH". */
internal fun levelLabel(name: String): String =
    name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }

/** Safe at 0, caution at 0.5, danger at 1, in the selected theme's colours. */
@Composable
internal fun meterColor(t: Float): Color {
    val c = ClairixTheme.colors
    val f = t.coerceIn(0f, 1f)
    return if (f <= 0.5f) lerp(c.safe, c.caution, f * 2f) else lerp(c.caution, c.danger, (f - 0.5f) * 2f)
}

/**
 * The page skeleton every tool shares. A page shown on its own screen passes
 * [onBack] and gets the app bar with [title]; a page shown under the shell's top
 * bar, which already carries the title and the back arrow, passes null and
 * starts straight with its content.
 */
@Composable
internal fun ToolPageList(title: String, onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    if (onBack == null) {
        ScreenList(content = content)
    } else {
        Column(Modifier.fillMaxSize()) {
            ClairixTopBar(title = title, onBack = onBack)
            ScreenList(Modifier.weight(1f), content = content)
        }
    }
}

/** Wall-clock that ticks every [periodMs] so countdowns and "ago" times stay honest. */
@Composable
internal fun rememberToolTick(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

internal fun openExternal(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
