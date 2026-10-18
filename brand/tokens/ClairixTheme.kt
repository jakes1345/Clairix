// Clairix theme for Jetpack Compose.
// Generated from brand/tokens/tokens.json by brand/tokens/build.mjs. Do not edit by hand.
//
// To use: copy this file into app/src/main/java/app/clairix/ui/theme/, and copy
// these fonts from brand/fonts into app/src/main/res/font/ (lowercase names):
//   inter/static/Inter-Regular.ttf          -> inter_regular.ttf
//   inter/static/Inter-Medium.ttf           -> inter_medium.ttf
//   inter/static/Inter-SemiBold.ttf         -> inter_semibold.ttf
//   inter/static/Inter-Bold.ttf             -> inter_bold.ttf
//   inter/static/InterDisplay-Medium.ttf    -> inter_display_medium.ttf
//   inter/static/InterDisplay-SemiBold.ttf  -> inter_display_semibold.ttf
//   jetbrains-mono/static/JetBrainsMono-Regular.ttf -> jetbrains_mono_regular.ttf
//   jetbrains-mono/static/JetBrainsMono-Medium.ttf  -> jetbrains_mono_medium.ttf
// Then wrap the app content in ClairixTheme { ... }.

package app.clairix.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

/** Raw palette. Prefer MaterialTheme.colorScheme or ClairixTheme.colors in UI code. */
object ClairixPalette {
    /** oklch(1 0 0) */
    val White = Color(0xFFFFFFFF)
    /** oklch(0.135 0.018 266) */
    val Ink = Color(0xFF050810)
    /** oklch(0.985 0.002 260) */
    val Neutral50 = Color(0xFFF9FAFB)
    /** oklch(0.965 0.004 260) */
    val Neutral100 = Color(0xFFF2F3F6)
    /** oklch(0.925 0.007 260) */
    val Neutral200 = Color(0xFFE3E6EB)
    /** oklch(0.87 0.01 260) */
    val Neutral300 = Color(0xFFD0D4DB)
    /** oklch(0.72 0.015 260) */
    val Neutral400 = Color(0xFF9FA5AE)
    /** oklch(0.555 0.018 260) */
    val Neutral500 = Color(0xFF6D737E)
    /** oklch(0.47 0.02 260) */
    val Neutral600 = Color(0xFF545B66)
    /** oklch(0.375 0.02 262) */
    val Neutral700 = Color(0xFF3B414C)
    /** oklch(0.285 0.02 262) */
    val Neutral800 = Color(0xFF252A34)
    /** oklch(0.215 0.02 264) */
    val Neutral900 = Color(0xFF151923)
    /** oklch(0.17 0.02 264) */
    val Neutral950 = Color(0xFF0B0F18)
    /** oklch(0.975 0.013 252) */
    val Blue50 = Color(0xFFF1F8FF)
    /** oklch(0.94 0.03 252) */
    val Blue100 = Color(0xFFDDEDFF)
    /** oklch(0.885 0.06 252) */
    val Blue200 = Color(0xFFBEDCFF)
    /** oklch(0.8 0.1 252) */
    val Blue300 = Color(0xFF8EC2FD)
    /** oklch(0.71 0.14 252) */
    val Blue400 = Color(0xFF5BA5F6)
    /** oklch(0.625 0.17 252) */
    val Blue500 = Color(0xFF2489EA)
    /** oklch(0.55 0.17 254) */
    val Blue600 = Color(0xFF0C71D1)
    /** oklch(0.47 0.15 256) */
    val Blue700 = Color(0xFF1059AC)
    /** oklch(0.39 0.12 258) */
    val Blue800 = Color(0xFF154384)
    /** oklch(0.31 0.09 260) */
    val Blue900 = Color(0xFF122E5D)
    /** oklch(0.23 0.065 262) */
    val Blue950 = Color(0xFF0B1B3B)
    /** oklch(0.955 0.035 160) */
    val Green100 = Color(0xFFDDF8E8)
    /** oklch(0.8 0.13 160) */
    val Green300 = Color(0xFF68D7A1)
    /** oklch(0.52 0.12 160) */
    val Green600 = Color(0xFF007D51)
    /** oklch(0.46 0.105 160) */
    val Green700 = Color(0xFF006943)
    /** oklch(0.28 0.05 165) */
    val Green900 = Color(0xFF0A3123)
    /** oklch(0.965 0.045 85) */
    val Amber100 = Color(0xFFFFF2D8)
    /** oklch(0.84 0.13 80) */
    val Amber300 = Color(0xFFF7C15F)
    /** oklch(0.78 0.15 75) */
    val Amber500 = Color(0xFFEFA831)
    /** oklch(0.52 0.11 60) */
    val Amber700 = Color(0xFF965719)
    /** oklch(0.29 0.05 70) */
    val Amber900 = Color(0xFF3B260C)
    /** oklch(0.955 0.025 25) */
    val Red100 = Color(0xFFFFEBE9)
    /** oklch(0.77 0.12 25) */
    val Red300 = Color(0xFFF7958D)
    /** oklch(0.55 0.18 27) */
    val Red600 = Color(0xFFC53732)
    /** oklch(0.5 0.17 27) */
    val Red700 = Color(0xFFB02B27)
    /** oklch(0.29 0.07 25) */
    val Red900 = Color(0xFF481B19)
    /** oklch(0.955 0.025 225) */
    val Sky100 = Color(0xFFDFF4FD)
    /** oklch(0.81 0.1 225) */
    val Sky300 = Color(0xFF74CFF2)
    /** oklch(0.53 0.11 230) */
    val Sky600 = Color(0xFF00769D)
    /** oklch(0.47 0.1 232) */
    val Sky700 = Color(0xFF006387)
    /** oklch(0.28 0.05 235) */
    val Sky900 = Color(0xFF0A2C3E)
}

