package app.clairix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clairix.security.Helplines
import app.clairix.ui.components.ChoiceChips
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.Tone
import app.clairix.ui.components.color
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Get help: helplines for abuse and stalking where the owner is, from the
 * bundled list in assets/helplines.json (security/Helplines.kt). Reached from
 * Settings, the Comms menu, every safety card and the sheet shown before a
 * flagged app's settings. A call goes through the dialler (ACTION_DIAL, so no
 * permission and nothing dialled until the owner presses call there), a text
 * through the messaging app and a chat through the browser or WhatsApp; the
 * page says what each of those leaves on the phone. Clairix records nothing
 * about this page being opened or what was tapped on it, and locking Clairix
 * closes it (MainApp), so an unlock someone forces does not open on it.
 */

/**
 * The Get help page. Under the shell (the Comms menu) [onBack] is null and the
 * shell's bar carries the title; opened over everything else (from Settings, a
 * safety card or the sheet) it draws its own bar with [onBack], and the system
 * back button closes it too.
 */
@Composable
internal fun GetHelpPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val book by produceState<Helplines.Book?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { Helplines.load(context) }.getOrDefault(Helplines.Book(emptyMap())) }
    }
    val sim = remember { Helplines.simCountry(context) }
    val region = remember { Locale.getDefault().country }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }

    val content: LazyListScope.() -> Unit = {
        val b = book
        if (b == null) {
            item(key = "§loading") { LoadingBlock(stringResource(R.string.safety_reading_the_list)) }
        } else {
            helpContent(
                book = b,
                choice = Helplines.choose(b, sim, region),
                picked = picked,
                picking = picking,
                onPicking = { picking = it },
                onPick = { code -> picked = code; picking = false },
                context = context
            )
        }
    }

    if (onBack == null) {
        ToolPageList(title = stringResource(R.string.settings_get_help), onBack = null, content = content)
    } else {
        BackHandler(onBack = onBack)
        // Drawn instead of the shell, like Settings: its own background, clear of the navigation bar.
        Box(Modifier.fillMaxSize().background(ClairixTheme.colors.base).navigationBarsPadding()) {
            ToolPageList(title = stringResource(R.string.settings_get_help), onBack = onBack, content = content)
        }
    }
}

