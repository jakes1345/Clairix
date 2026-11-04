package app.clairix.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The app's resources, for the text functions that take [Resources] so the JVM
 * tests can call them with the real strings. Reading the configuration first
 * recomposes the caller when the locale changes, as stringResource does.
 */
@Composable
@ReadOnlyComposable
internal fun appResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
