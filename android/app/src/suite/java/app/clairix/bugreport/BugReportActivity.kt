package app.clairix.bugreport

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.AppSettings
import app.clairix.R
import app.clairix.analysis.StalkwareScanner
import app.clairix.security.WindowShield
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Check a bug report": a share target for the zip Android makes under Developer options → Take
 * bug report. It reads the zip as it is handed over ([BugReportZip], [BugReportParser]) and shows
 * which apps the report says used the microphone, the camera and location, and what it could not
 * read. Nothing is uploaded, stored or kept: the report is read once, in memory, a line at a time.
 *
 * Taking a bug report needs Developer options, which Phone check counts as a risk, so the screen
 * says first and last to switch them off again, with a button to the page where that is done.
 *
 * Discreet mode switches this entry off (DiscreetMode), like Clean link.
 */
class BugReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Like every Clairix screen: no screenshots or Recents thumbnail, and shielded.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowShield.shield(this)
        AppSettings.load(this)
        val uri = sharedStream(intent)
        setContent {
            val theme by AppSettings.theme.collectAsStateWithLifecycle()
            ClairixTheme(theme = theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    BugReportScreen(uri, onDeveloperOptions = { developerOptions() }, onDone = { finish() })
                }
            }
        }
    }

    private fun developerOptions() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    companion object {
        /**
         * The file a share handed over: the stream of ACTION_SEND, or for ACTION_SEND_MULTIPLE (the
         * bug report with its screenshot, as Android's own share sends it) the first stream that is
         * not an image.
         */
        internal fun sharedStream(intent: Intent?): Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.firstOrNull { !looksLikeImage(it) }
            else -> null
        }

        private fun looksLikeImage(uri: Uri): Boolean {
            val name = uri.lastPathSegment.orEmpty().lowercase()
            return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        }

        /** Android's own name for where Developer options are switched off, for the words on screen. */
        internal val SWITCH_OFF_WHERE: String =
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "Settings → Developer options, the switch at the top"
            else "Settings → System → Developer options, the switch at the top"
    }
}

@Composable
private fun BugReportScreen(uri: Uri?, onDeveloperOptions: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    var outcome by remember { mutableStateOf<BugReportZip.Outcome?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        outcome = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BugReportZip.read(it) }
                    ?: BugReportZip.Outcome.NotRead("The app that shared it did not hand over the file.")
            }.getOrElse { BugReportZip.Outcome.NotRead("The file could not be opened (${it.javaClass.simpleName}).") }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.bugreport_title),
            style = MaterialTheme.typography.headlineSmall,
            color = c.text,
            modifier = Modifier.semantics { heading() }
        )
        DeveloperOptionsNotice(onDeveloperOptions)
        when (val o = outcome) {
            null -> if (uri == null) {
                Notice(Tone.Neutral, stringResource(R.string.bugreport_nothing_shared))
            } else {
                LoadingBlock(stringResource(R.string.bugreport_reading))
            }
            is BugReportZip.Outcome.NotRead -> Notice(Tone.Caution, o.reason, title = stringResource(R.string.bugreport_not_read))
            is BugReportZip.Outcome.Read -> Findings(o)
        }
        Text(stringResource(R.string.bugreport_offline), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
        if (outcome != null) DeveloperOptionsNotice(onDeveloperOptions)
        GhostButton(stringResource(R.string.link_done), onClick = onDone, tone = Tone.Neutral, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DeveloperOptionsNotice(onDeveloperOptions: () -> Unit) {
    Notice(
        Tone.Caution,
        stringResource(R.string.bugreport_switch_off_developer_options, BugReportActivity.SWITCH_OFF_WHERE),
        title = stringResource(R.string.bugreport_switch_off_title),
    )
    PrimaryButton(stringResource(R.string.bugreport_open_developer_options), onClick = onDeveloperOptions, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Findings(o: BugReportZip.Outcome.Read) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val f = o.findings
    ClairixCard {
        KeyValueRow(stringResource(R.string.bugreport_file), o.entry)
        f.takenAt?.let { KeyValueRow(stringResource(R.string.bugreport_taken), it) }
        f.build?.let { Text(it, style = MaterialTheme.typography.monoSmall, color = c.textSubtle) }
    }
    for (resource in BugReportParser.Resource.entries) {
        val uses = f.of(resource)
        ClairixCard {
            Text(resource.label, style = MaterialTheme.typography.titleSmall, color = c.text)
            if (uses.isEmpty()) {
                Text(stringResource(R.string.bugreport_no_use_found), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            for (u in uses) {
                val label = remember(u.packageName) { StalkwareScanner.appLabel(context, u.packageName) }
                val name = if (label == u.packageName) u.packageName else "$label (${u.packageName})"
                val last = u.last?.let { stringResource(R.string.bugreport_last_at, it) } ?: stringResource(R.string.bugreport_no_time)
                Text(name, style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("$last · ${u.sources.joinToString(", ") { it.label }}", style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
            }
        }
    }
    Text(stringResource(R.string.bugreport_what_it_shows), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    val gaps = f.unread + listOfNotNull(o.stopped)
    if (gaps.isNotEmpty()) {
        Notice(Tone.Neutral, gaps.joinToString("\n"), title = stringResource(R.string.bugreport_could_not_read))
    }
}
