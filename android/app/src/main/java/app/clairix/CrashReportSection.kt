package app.clairix

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.components.setSensitiveText
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Settings, About: the last crash [CrashJournal] kept, with Copy, Share and
 * Delete, and "Report a problem", which opens the owner's mail app on a
 * prefilled message to support. Nothing here touches the network; a report
 * leaves only if the owner sends it from their own mail app.
 */
@Composable
internal fun CrashReportSection() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf<CrashRecord?>(null) }
    LaunchedEffect(Unit) {
        crash = withContext(Dispatchers.IO) { runCatching { CrashJournal.read(context) }.getOrNull() }
    }
    val c = ClairixTheme.colors

    Hairline(Modifier.padding(vertical = 4.dp))
    crash?.let { last ->
        Text(stringResource(R.string.settings_clairix_crashed_last_time), style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(
            stringResource(R.string.settings_on_what_is_kept_is_the, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last.ts))),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSubtle
        )
        Text(
            last.trace.lineSequence().take(4).joinToString("\n"),
            style = MaterialTheme.typography.monoSmall,
            color = c.textMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(R.string.settings_copy), onClick = { clipboard.setSensitiveText(context.getString(R.string.settings_clairix_crash), last.asText()) }, tone = Tone.Neutral)
            GhostButton(
                stringResource(R.string.settings_share),
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, CrashJournal.reportSubject(last.version, last.model, last.sdk))
                        .putExtra(Intent.EXTRA_TEXT, last.asText())
                    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_share_crash)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                tone = Tone.Neutral
            )
            GhostButton(
                stringResource(R.string.settings_delete),
                onClick = {
                    crash = null
                    scope.launch(Dispatchers.IO) { CrashJournal.delete(context) }
                },
                tone = Tone.Danger
            )
        }
    }
    Text(stringResource(R.string.settings_report_a_problem), style = MaterialTheme.typography.titleSmall, color = c.text)
    Text(
        stringResource(R.string.settings_opens_your_mail_app_on_a, CrashJournal.SUPPORT_ADDRESS),
        style = MaterialTheme.typography.bodyMedium,
        color = c.textSubtle
    )
    SecondaryButton(
        stringResource(R.string.settings_report_a_problem),
        onClick = {
            val opened = runCatching { context.startActivity(CrashJournal.reportIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (opened.isFailure) {
                Toast.makeText(context, context.getString(R.string.settings_no_mail_app_on_this_phone, CrashJournal.SUPPORT_ADDRESS), Toast.LENGTH_LONG).show()
            }
        },
        tone = Tone.Accent,
        modifier = Modifier.fillMaxWidth()
    )
}
