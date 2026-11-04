package app.clairix.links

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.AppSettings
import app.clairix.R
import app.clairix.security.WindowShield
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.mono

/**
 * "Clean link": a share target (ACTION_SEND, text/plain) and a text-selection action
 * (ACTION_PROCESS_TEXT) that shows the text it was handed with every link in it cleaned
 * ([LinkCleaner]), what was taken out, and Copy, Open and Share. The cleaning is string work on
 * the phone; this screen makes no network request of its own. Open hands the cleaned link to the
 * browser (or whichever app handles it), as tapping it anywhere else would. A text-selection
 * action on editable text can also put the cleaned text back in place of the selection.
 *
 * Discreet mode switches this entry off (DiscreetMode), so neither the share sheet nor the
 * text-selection menu shows a Clairix entry while it is on.
 */
class LinkCleanerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Like every Clairix screen: no screenshots or Recents thumbnail, and shielded.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowShield.shield(this)
        AppSettings.load(this)
        val shared = sharedText(intent)
        val result = shared?.let(LinkCleaner::cleanText)
        val canReplace = intent?.action == Intent.ACTION_PROCESS_TEXT &&
            !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        setContent {
            val theme by AppSettings.theme.collectAsStateWithLifecycle()
            ClairixTheme(theme = theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    LinkCleanerScreen(
                        result = result,
                        onOpen = { link -> open(link) },
                        onShare = { text -> share(text) },
                        onReplace = if (canReplace) { text -> replace(text) } else null,
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    private fun open(link: String) {
        val view = Intent(Intent.ACTION_VIEW, link.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { startActivity(view) }
    }

    /** Shares [text] onward; this entry is left out of the chooser, which would only hand it back. */
    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, LinkCleanerActivity::class.java)))
        runCatching { startActivity(chooser) }
    }

    private fun replace(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }

    companion object {
        /** The text a share or a text-selection action handed over; null when there is none. */
        internal fun sharedText(intent: Intent?): String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}

/** What was taken out of the links in [r], in words, or null when nothing was. */
internal fun removedSummary(r: LinkCleaner.CleanedText): String? {
    val removed = r.links.flatMap { it.removed }.distinct()
    val unwrapped = r.links.flatMap { it.unwrapped }.distinct()
    if (removed.isEmpty() && unwrapped.isEmpty()) return null
    return buildList {
        if (unwrapped.isNotEmpty()) add("redirect through ${unwrapped.joinToString(", ")}")
        if (removed.isNotEmpty()) add(removed.joinToString(", "))
    }.joinToString("; ")
}

@Composable
private fun LinkCleanerScreen(
    result: LinkCleaner.CleanedText?,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onReplace: ((String) -> Unit)?,
    onDone: () -> Unit,
) {
    val c = ClairixTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.link_clean_link),
            style = MaterialTheme.typography.headlineSmall,
            color = c.text,
            modifier = Modifier.semantics { heading() }
        )
        val link = result?.firstLink
        if (result == null || link == null) {
            Notice(Tone.Neutral, stringResource(R.string.link_no_link_found))
            GhostButton(stringResource(R.string.link_done), onClick = onDone, tone = Tone.Neutral, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        ClairixCard {
            SelectionContainer {
                Text(result.text, style = MaterialTheme.typography.mono, color = c.text)
            }
        }
        val summary = removedSummary(result)
        if (summary != null) {
            Notice(Tone.Safe, summary, title = stringResource(R.string.link_removed))
        } else {
            Notice(Tone.Neutral, stringResource(R.string.link_nothing_to_remove))
        }
        Text(stringResource(R.string.link_no_network), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
        PrimaryButton(
            if (copied) stringResource(R.string.link_copied) else stringResource(R.string.link_copy),
            onClick = {
                clipboard.setText(AnnotatedString(result.text))
                copied = true
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.link_open), onClick = { onOpen(link) }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.link_share), onClick = { onShare(result.text) }, modifier = Modifier.weight(1f))
        }
        if (onReplace != null) {
            SecondaryButton(stringResource(R.string.link_replace), onClick = { onReplace(result.text) }, modifier = Modifier.fillMaxWidth())
        }
        GhostButton(stringResource(R.string.link_done), onClick = onDone, tone = Tone.Neutral, modifier = Modifier.fillMaxWidth())
    }
}