val ClairixLightColorScheme: ColorScheme = lightColorScheme(
    primary = ClairixPalette.Blue600,
    onPrimary = ClairixPalette.White,
    primaryContainer = ClairixPalette.Blue100,
    onPrimaryContainer = ClairixPalette.Blue800,
    inversePrimary = ClairixPalette.Blue300,
    secondary = ClairixPalette.Neutral700,
    onSecondary = ClairixPalette.White,
    secondaryContainer = ClairixPalette.Neutral100,
    onSecondaryContainer = ClairixPalette.Neutral800,
    tertiary = ClairixPalette.Sky600,
    onTertiary = ClairixPalette.White,
    tertiaryContainer = ClairixPalette.Sky100,
    onTertiaryContainer = ClairixPalette.Sky700,
    background = ClairixPalette.Neutral50,
    onBackground = ClairixPalette.Neutral950,
    surface = ClairixPalette.White,
    onSurface = ClairixPalette.Neutral950,
    surfaceVariant = ClairixPalette.Neutral100,
    onSurfaceVariant = ClairixPalette.Neutral600,
    surfaceTint = ClairixPalette.Blue600,
    inverseSurface = ClairixPalette.Neutral900,
    inverseOnSurface = ClairixPalette.Neutral50,
    error = ClairixPalette.Red600,
    onError = ClairixPalette.White,
    errorContainer = ClairixPalette.Red100,
    onErrorContainer = ClairixPalette.Red700,
    outline = ClairixPalette.Neutral300,
    outlineVariant = ClairixPalette.Neutral200,
    scrim = ClairixPalette.Ink,
    surfaceBright = ClairixPalette.White,
    surfaceDim = ClairixPalette.Neutral200,
    surfaceContainerLowest = ClairixPalette.White,
    surfaceContainerLow = ClairixPalette.Neutral50,
    surfaceContainer = ClairixPalette.Neutral100,
    surfaceContainerHigh = ClairixPalette.Neutral200,
    surfaceContainerHighest = ClairixPalette.Neutral300,
)

val ClairixDarkColorScheme: ColorScheme = darkColorScheme(
    primary = ClairixPalette.Blue400,
    onPrimary = ClairixPalette.Blue950,
    primaryContainer = ClairixPalette.Blue900,
    onPrimaryContainer = ClairixPalette.Blue100,
    inversePrimary = ClairixPalette.Blue600,
    secondary = ClairixPalette.Neutral300,
    onSecondary = ClairixPalette.Neutral900,
    secondaryContainer = ClairixPalette.Neutral800,
    onSecondaryContainer = ClairixPalette.Neutral100,
    tertiary = ClairixPalette.Sky300,
    onTertiary = ClairixPalette.Sky900,
    tertiaryContainer = ClairixPalette.Sky900,
    onTertiaryContainer = ClairixPalette.Sky300,
    background = ClairixPalette.Ink,
    onBackground = ClairixPalette.Neutral50,
    surface = ClairixPalette.Neutral950,
    onSurface = ClairixPalette.Neutral50,
    surfaceVariant = ClairixPalette.Neutral900,
    onSurfaceVariant = ClairixPalette.Neutral300,
    surfaceTint = ClairixPalette.Blue400,
    inverseSurface = ClairixPalette.Neutral100,
    inverseOnSurface = ClairixPalette.Neutral900,
    error = ClairixPalette.Red300,
    onError = ClairixPalette.Red900,
    errorContainer = ClairixPalette.Red900,
    onErrorContainer = ClairixPalette.Red300,
    outline = ClairixPalette.Neutral700,
    outlineVariant = ClairixPalette.Neutral800,
    scrim = ClairixPalette.Ink,
    surfaceBright = ClairixPalette.Neutral800,
    surfaceDim = ClairixPalette.Ink,
    surfaceContainerLowest = ClairixPalette.Ink,
    surfaceContainerLow = ClairixPalette.Neutral950,
    surfaceContainer = ClairixPalette.Neutral900,
    surfaceContainerHigh = ClairixPalette.Neutral800,
    surfaceContainerHighest = ClairixPalette.Neutral700,
)

