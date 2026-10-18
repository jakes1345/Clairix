package app.clairix.ui.components

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.clairix.ui.theme.ClairixTheme

/**
 * What a piece of UI is telling the user, independent of the colour theme.
 * Every status colour in the app is chosen through a Tone, so all four themes
 * render the same meaning with their own palette.
 */
enum class Tone {
    /** Informational, nothing to judge. */
    Neutral,

    /** The brand accent: primary actions, selection, focus. Never a verdict. */
    Accent,

    /** Checked and clear. */
    Safe,

    /** Worth a look. */
    Caution,

    /** Found something that needs acting on. */
    Danger,

    /** A neutral fact the user should notice, such as an active mode. */
    Info,
}

/** The tone's foreground colour: text, icons and strokes. */
@Composable
fun Tone.color(): Color = with(ClairixTheme.colors) {
    when (this@color) {
        Tone.Neutral -> textMuted
        Tone.Accent -> accent
        Tone.Safe -> safe
        Tone.Caution -> caution
        Tone.Danger -> danger
        Tone.Info -> info
    }
}

/** A dark, tinted fill that the tone's [color] reads on with AA contrast. */
@Composable
fun Tone.container(): Color = with(ClairixTheme.colors) {
    when (this@container) {
        Tone.Neutral -> surfaceOverlay
        Tone.Accent -> accentContainer
        Tone.Safe -> safeContainer
        Tone.Caution -> cautionContainer
        Tone.Danger -> dangerContainer
        Tone.Info -> infoContainer
    }
}

/** Text and icons drawn on a solid [color] fill. */
@Composable
fun Tone.onColor(): Color = with(ClairixTheme.colors) {
    when (this@onColor) {
        Tone.Neutral -> base
        Tone.Accent -> onAccent
        Tone.Safe -> onSafe
        Tone.Caution -> onCaution
        Tone.Danger -> onDanger
        Tone.Info -> onInfo
    }
}

/**
 * The brand's cut corners, from the octagonal C. Cards and buttons cut two
 * opposite corners, like a tag; small marks (pills, icon wells) cut all four.
 */
object ClairixShapes {
    /** Cards, list groups and tiles. */
    val Card = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)

    /** The large status panel at the top of a screen. */
    val Hero = CutCornerShape(topStart = 24.dp, bottomEnd = 24.dp)

    /** Buttons and text fields. */
    val Control = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)

    /** Status pills and tags. */
    val Tag = CutCornerShape(4.dp)

    /** Icon wells: an octagon cut at the logo's own 24 % chamfer. */
    val Octagon = CutCornerShape(24)

    /** Dialogs and bottom sheets. */
    val Sheet = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)
}
