package app.clairix

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clairix.analysis.AccountChecklist
import app.clairix.analysis.AccountChecklist.Action
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Phone check's "Accounts" sub-tab, by its place in [ScanCategory.PHONE]'s sub-tabs. */
internal const val ACCOUNTS_SUB_TAB = 3

/** "3 Nov 2026, 14:05": when a row was ticked, in the phone's own date format. */
private fun formatTicked(ts: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

/** The Health tab's line about the checklist, from [AccountChecklist.progress]. */
internal fun accountChecklistLine(p: AccountChecklist.Progress, format: (Long) -> String): String = when {
    !p.started -> "Not done yet. Most watching by someone you know goes through your accounts, not an app: " +
        "this checklist takes you to each place it shows."
    p.done -> "All ${p.total} checked. Last ticked ${format(p.lastTickAt ?: 0L)}."
    else -> "${p.checked} of ${p.total} checked. Last ticked ${format(p.lastTickAt ?: 0L)}."
}

/**
 * Phone check → Health's card for the accounts checklist: how far it got and when a row was last
 * ticked, and a button to it.
 */
internal fun LazyListScope.accountChecklistSummaryItems(onOpen: () -> Unit) {
    item(key = "accounts_hdr") { SectionHeader("Who else can see this phone") }
    item(key = "accounts_card") { AccountChecklistSummary(onOpen) }
}

@Composable
private fun AccountChecklistSummary(onOpen: () -> Unit) {
    val context = LocalContext.current
    val rows = remember { AccountChecklist.rows(Build.MANUFACTURER) }
    var ticks by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        ticks = withContext(Dispatchers.IO) { runCatching { AccountChecklist.ticks(context) }.getOrDefault(emptyMap()) }
    }
    val progress = AccountChecklist.progress(rows, ticks)
    ClairixCard(tone = if (progress.done) Tone.Safe else Tone.Neutral) {
        Text(
            accountChecklistLine(progress, ::formatTicked),
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.text
        )
        SecondaryButton(
            if (progress.started) "Open the checklist" else "Start the checklist",
            onClick = onOpen,
            tone = Tone.Accent,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The Intent for an [Action], or null when there is nothing on this phone to open it with. */
private fun intentFor(context: Context, action: Action): Intent? = when (action) {
    is Action.Web -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
    is Action.App -> context.packageManager.getLaunchIntentForPackage(action.pkg)
        ?: action.fallbackUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
    is Action.SystemSettings -> Intent(action.action)
    // Uri.fromParts encodes the # in the code, which a tel: string would cut off as a fragment.
    is Action.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.code, null))
    is Action.ChooseAccount ->
        AccountManager.newChooseAccountIntent(null, null, arrayOf(action.accountType), null, null, null, null)
}

/**
 * Phone check → Accounts: the "who else can see this phone" checklist. A row per
 * channel, each with what to look for and the button that opens the page that
 * shows it; a tick per row, kept in the app's encrypted store, so the Health tab
 * can say when it was last done.
 */
@Composable
internal fun AccountChecklistPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember { AccountChecklist.rows(Build.MANUFACTURER) }
    var ticks by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        ticks = withContext(Dispatchers.IO) { runCatching { AccountChecklist.ticks(context) }.getOrDefault(emptyMap()) }
    }
    // Android's account chooser is an activity that returns the account picked; nothing is read
    // from it. Back closes it, which is what the row tells her to do.
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        // Nothing is read from the choice: the list itself was the point.
    }

    fun open(action: Action) {
        val intent = intentFor(context, action) ?: return
        if (action is Action.ChooseAccount) {
            runCatching { chooser.launch(intent) }
        } else {
            openExternal(context, intent)
        }
    }

    fun setChecked(id: String, checked: Boolean) {
        val now = System.currentTimeMillis()
        ticks = if (checked) ticks + (id to now) else ticks - id
        scope.launch(Dispatchers.IO) { runCatching { AccountChecklist.setChecked(context, id, checked, now) } }
    }

    val progress = AccountChecklist.progress(rows, ticks)

    ToolPageList(title = "Who else can see this phone", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "Someone who knows you rarely needs an app to watch you. A Google or Samsung account they also sign " +
                    "in to, location sharing, a family group, a recovery number they control or calls forwarded at the " +
                    "carrier show them as much, and no app on the phone can see any of it, Clairix included. Each row " +
                    "opens the page where it shows and says what to look for there. Tick a row once you have looked.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        item(key = "§before") {
            Notice(
                Tone.Caution,
                "Looking changes nothing. Changing something can be noticed: a device signed out, location sharing " +
                    "stopped or a password changed can tell the other person you found it, and Google tells an account's " +
                    "recovery addresses about some changes. If it may not be safe yet, look first, take screenshots if " +
                    "you may want them as evidence, and talk to an advocate before you change anything.",
                title = "Before you change anything",
                action = { GhostButton("Get help", onClick = { Registry.requestHelp() }) }
            )
        }
        item(key = "§progress") {
            SectionHeader(
                "Checklist",
                supporting = "${progress.checked} of ${progress.total} checked"
            )
        }
        items(rows, key = { it.id }) { row ->
            AccountCheckRowCard(
                row = row,
                tickedAt = ticks[row.id],
                onOpen = { open(it) },
                onChecked = { setChecked(row.id, it) }
            )
        }
        if (progress.started) {
            item(key = "§again") {
                GhostButton(
                    "Start again",
                    onClick = {
                        ticks = emptyMap()
                        scope.launch(Dispatchers.IO) { runCatching { AccountChecklist.clear(context) } }
                    },
                    tone = Tone.Neutral
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCheckRowCard(
    row: AccountChecklist.Row,
    tickedAt: Long?,
    onOpen: (Action) -> Unit,
    onChecked: (Boolean) -> Unit
) {
    val c = ClairixTheme.colors
    val context = LocalContext.current
    ClairixCard(tone = if (tickedAt != null) Tone.Safe else null) {
        Text(row.title, style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(row.lookFor, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        val dials = row.actions.filterIsInstance<Action.Dial>()
        if (dials.isNotEmpty()) {
            // The forwarding codes: one button each, with what its answer means.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in dials) {
                    SecondaryButton(d.label, onClick = { onOpen(d) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                    Text(d.shows, style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
                }
            }
        }
        // An app's button only where the app is installed or has a web page to fall back on.
        val others = remember(row.id) { row.actions.filter { it !is Action.Dial && intentFor(context, it) != null } }
        if (others.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEachIndexed { i, a ->
                    if (i == 0) {
                        SecondaryButton(a.label, onClick = { onOpen(a) }, tone = Tone.Accent)
                    } else {
                        GhostButton(a.label, onClick = { onOpen(a) })
                    }
                }
            }
        }
        ToggleRow(
            title = "I have checked this",
            subtitle = tickedAt?.let { "Ticked ${formatTicked(it)}" },
            checked = tickedAt != null,
            onCheckedChange = onChecked
        )
    }
}
