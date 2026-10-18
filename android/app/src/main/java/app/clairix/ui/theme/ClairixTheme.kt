// Clairix theme for Jetpack Compose.
// Generated from brand/tokens/tokens.json by brand/tokens/build.mjs. Do not edit by hand.
//
// Dark-only, four colour themes (Acid, Infrared, Hazard, Volt); Acid is the default.
// The logo and app icon never change with the theme: only these colours do.
// Fonts live in res/font (Archivo, Archivo Expanded, JetBrains Mono; SIL OFL).
// Usage: ClairixTheme(theme = ClairixThemeName.ACID) { ... }

package app.clairix.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.clairix.R

/** The selectable colour themes. ACID is the brand default. */
enum class ClairixThemeName(val label: String) {
    ACID("Acid"),
    INFRARED("Infrared"),
    HAZARD("Hazard"),
    VOLT("Volt"),
}

/** Every colour role of a Clairix theme, including those Material 3 has no slot for. */
@Immutable
data class ClairixColors(
    val base: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val border: Color,
    val borderStrong: Color,
    val textSubtle: Color,
    val textMuted: Color,
    val text: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val safe: Color,
    val onSafe: Color,
    val safeContainer: Color,
    val onSafeContainer: Color,
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val focusRing: Color,
)

/** Acid: The default. Black, bone white and acid green. Loud on merch, clear in the app. */
val AcidColors = ClairixColors(
    base = Color(0xFF0A0B08), // oklch(0.148 0.007 125)
    surface = Color(0xFF131510), // oklch(0.19 0.0091 125)
    surfaceRaised = Color(0xFF1B1D18), // oklch(0.225 0.0098 125)
    surfaceOverlay = Color(0xFF23251F), // oklch(0.26 0.0105 125)
    border = Color(0xFF2C2F29), // oklch(0.3 0.0105 125)
    borderStrong = Color(0xFF4C4E48), // oklch(0.42 0.0112 125)
    textSubtle = Color(0xFF91958B), // oklch(0.665 0.0154 125)
    textMuted = Color(0xFFB0B4AB), // oklch(0.765 0.014 125)
    text = Color(0xFFEFF3EB), // oklch(0.958 0.011 125)
    accent = Color(0xFFC8FF24), // oklch(0.929 0.226 124.2)
    accentHover = Color(0xFFE9FFC2), // oklch(0.969 0.226 124.2)
    onAccent = Color(0xFF0A0B08), // = base
    accentContainer = Color(0xFF1F2C00), // oklch(0.27 0.075 124.2)
    onAccentContainer = Color(0xFFC8FF24), // = accent
    safe = Color(0xFF3DE6B0), // oklch(0.828 0.156 166.4)
    onSafe = Color(0xFF0A0B08), // = base
    safeContainer = Color(0xFF002B1D), // oklch(0.255 0.055 166.4)
    onSafeContainer = Color(0xFF3DE6B0), // = safe
    caution = Color(0xFFFFB52F), // oklch(0.823 0.161 76.6)
    onCaution = Color(0xFF0A0B08), // = base
    cautionContainer = Color(0xFF311F00), // oklch(0.255 0.055 76.6)
    onCautionContainer = Color(0xFFFFB52F), // = caution
    danger = Color(0xFFFF5A4E), // oklch(0.687 0.203 27.8)
    onDanger = Color(0xFF0A0B08), // = base
    dangerContainer = Color(0xFF391713), // oklch(0.255 0.055 27.8)
    onDangerContainer = Color(0xFFFF5A4E), // = danger
    info = Color(0xFF5FCBFF), // oklch(0.8 0.13 232)
    onInfo = Color(0xFF0A0B08), // = base
    infoContainer = Color(0xFF002738), // oklch(0.255 0.055 232)
    onInfoContainer = Color(0xFF5FCBFF), // = info
    focusRing = Color(0xFFC8FF24), // = accent
)