private fun LazyListScope.helpContent(
    book: Helplines.Book,
    choice: Helplines.Choice,
    picked: String?,
    picking: Boolean,
    onPicking: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    context: Context
) {
    val shown = picked?.let { book.countries[it] } ?: choice.country

    item(key = "§intro") {
        Text(
            stringResource(R.string.safety_these_services_help_people_dealing_with),
            style = MaterialTheme.typography.bodyLarge,
            color = ClairixTheme.colors.textMuted
        )
    }

    if (book.countries.isEmpty()) {
        item(key = "§unreadable") {
            Notice(Tone.Danger, stringResource(R.string.safety_the_list_of_helplines_could_not))
        }
        return
    }

    val emergency = shown?.emergency
    if (emergency != null) {
        item(key = "§emergency") { EmergencyCard(emergency, context) }
    }

    item(key = "§country") {
        val why = when {
            shown == null -> null
            picked != null -> stringResource(R.string.safety_chosen_by_you)
            choice.match == Helplines.Match.SIM -> stringResource(R.string.safety_from_your_sim_card)
            choice.match == Helplines.Match.REGION -> stringResource(R.string.safety_from_the_phones_region)
            else -> null
        }
        val change: (@Composable RowScope.() -> Unit)? = if (shown != null && book.countries.size > 1) {
            { GhostButton(if (picking) stringResource(R.string.comms_done) else stringResource(R.string.settings_change), onClick = { onPicking(!picking) }) }
        } else {
            null
        }
        SectionHeader(shown?.name ?: stringResource(R.string.safety_pick_a_country), supporting = why, action = change)
    }
    if (shown == null) {
        item(key = "§none") {
            Notice(
                Tone.Info,
                choice.unmatched?.let { stringResource(R.string.safety_no_line_for_is_in_this, countryName(it)) }
                    ?: stringResource(R.string.safety_the_phone_does_not_say_which)
            )
        }
    }
    Helplines.directoryFor(book, shown)?.let { directory ->
        item(key = "§directory") { DirectoryCard(directory, context) }
    }
    if (shown == null || picking) {
        item(key = "§picker") {
            ChoiceChips(
                choices = book.byName.map { it.code },
                selected = shown?.code ?: "",
                label = { code -> book.countries[code]?.name ?: code },
                onSelect = onPick
            )
        }
    }

    if (shown != null) {
        items(shown.lines, key = { "line|${shown.code}|${it.name}" }) { line -> HelplineCard(line, context) }
    }

    item(key = "§traces") {
        Notice(
            Tone.Caution,
            stringResource(R.string.safety_a_call_stays_in_the_phones),
            title = stringResource(R.string.safety_what_this_leaves_on_the_phone)
        )
    }

    item(key = "§verified") {
        val date = shown?.let { Helplines.lastVerified(it) }
        Text(
            (date?.let { stringResource(R.string.safety_last_verified_against_each_services_own, Helplines.formatDate(it)) } ?: "") +
                "This list is not exhaustive: it has some countries and their national lines, and most places also " +
                "have local services." +
                (if (date != null) stringResource(R.string.safety_a_service_can_change_its_number) else ""),
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

/**
 * For a country the list does not have: what to do in danger now, without a
 * number Clairix cannot vouch for there, and the outside directory.
 */
@Composable
private fun DirectoryCard(d: Helplines.Directory, context: Context) {
    val c = ClairixTheme.colors
    ClairixCard(spacing = 8.dp) {
        Text(stringResource(R.string.safety_in_danger_now), style = MaterialTheme.typography.titleSmall, color = Tone.Danger.color())
        Text(
            stringResource(R.string.safety_call_the_local_emergency_number_from),
            style = MaterialTheme.typography.bodyMedium,
            color = c.text
        )
        Hairline(Modifier.padding(vertical = 4.dp))
        Text(d.label, style = MaterialTheme.typography.titleSmall, color = c.text)
        d.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle) }
        Text(
            stringResource(R.string.safety_checked_clairix_has_not_checked_the, Helplines.formatDate(d.verified)),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSubtle
        )
        SecondaryButton(
            stringResource(R.string.shell_open, d.label),
            onClick = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse(d.url)), "No app on this phone can open ${d.url}") },
            tone = Tone.Accent,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The country's emergency number first, for someone in danger now. */
@Composable
private fun EmergencyCard(e: Helplines.Emergency, context: Context) {
    ClairixCard(tone = Tone.Danger, emphasis = true, spacing = 8.dp) {
        Text(stringResource(R.string.safety_in_danger_now), style = MaterialTheme.typography.titleSmall, color = Tone.Danger.color())
        e.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text) }
        PrimaryButton(
            stringResource(R.string.safety_call, e.number),
            onClick = { dial(context, e.number) },
            tone = Tone.Danger,
            icon = Icons.Filled.Call,
            modifier = Modifier.fillMaxWidth()
        )
        e.text?.let { t -> SecondaryButton(t.label, onClick = { sendText(context, t) }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun HelplineCard(line: Helplines.Line, context: Context) {
    val c = ClairixTheme.colors
    ClairixCard(spacing = 6.dp) {
        Text(line.name, style = MaterialTheme.typography.titleSmall, color = c.text)
        line.area?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Tone.Info.color()) }
        Text(line.hours, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        line.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle) }
        SecondaryButton(
            stringResource(R.string.safety_call, line.phone),
            onClick = { dial(context, line.phone) },
            tone = Tone.Accent,
            icon = Icons.Filled.Call,
            modifier = Modifier.fillMaxWidth()
        )
        for (extra in line.also) {
            Text(extra.label, style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
            GhostButton(stringResource(R.string.safety_call, extra.phone), onClick = { dial(context, extra.phone) }, modifier = Modifier.fillMaxWidth())
        }
        val text = line.text
        if (text != null || line.chat.isNotEmpty()) {
            Hairline(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.safety_when_calling_is_not_safe), style = MaterialTheme.typography.titleSmall, color = c.text)
            if (text != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text.label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    text.hours?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSubtle) }
                }
                SecondaryButton(stringResource(R.string.safety_send_a_text), onClick = { sendText(context, text) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
            }
            for (chat in line.chat) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chat.label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    chat.hours?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSubtle) }
                }
                SecondaryButton(
                    if (chat.url.startsWith("https://wa.me/")) stringResource(R.string.safety_open_whatsapp) else stringResource(R.string.safety_open_chat),
                    onClick = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse(chat.url)), "No app on this phone can open ${chat.url}") },
                    tone = Tone.Accent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** A country's English name from its code, for a country the list does not have. */
private fun countryName(code: String): String =
    runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: code

/** Opens the dialler with [phone] filled in; the owner presses call there. */
private fun dial(context: Context, phone: String) {
    open(
        context,
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", Helplines.dialString(phone), null)),
        "No app on this phone can make calls. The number is $phone."
    )
}

/** Opens the messaging app with the number and, when the service asks for a keyword, the keyword filled in. */
private fun sendText(context: Context, t: Helplines.TextOption) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", t.to, null))
    t.body?.let { intent.putExtra("sms_body", it) }
    open(context, intent, "No app on this phone can send texts. ${t.label}.")
}

private fun open(context: Context, intent: Intent, otherwise: String) {
    val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!started) Toast.makeText(context, otherwise, Toast.LENGTH_LONG).show()
}
