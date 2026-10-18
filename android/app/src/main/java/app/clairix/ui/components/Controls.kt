package app.clairix.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clairix.ui.theme.ClairixTheme

/** The three weights of button. Buttons are verbs in sentence case: "Scan room", "Find it". */
enum class ButtonKind { Primary, Secondary, Ghost }

/**
 * A Clairix button. [Primary] fills with the [tone]'s colour and is used once
 * per screen for the main action; [Secondary] is outlined; [Ghost] is text only.
 * Every button is at least 48 dp tall.
 */
@Composable
fun ClairixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    tone: Tone = Tone.Accent,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false
) {
    val colors = ClairixTheme.colors
    val fg = if (tone == Tone.Neutral) colors.text else tone.color()
    val shape = ClairixShapes.Control
    val padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    val label: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = if (kind == ButtonKind.Primary) tone.onColor() else fg,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val sized = modifier.heightIn(min = 48.dp)
    when (kind) {
        ButtonKind.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tone == Tone.Neutral) colors.surfaceOverlay else tone.color(),
                contentColor = if (tone == Tone.Neutral) colors.text else tone.onColor(),
                disabledContainerColor = colors.surfaceOverlay,
                disabledContentColor = colors.textSubtle
            ),
            modifier = sized
        ) { label() }
        ButtonKind.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.5.dp, if (enabled) (if (tone == Tone.Neutral) colors.borderStrong else fg) else colors.border),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = fg,
                disabledContentColor = colors.textSubtle
            ),
            modifier = sized
        ) { label() }
        ButtonKind.Ghost -> TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = fg, disabledContentColor = colors.textSubtle),
            modifier = sized
        ) { label() }
    }
}

/** The screen's main action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Accent,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false
) = ClairixButton(text, onClick, modifier, ButtonKind.Primary, tone, enabled, icon, loading)

/** A supporting action beside or below the primary one. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
    icon: ImageVector? = null
) = ClairixButton(text, onClick, modifier, ButtonKind.Secondary, tone, enabled, icon)

/** A low-emphasis action: dismiss, copy, show more. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Accent,
    enabled: Boolean = true,
    icon: ImageVector? = null
) = ClairixButton(text, onClick, modifier, ButtonKind.Ghost, tone, enabled, icon)

/** The brand switch: an acid track when on, a quiet outline when off. */
@Composable
fun ClairixSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = ClairixTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = c.accent,
            uncheckedThumbColor = c.textSubtle,
            uncheckedTrackColor = c.surfaceOverlay,
            uncheckedBorderColor = c.borderStrong,
            disabledCheckedTrackColor = c.accentContainer,
            disabledCheckedThumbColor = c.textSubtle,
            disabledUncheckedTrackColor = c.surface,
            disabledUncheckedThumbColor = c.border,
            disabledUncheckedBorderColor = c.border
        )
    )
}

/**
 * A setting with a switch. The whole row is the touch target and reads as one
 * switch to accessibility services.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) ClairixTheme.colors.text else ClairixTheme.colors.textSubtle
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            }
        }
        ClairixSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A themed slider. */
@Composable
fun ClairixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val c = ClairixTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.border,
            activeTickColor = c.onAccent,
            inactiveTickColor = c.borderStrong
        )
    )
}

/** Colours for any text field in the app, so the ones with extra slots match [ClairixTextField]. */
@Composable
fun clairixTextFieldColors(): TextFieldColors {
    val c = ClairixTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        disabledTextColor = c.textSubtle,
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface,
        disabledContainerColor = c.surface,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.borderStrong,
        disabledBorderColor = c.border,
        errorBorderColor = c.danger,
        cursorColor = c.accent,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.textSubtle,
        errorLabelColor = c.danger,
        focusedPlaceholderColor = c.textSubtle,
        unfocusedPlaceholderColor = c.textSubtle,
        errorSupportingTextColor = c.danger,
        focusedSupportingTextColor = c.textSubtle,
        unfocusedSupportingTextColor = c.textSubtle
    )
}

/** A single-line text field in the brand's chamfered control shape. */
@Composable
fun ClairixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        singleLine = singleLine,
        isError = isError,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = ClairixShapes.Control,
        colors = clairixTextFieldColors(),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}

/**
 * Sub-pages of one destination, as a row of chamfered segments. Scrolls
 * sideways when the labels do not fit.
 */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ClairixTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .background(if (on) c.accent else c.surface, ClairixShapes.Control)
                    .border(1.dp, if (on) c.accent else c.border, ClairixShapes.Control)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) c.onAccent else c.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * A row of choices where exactly one is picked, such as a wipe limit. Each
 * option is a 48 dp target and reads as a radio button.
 */
@Composable
fun <T> ChoiceChips(
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = ClairixTheme.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { choice ->
            val on = choice == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .background(if (on) c.accentContainer else c.surfaceRaised, ClairixShapes.Control)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(choice) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(choice),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) c.onAccentContainer else c.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}

/** Spacer that keeps vertical rhythm on the 8 dp grid. */
@Composable
fun VSpace(units: Int = 1) = Spacer(Modifier.height((8 * units).dp))

/** A whole-width clickable row with no ripple styling of its own. */
internal fun Modifier.rowClick(onClick: (() -> Unit)?, label: String?): Modifier =
    if (onClick == null) this else this.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
