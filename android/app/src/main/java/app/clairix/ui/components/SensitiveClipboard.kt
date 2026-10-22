package app.clairix.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager

/**
 * Copies [text] marked sensitive: from Android 13 the system clipboard then
 * hides it from the copy preview and keyboards keep it out of their
 * clipboard history. Safety numbers, invite links, pairing codes and the
 * comms log are all copied this way; a plain copy of any of them would sit in
 * the keyboard's history until something pushed it out.
 */
fun ClipboardManager.setSensitiveText(label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    setClip(ClipEntry(clip))
}
