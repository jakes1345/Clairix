// Hand-written companions to the generated ClairixTheme.kt: the text styles
// Material 3 has no slot for, and the few fixed colours that must NOT follow the
// selected theme (the logo, the decoy call screen, map and road-sign rendering).
// Every other colour in the app comes from ClairixTheme.colors or
// MaterialTheme.colorScheme.

package app.clairix.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Type ─────────────────────────────────────────────────────────────────────

/** Machine values: MAC addresses, cell IDs, fingerprints, coordinates. 13 sp. */
val Typography.mono: TextStyle get() = ClairixType.mono

/** Machine values in dense metadata lines. 12 sp. */
val Typography.monoSmall: TextStyle
    get() = ClairixType.mono.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** A single large machine reading, such as a dBm figure or a countdown. */
val Typography.monoLarge: TextStyle
    get() = ClairixType.mono.copy(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)

// ── Fixed brand colours ──────────────────────────────────────────────────────

/**
 * The logo's own colours. The brand book is explicit: the logo and the app icon
 * never change with the theme, so these are deliberately not theme roles.
 */
object ClairixBrand {
    /** The C and the wordmark: bone white. */
    val Bone = Color(0xFFEFF3EB)

    /** The X: acid green, in every theme. */
    val Acid = Color(0xFFC8FF24)

    /** The ground the mark sits on in the launcher icon and the splash. */
    val Ink = Color(0xFF0A0B08)
}

/**
 * The decoy incoming-call screen imitates a stock phone call UI on purpose, so
 * that someone looking over the user's shoulder sees an ordinary call. It must
 * not carry the Clairix look, and so it does not follow the theme either.
 */
object DecoyCallPalette {
    val Ground = Color(0xFF0B0F14)
    val Ink = Color(0xFFF2F4F7)
    val Dim = Color(0xFF9AA4B2)
    val Answer = Color(0xFF2EBD59)
    val Decline = Color(0xFFE5484D)
    val Avatar = Color(0xFF3A4A5E)
}

/**
 * Colours drawn onto the navigation map. Traffic speeds follow the convention
 * every navigation app uses (green, amber, red, dark red) so drivers read them at
 * a glance, and the road signs copy the real regulatory signs. None of this may
 * change with the app's colour theme.
 */
object MapPalette {
    val TrafficFree = Color(0xFF2E9E4F)
    val TrafficSlow = Color(0xFFF2A30F)
    val TrafficHeavy = Color(0xFFE5392F)
    val TrafficStopped = Color(0xFF8E1010)
    val TrafficUnknown = Color(0xFF2F6FEB)
    val RouteAlternative = Color(0xFF8A94A6)
    val RouteCasing = Color(0xFF0D1B33)
    val Hazard = Color(0xFFE5392F)
    val WorkZone = Color(0xFFF57C00)
    val Closure = Color(0xFFD32F2F)

    /** The green of a motorway direction sign, behind the next manoeuvre. */
    val GuidanceSign = Color(0xFF14532D)
    val GuidanceSignInk = Color(0xFFFFFFFF)

    /** A regulatory speed-limit sign: white face, black border and figures. */
    val SpeedSignFace = Color(0xFFFFFFFF)
    val SpeedSignInk = Color(0xFF000000)
}