/** Clairix roles that Material 3 has no slot for: the Glint accent and the status colours. */
@Immutable
data class ClairixColors(
    val glint: Color,
    val safe: Color,
    val onSafe: Color,
    val safeContainer: Color,
    val onSafeContainer: Color,
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val textTertiary: Color,
    val border: Color,
)

val ClairixLightColors = ClairixColors(
    glint = ClairixPalette.Blue500,
    safe = ClairixPalette.Green600,
    onSafe = ClairixPalette.White,
    safeContainer = ClairixPalette.Green100,
    onSafeContainer = ClairixPalette.Green700,
    caution = ClairixPalette.Amber500,
    onCaution = ClairixPalette.Neutral950,
    cautionContainer = ClairixPalette.Amber100,
    onCautionContainer = ClairixPalette.Amber700,
    info = ClairixPalette.Sky600,
    onInfo = ClairixPalette.White,
    infoContainer = ClairixPalette.Sky100,
    onInfoContainer = ClairixPalette.Sky700,
    textTertiary = ClairixPalette.Neutral500,
    border = ClairixPalette.Neutral200,
)

val ClairixDarkColors = ClairixColors(
    glint = ClairixPalette.Blue400,
    safe = ClairixPalette.Green300,
    onSafe = ClairixPalette.Green900,
    safeContainer = ClairixPalette.Green900,
    onSafeContainer = ClairixPalette.Green300,
    caution = ClairixPalette.Amber300,
    onCaution = ClairixPalette.Amber900,
    cautionContainer = ClairixPalette.Amber900,
    onCautionContainer = ClairixPalette.Amber300,
    info = ClairixPalette.Sky300,
    onInfo = ClairixPalette.Sky900,
    infoContainer = ClairixPalette.Sky900,
    onInfoContainer = ClairixPalette.Sky300,
    textTertiary = ClairixPalette.Neutral400,
    border = ClairixPalette.Neutral800,
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Inter's display optical size, for text 20 sp and larger. */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
)

/** Machine values only: MAC addresses, cell IDs, fingerprints. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** The full Clairix type scale, including steps Material has no slot for. */
object ClairixType {
    val displayLg = TextStyle(
        fontFamily = InterDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.025).em,
    )
    val displayMd = TextStyle(
        fontFamily = InterDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.022).em,
    )
    val headlineLg = TextStyle(
        fontFamily = InterDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.02).em,
    )
    val headlineMd = TextStyle(
        fontFamily = InterDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.017).em,
    )
    val headlineSm = TextStyle(
        fontFamily = InterDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.014).em,
    )
    val titleLg = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.011).em,
    )
    val titleMd = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.008).em,
    )
    val titleSm = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.005).em,
    )
    val bodyLg = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.008).em,
    )
    val bodyMd = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.004).em,
    )
    val bodySm = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.em,
    )
    val labelLg = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.004).em,
    )
    val labelMd = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.em,
    )
    val labelSm = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = (0.02).em,
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
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

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

/** Elevation levels in dp. Depth is carried mostly by surface colour. */
object ClairixElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
    val level4 = 12.dp
}

/** Calm motion: short, decelerating, never bouncy. Durations in milliseconds. */
object ClairixMotion {
    const val instant = 0
    const val fast = 120
    const val base = 200
    const val slow = 320
    const val reveal = 480
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val emphasized = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

val LocalClairixColors = staticCompositionLocalOf { ClairixLightColors }

@Composable
fun ClairixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalClairixColors provides if (darkTheme) ClairixDarkColors else ClairixLightColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) ClairixDarkColorScheme else ClairixLightColorScheme,
            typography = ClairixTypography,
            shapes = ClairixShapes,
            content = content,
        )
    }
}

/** Access Clairix-only roles: ClairixTheme.colors.safe, ClairixTheme.colors.glint ... */
object ClairixTheme {
    val colors: ClairixColors
        @Composable get() = LocalClairixColors.current
}
