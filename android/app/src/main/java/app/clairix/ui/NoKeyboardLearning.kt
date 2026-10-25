package app.clairix.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard not to learn from anything typed under [content]: every
 * text field gets IME_FLAG_NO_PERSONALIZED_LEARNING, the flag Gboard, Samsung's
 * keyboard and others read as "incognito". Without it a keyboard adds what is
 * typed (messages, contact names, the relay's enrollment code, a passcode) to
 * its suggestions and, with sync on, to the owner's cloud account. It is a
 * request: a keyboard may ignore it, which is why Phone check names the
 * keyboard in use and whether it can reach the internet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val incognito = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return connection
                }
            }
            nextHandler.startInputMethod(incognito)
        },
        content = content
    )
}