/** Infrared: Carbon black and steel with an infrared red that means one thing: threat found. */
val InfraredColors = ClairixColors(
    base = Color(0xFF090B0F), // oklch(0.148 0.01 272)
    surface = Color(0xFF12141A), // oklch(0.19 0.013 272)
    surfaceRaised = Color(0xFF191B22), // oklch(0.225 0.014 272)
    surfaceOverlay = Color(0xFF21242B), // oklch(0.26 0.015 272)
    border = Color(0xFF2B2D36), // oklch(0.3 0.015 272)
    borderStrong = Color(0xFF4A4D56), // oklch(0.42 0.016 272)
    textSubtle = Color(0xFF8F93A2), // oklch(0.665 0.022 272)
    textMuted = Color(0xFFAEB2C0), // oklch(0.765 0.02 272)
    text = Color(0xFFF0F1F4), // oklch(0.958 0.004 272)
    accent = Color(0xFFFF2F4A), // oklch(0.649 0.238 21.1)
    accentHover = Color(0xFFFF5962), // oklch(0.689 0.238 21.1)
    onAccent = Color(0xFF090B0F), // = base
    accentContainer = Color(0xFF441416), // oklch(0.27 0.075 21.1)
    onAccentContainer = Color(0xFFFF9593), // oklch(0.78 0.14 21)
    safe = Color(0xFF35E39A), // oklch(0.814 0.172 159.7)
    onSafe = Color(0xFF090B0F), // = base
    safeContainer = Color(0xFF032B19), // oklch(0.255 0.055 159.7)
    onSafeContainer = Color(0xFF35E39A), // = safe
    caution = Color(0xFFFFC23E), // oklch(0.848 0.156 82.4)
    onCaution = Color(0xFF090B0F), // = base
    cautionContainer = Color(0xFF2F2000), // oklch(0.255 0.055 82.4)
    onCautionContainer = Color(0xFFFFC23E), // = caution
    danger = Color(0xFFFD617F), // oklch(0.7 0.19 12)
    onDanger = Color(0xFF090B0F), // = base
    dangerContainer = Color(0xFF39161C), // oklch(0.255 0.055 12)
    onDangerContainer = Color(0xFFFD617F), // = danger
    info = Color(0xFF54CFF5), // oklch(0.8 0.12 222)
    onInfo = Color(0xFF090B0F), // = base
    infoContainer = Color(0xFF002833), // oklch(0.255 0.055 222)
    onInfoContainer = Color(0xFF54CFF5), // = info
    focusRing = Color(0xFFFF2F4A), // = accent
)

/** Hazard: Workwear black, bone and hazard orange. Built like a warning label. */
val HazardColors = ClairixColors(
    base = Color(0xFF0B0B09), // oklch(0.148 0.004 90)
    surface = Color(0xFF151411), // oklch(0.19 0.0052 90)
    surfaceRaised = Color(0xFF1D1C19), // oklch(0.225 0.0056 90)
    surfaceOverlay = Color(0xFF252421), // oklch(0.26 0.006 90)
    border = Color(0xFF2F2E2A), // oklch(0.3 0.006 90)
    borderStrong = Color(0xFF4E4D49), // oklch(0.42 0.0064 90)
    textSubtle = Color(0xFF96948E), // oklch(0.665 0.0088 90)
    textMuted = Color(0xFFB4B2AD), // oklch(0.765 0.008 90)
    text = Color(0xFFF5F1E5), // oklch(0.958 0.016 90)
    accent = Color(0xFFFF6A12), // oklch(0.701 0.2 43.7)
    accentHover = Color(0xFFFF834B), // oklch(0.741 0.2 43.7)
    onAccent = Color(0xFF0B0B09), // = base
    accentContainer = Color(0xFF431702), // oklch(0.27 0.075 43.7)
    onAccentContainer = Color(0xFFFF6A12), // = accent
    safe = Color(0xFF4BE08A), // oklch(0.807 0.175 153.9)
    onSafe = Color(0xFF0B0B09), // = base
    safeContainer = Color(0xFF082A16), // oklch(0.255 0.055 153.9)
    onSafeContainer = Color(0xFF4BE08A), // = safe
    caution = Color(0xFFFFD43D), // oklch(0.883 0.165 92.2)
    onCaution = Color(0xFF0B0B09), // = base
    cautionContainer = Color(0xFF2C2200), // oklch(0.255 0.055 92.2)
    onCautionContainer = Color(0xFFFFD43D), // = caution
    danger = Color(0xFFFF5247), // oklch(0.677 0.211 27.8)
    onDanger = Color(0xFF0B0B09), // = base
    dangerContainer = Color(0xFF391713), // oklch(0.255 0.055 27.8)
    onDangerContainer = Color(0xFFFF5247), // = danger
    info = Color(0xFF63CBFE), // oklch(0.8 0.12 232)
    onInfo = Color(0xFF0B0B09), // = base
    infoContainer = Color(0xFF002738), // oklch(0.255 0.055 232)
    onInfoContainer = Color(0xFF63CBFE), // = info
    focusRing = Color(0xFFFF6A12), // = accent
)

