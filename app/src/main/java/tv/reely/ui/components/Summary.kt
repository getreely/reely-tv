package tv.reely.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/** Lines shown before a summary has to be opened to read the rest. */
const val SUMMARY_LINES = 3

/** Past this many lines an opened summary steps down a size rather than run on. */
private const val OPENED_LINES = 8

/**
 * Sizes an opened summary may take, largest first. Its closed size first, so a summary
 * a little over three lines opens without changing size; longer ones step down to fit.
 */
private val OPENED_STYLES = listOf(
    ReelyType.Body,
    ReelyType.Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
    ReelyType.Body.copy(fontSize = 15.sp, lineHeight = 21.sp),
)

/**
 * A summary cut to [SUMMARY_LINES] lines that the remote can select to read the rest.
 * It only takes focus when there is more to read, so a short one is not a stop on the
 * way from the buttons to the seasons. Opened, it keeps to about [OPENED_LINES] lines by
 * dropping a size when the text is long, so the buttons below stay close by.
 */
@Composable
fun ExpandableSummary(
    text: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 640.dp,
) {
    // Keyed on the text: moving to another episode closes it again.
    var open by remember(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.widthIn(max = maxWidth)) {
        val width = constraints.maxWidth
        val style: TextStyle = remember(text, width, open) {
            if (!open) return@remember ReelyType.Body
            OPENED_STYLES.firstOrNull { candidate ->
                measurer.measure(text, candidate, constraints = Constraints(maxWidth = width)).lineCount <= OPENED_LINES
            } ?: OPENED_STYLES.last()
        }
        val ring by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "summary-ring")
        val colour by animateColorAsState(if (focused) Chalk else Muted, tween(180), label = "summary-text")
        Text(
            text = text,
            color = colour,
            style = style,
            // Three lines held even when shorter, so the buttons don't shift between a
            // show and an episode with a one-line summary.
            minLines = if (open) 1 else SUMMARY_LINES,
            maxLines = if (open) Int.MAX_VALUE else SUMMARY_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) cut = it.hasVisualOverflow },
            modifier = Modifier
                // A soft panel drawn out past the text rather than padding it, so the
                // words don't move when focus arrives.
                .drawBehind {
                    if (ring <= 0f) return@drawBehind
                    val padX = 14.dp.toPx()
                    val padY = 8.dp.toPx()
                    val corner = CornerRadius(14.dp.toPx())
                    val topLeft = Offset(-padX, -padY)
                    val box = Size(size.width + padX * 2, size.height + padY * 2)
                    drawRoundRect(Chalk.copy(alpha = 0.08f * ring), topLeft, box, corner)
                    drawRoundRect(Chalk.copy(alpha = 0.55f * ring), topLeft, box, corner, style = Stroke(2.dp.toPx()))
                }
                .animateContentSize(tween(220))
                .then(
                    if (cut || open) {
                        Modifier.clickable(interactionSource = interaction, indication = null) { open = !open }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
