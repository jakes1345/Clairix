package app.clairix.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clairix.ui.theme.ClairixBrand

/*
 * The Clairix mark: the octagonal C, cut by the stencil slit, locked around the
 * acid X. Path data is the brand's own symbol-reversed.svg and
 * symbol-small-reversed.svg (brand/logo), drawn to the unit; the brand book
 * forbids retyping or redrawing it, so it is only ever scaled.
 */

private const val C_REGULAR =
    "M24 0H76L100 24V27.79L77 34L66 23H34L23 34V46.75H0V24Z" +
        "M23 53.25V66L34 77H66L77 66L100 72.21V76L76 100H24L0 76L0 53.25H23Z"
private const val X_REGULAR =
    "M33.5 33.5H42.69L50 40.81L57.31 33.5H66.5V42.69L59.19 50L66.5 57.31V66.5H57.31" +
        "L50 59.19L42.69 66.5H33.5V57.31L40.81 50L33.5 42.69Z"

// The small master opens the slit and the counter so they survive at 16 to 39 px.
private const val C_SMALL =
    "M21 54.5V70L30 79H70L79 70L100 74.62V76L76 100H24L0 76L0 54.5H21Z" +
        "M24 0H76L100 24V25.38L79 30L70 21H30L21 30V45H0V24Z"
private const val X_SMALL =
    "M31 31H39.84L50 41.16L60.16 31H69V39.84L58.84 50L69 60.16V69H60.16L50 58.84" +
        "L39.84 69H31V60.16L41.16 50L31 39.84Z"

private fun markVector(name: String, c: String, x: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 100.dp,
        defaultHeight = 100.dp,
        viewportWidth = 100f,
        viewportHeight = 100f
    ).apply {
        addPath(pathData = addPathNodes(c), fill = SolidColor(ClairixBrand.Bone))
        addPath(pathData = addPathNodes(x), fill = SolidColor(ClairixBrand.Acid))
    }.build()

private val MarkRegular: ImageVector by lazy(LazyThreadSafetyMode.NONE) { markVector("ClairixMark", C_REGULAR, X_REGULAR) }
private val MarkSmall: ImageVector by lazy(LazyThreadSafetyMode.NONE) { markVector("ClairixMarkSmall", C_SMALL, X_SMALL) }

/**
 * The Clairix mark at [size]. Below 40 dp the small master is used, as the brand
 * book's minimum-size rules require. It never takes a theme colour.
 */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 32.dp, contentDescription: String? = "Clairix") {
    Image(
        imageVector = if (size < 40.dp) MarkSmall else MarkRegular,
        contentDescription = contentDescription,
        modifier = modifier.size(size)
    )
}

/**
 * Display text with the stencil slit, the brand's one horizontal cut, through
 * every line at the middle of the capitals, as the wordmark has it. Use it
 * sparingly: big display headlines only, never body text. The slit is drawn in
 * [ground], the colour behind the text. Accessibility services read the text
 * exactly as written.
 */
@Composable
fun StencilText(
    text: String,
    style: TextStyle,
    color: Color,
    ground: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = 2.dp
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            val h = thickness.toPx()
            // Archivo's capitals are about 0.72 em tall, so their middle sits 0.36 em over the baseline.
            val capMiddle = style.fontSize.toPx() * 0.36f
            for (line in 0 until l.lineCount) {
                val y = l.getLineBaseline(line) - capMiddle
                drawRect(
                    color = ground,
                    topLeft = Offset(l.getLineLeft(line), y - h / 2f),
                    size = Size(l.getLineRight(line) - l.getLineLeft(line), h)
                )
            }
        }
    )
}