/** Volt: Deep night violet with an electric violet signal. Gaming energy, kept precise. */
val VoltColors = ClairixColors(
    base = Color(0xFF0B0911), // oklch(0.148 0.016 298)
    surface = Color(0xFF15121C), // oklch(0.19 0.0208 298)
    surfaceRaised = Color(0xFF1D1A25), // oklch(0.225 0.0224 298)
    surfaceOverlay = Color(0xFF25222E), // oklch(0.26 0.024 298)
    border = Color(0xFF2F2B38), // oklch(0.3 0.024 298)
    borderStrong = Color(0xFF4F4A5A), // oklch(0.42 0.0256 298)
    textSubtle = Color(0xFF968FA7), // oklch(0.665 0.0352 298)
    textMuted = Color(0xFFB5AFC5), // oklch(0.765 0.032 298)
    text = Color(0xFFF2EFFA), // oklch(0.958 0.015 298)
    accent = Color(0xFFA47BFF), // oklch(0.682 0.189 295.3)
    accentHover = Color(0xFFAE8DFF), // oklch(0.722 0.189 295.3)
    onAccent = Color(0xFF0B0911), // = base
    accentContainer = Color(0xFF2A1D47), // oklch(0.27 0.075 295.3)
    onAccentContainer = Color(0xFFA47BFF), // = accent
    safe = Color(0xFF45E3A5), // oklch(0.819 0.158 162.5)
    onSafe = Color(0xFF0B0911), // = base
    safeContainer = Color(0xFF002B1B), // oklch(0.255 0.055 162.5)
    onSafeContainer = Color(0xFF45E3A5), // = safe
    caution = Color(0xFFFFC84A), // oklch(0.86 0.151 84.4)
    onCaution = Color(0xFF0B0911), // = base
    cautionContainer = Color(0xFF2F2000), // oklch(0.255 0.055 84.4)
    onCautionContainer = Color(0xFFFFC84A), // = caution
    danger = Color(0xFFFF5C7A), // oklch(0.697 0.198 12.9)
    onDanger = Color(0xFF0B0911), // = base
    dangerContainer = Color(0xFF39161B), // oklch(0.255 0.055 12.9)
    onDangerContainer = Color(0xFFFF5C7A), // = danger
    info = Color(0xFF59E4F3), // oklch(0.85 0.12 205)
    onInfo = Color(0xFF0B0911), // = base
    infoContainer = Color(0xFF00292D), // oklch(0.255 0.055 205)
    onInfoContainer = Color(0xFF59E4F3), // = info
    focusRing = Color(0xFFA47BFF), // = accent
)

fun ClairixThemeName.colors(): ClairixColors = when (this) {
    ClairixThemeName.ACID -> AcidColors
    ClairixThemeName.INFRARED -> InfraredColors
    ClairixThemeName.HAZARD -> HazardColors
    ClairixThemeName.VOLT -> VoltColors
}

/** Maps a Clairix theme onto a Material 3 dark ColorScheme. */
fun ClairixColors.toColorScheme(): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentContainer,
    onPrimaryContainer = onAccentContainer,
    inversePrimary = accent,
    secondary = textMuted,
    onSecondary = base,
    secondaryContainer = surfaceOverlay,
    onSecondaryContainer = text,
    tertiary = info,
    onTertiary = onInfo,
    tertiaryContainer = infoContainer,
    onTertiaryContainer = onInfoContainer,
    background = base,
    onBackground = text,
    surface = base,
    onSurface = text,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textMuted,
    surfaceTint = accent,
    inverseSurface = text,
    inverseOnSurface = base,
    error = danger,
    onError = onDanger,
    errorContainer = dangerContainer,
    onErrorContainer = onDangerContainer,
    outline = borderStrong,
    outlineVariant = border,
    surfaceBright = surfaceOverlay,
    surfaceDim = base,
    surfaceContainerLowest = base,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceOverlay,
    scrim = Color.Black,
)

