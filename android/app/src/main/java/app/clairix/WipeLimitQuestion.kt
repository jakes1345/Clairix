package app.clairix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Whether [WipeChoiceDialog] is on screen: while someone is [asking] for an
 * answer, and only while Clairix is not [locked]. A dialog is a window of its
 * own, drawn above the lock screen Clairix draws in the activity; one left up
 * when the lock came down would let whoever holds the phone export the
 * evidence or arm a one-attempt wipe without the passcode.
 */
internal fun wipeQuestionShown(asking: Boolean, locked: Boolean): Boolean = asking && !locked

/**
 * Whether [WipeLimitQuestion] asks now: the last unlock found a wipe limit
 * nobody chose ([AppLock.wipeChoiceDue]), Clairix is open, and the notice about
 * wrong passcodes, when there is one, has been read first, so the two never
 * stack.
 */
internal fun wipeLimitQuestionDue(due: Boolean, locked: Boolean, wrongAttemptsPending: Boolean): Boolean =
    wipeQuestionShown(asking = due, locked = locked) && !wrongAttemptsPending

/** What the owner is told about a limit an earlier version set for her ([AppLock.pausedWipeAfter]). */
internal fun pausedWipeNote(limit: Int): String =
    "Earlier versions set a wipe after $limit wrong passcodes in a row whenever a passcode was made, " +
        "without asking. It is paused: until you choose, wrong passcodes lock you out for longer instead."

/**
 * The wipe-limit question, once Clairix is open after an unlock, on a phone
 * whose passcode an earlier version set together with a wipe after 3 it never
 * asked about. That limit is held back until she answers ([AppLock.wipeAfter]),
 * so nothing wipes on a number she did not pick; and she is asked rather than
 * left to find a notice in Settings, so a limit she did rely on is not lost
 * without her knowing.
 */
@Composable
internal fun WipeLimitQuestion() {
    val context = LocalContext.current
    val due by AppLock.wipeChoiceDue.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val wrongAttempts by AppLock.wrongAttempts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!wipeLimitQuestionDue(due, locked, wrongAttemptsPending = wrongAttempts != null)) return
    val paused = remember(due) { AppLock.pausedWipeAfter(context) }
    WipeChoiceDialog(
        onExportEvidence = if (BuildConfig.FULL_ACCESS) { { EvidenceExport.request() } } else null,
        note = if (paused > 0) pausedWipeNote(paused) else null,
        onChosen = { choice -> scope.launch(Dispatchers.IO) { AppLock.setWipeAfter(context, choice) } }
    )
}
