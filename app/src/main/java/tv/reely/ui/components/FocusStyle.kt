package tv.reely.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted

/*
 * How focus looks, in one place, so every card and every button says it the same way.
 *
 * On a card, focus is light rather than an outline: the card lifts, a white ring sits a
 * little way off the artwork, and it glows from behind in the colour of what is on
 * screen. The rest of its row steps back. The coral outline this replaces was easy to
 * lose against a busy poster, and coral is the brand colour, not a signal.
 *
 * On a button or a chip, focus is a white pill with dark text. One look for focus
 * everywhere means nobody has to learn which kind of highlight means "you are here".
 */

/** Card corners: posters are narrower, so a smaller radius reads as the same curve. */
val PosterCorner = 12.dp
val WideCorner = 14.dp

private const val LIFT = 1.08f
private const val STEPPED_BACK = 0.72f
private val RING_GAP = 5.dp
private val RING_WIDTH = 3.dp
private val GLOW = 20.dp

/**
 * The colour of what is on screen, for the glow behind a focused card. Set from the
 * artwork where a screen knows it; coral until then.
 */
val LocalTint = compositionLocalOf { Accent }

/** Whether something in the enclosing row has focus, so the rest of it can step back. */
private val LocalRowFocused = compositionLocalOf { false }

/**
 * A row, or grid, whose unfocused cards step back while one of them has focus. The
 * content is handed the modifier to put on the row itself.
 */
@Composable
fun FocusRow(content: @Composable (Modifier) -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalRowFocused provides hasFocus) {
        content(Modifier.onFocusChanged { hasFocus = it.hasFocus })
    }
}

/**
 * On a card's outermost layout: the lift, drawing over its neighbours while lifted,
 * and stepping back while a neighbour has focus.
 */
@Composable
fun Modifier.cardLift(focused: Boolean): Modifier {
    val lift by animateFloatAsState(if (focused) LIFT else 1f, tween(200), label = "card-lift")
    val stepped = !focused && LocalRowFocused.current
    val alpha by animateFloatAsState(if (stepped) STEPPED_BACK else 1f, tween(200), label = "card-dim")
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = lift
            scaleY = lift
            this.alpha = alpha
        }
}

/**
 * On a card's artwork, before it is clipped: the glow behind it and the ring around it.
 * Both are drawn outside the artwork's bounds, which is why this has to come first.
 */
@Composable
fun Modifier.cardRing(focused: Boolean, corner: Dp, round: Boolean = false): Modifier {
    val on by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "card-ring")
    val tint = LocalTint.current.copy(alpha = 0.75f)
    val shape = if (round) CircleShape else RoundedCornerShape(corner)
    return this
        .shadow(elevation = GLOW * on, shape = shape, clip = false, ambientColor = tint, spotColor = tint)
        .drawWithContent {
            drawContent()
            if (on <= 0f) return@drawWithContent
            val gap = RING_GAP.toPx()
            val stroke = RING_WIDTH.toPx()
            val inset = gap + stroke / 2
            val ring = Chalk.copy(alpha = on)
            if (round) {
                drawCircle(ring, radius = size.minDimension / 2 + inset, style = Stroke(stroke))
            } else {
                val r = corner.toPx() + inset
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-inset, -inset),
                    size = Size(size.width + inset * 2, size.height + inset * 2),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(stroke),
                )
            }
        }
}

/** A pill-shaped control's fill and text, for each of the states it can be in. */
class PillColors(val fill: Color, val text: Color)

/**
 * Focused is a white pill with dark text, whatever the control. Otherwise a pill is
 * faintly filled — so it can be seen to be a button at all, which on a panel it could
 * not be before — brighter when it is the selected one, and coral when it is the
 * action a screen is built around.
 */
fun pillColors(focused: Boolean, selected: Boolean = false, emphasised: Boolean = false): PillColors = when {
    focused -> PillColors(Chalk, Ink)
    emphasised -> PillColors(Accent.copy(alpha = 0.26f), Chalk)
    selected -> PillColors(Chalk.copy(alpha = 0.16f), Chalk)
    else -> PillColors(Chalk.copy(alpha = 0.08f), Muted)
}