/** Archivo at normal width: body, labels, buttons. */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

/** Archivo Expanded: headlines and display. Set these styles in capitals. */
val ArchivoExpanded = FontFamily(
    Font(R.font.archivo_expanded_bold, FontWeight.Bold),
    Font(R.font.archivo_expanded_extrabold, FontWeight.ExtraBold),
    Font(R.font.archivo_expanded_black, FontWeight.Black),
)

/** Machine values only: MAC addresses, cell IDs, fingerprints. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/**
 * The full Clairix type scale. Styles marked UPPERCASE are always set in
 * capitals: pass text.uppercase(), since TextStyle has no text-transform.
 */
object ClairixType {
    /** UPPERCASE */
    val displayLg = TextStyle(
        fontFamily = ArchivoExpanded,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.005).em,
    )
    /** UPPERCASE */
    val displayMd = TextStyle(
        fontFamily = ArchivoExpanded,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.em,
    )
    /** UPPERCASE */
    val headlineLg = TextStyle(
        fontFamily = ArchivoExpanded,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.em,
    )
    /** UPPERCASE */
    val headlineMd = TextStyle(
        fontFamily = ArchivoExpanded,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (0.005).em,
    )
    /** UPPERCASE */
    val headlineSm = TextStyle(
        fontFamily = ArchivoExpanded,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (0.01).em,
    )
    val titleLg = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.005).em,
    )
    val titleMd = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.em,
    )
    val titleSm = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (0.005).em,
    )
    val bodyLg = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.em,
    )
    val bodyMd = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = (0.005).em,
    )
    val bodySm = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = (0.012).em,
    )
    val labelLg = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = (0.01).em,
    )
    val labelMd = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = (0.02).em,
    )
    /** UPPERCASE */
    val labelSm = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = (0.08).em,
    )
    val mono = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.em,
    )
}

val ClairixTypography = Typography(
    displayLarge = ClairixType.displayLg,
    displayMedium = ClairixType.displayMd,
    displaySmall = ClairixType.headlineLg,
    headlineLarge = ClairixType.headlineLg,
    headlineMedium = ClairixType.headlineMd,
    headlineSmall = ClairixType.headlineSm,
    titleLarge = ClairixType.titleLg,
    titleMedium = ClairixType.titleMd,
    titleSmall = ClairixType.titleSm,
    bodyLarge = ClairixType.bodyLg,
    bodyMedium = ClairixType.bodyMd,
    bodySmall = ClairixType.bodySm,
    labelLarge = ClairixType.labelLg,
    labelMedium = ClairixType.labelMd,
    labelSmall = ClairixType.labelSm,
)

val ClairixShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/** The brand's cut corner, from the octagon in the logo. For badges, tags and hero cards. */
val ClairixChamfer = CutCornerShape(8.dp)

/** 4 dp spacing grid. */
object ClairixSpace {
    val s0 = 0.dp
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
    val s16 = 64.dp
    val s20 = 80.dp
    val s24 = 96.dp
}

/** Elevation in dp. On black, depth is mostly the surface step. */
object ClairixElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
}

/** Fast and decisive, never bouncy. Durations in milliseconds. */
object ClairixMotion {
    const val instant = 0
    const val fast = 90
    const val base = 160
    const val slow = 260
    const val reveal = 420
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val snap = CubicBezierEasing(0.5f, 0f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

val LocalClairixColors = staticCompositionLocalOf { AcidColors }

@Composable
fun ClairixTheme(
    theme: ClairixThemeName = ClairixThemeName.ACID,
    content: @Composable () -> Unit,
) {
    val colors = theme.colors()
    CompositionLocalProvider(LocalClairixColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = ClairixTypography,
            shapes = ClairixShapes,
            content = content,
        )
    }
}

/** Clairix-only roles: ClairixTheme.colors.safe, .caution, .textSubtle ... */
object ClairixTheme {
    val colors: ClairixColors
        @Composable get() = LocalClairixColors.current
}
