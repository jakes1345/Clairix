package app.clairix.ui.components

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager

/**
 * The extra that marks a clip sensitive. Android 13 names it
 * ClipDescription.EXTRA_IS_SENSITIVE; on Android 12 the same key is what
 * keyboards such as Gboard read, so it is set on every version.
 */
internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * Copies [text] marked sensitive: from Android 13 the system clipboard then
 * hides it from the copy preview, and on every version keyboards that honour
 * the mark keep it out of their clipboard history. Safety numbers, invite
 * links, pairing codes and the comms log are all copied this way; a plain copy
 * of any of them would sit in the keyboard's history until something pushed it
 * out. The keyboard in use can still read what is on the clipboard.
 */
fun ClipboardManager.setSensitiveText(label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    setClip(ClipEntry(clip))
}
