package tv.reely.ui.screens

import tv.reely.ui.components.keepCursorInside
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceRaised

/** Which of the remote's buttons a step is about, lit on the drawing. */
internal enum class RemoteKey { NONE, UP, SELECT, LEFT_RIGHT, DOWN }

internal data class TourStep(
    val title: String,
    val body: String,
    val key: RemoteKey,
    /** Held rather than pressed: the lit button pulses. */
    val hold: Boolean = false,
)

/**
 * How to get around, a step at a time: what the remote's buttons do on the screens where
 * that isn't obvious. Written from what the app does, so it can't promise what isn't so.
 */
internal val TOUR = listOf(
    TourStep(
        "Welcome to Reely",
        "A quick look at getting around with your remote. It takes a minute, and you can skip it.",
        RemoteKey.NONE,
    ),
    TourStep(
        "The top row",
        "Press Up to reach the tabs: Search, Home, Movies, TV Shows, Live TV and Request. " +
            "Settings is the gear on the right. Moving onto a tab opens it.",
        RemoteKey.UP,
    ),
    TourStep(
        "Hold OK for more",
        "Hold OK on any poster for more: carry on or start again, mark it watched, or go " +
            "to its page. On a show, it plays the next episode.",
        RemoteKey.SELECT,
        hold = true,
    ),
    TourStep(
        "While you watch",
        "Left and Right skip back and forward, with a picture of where you'll land; hold " +
            "them to go faster. Up or Down brings up the controls, with subtitles, audio and " +
            "chapters on the right. Back puts them away.",
        RemoteKey.LEFT_RIGHT,
    ),
    TourStep(
        "Live TV",
        "Left and Right change channel, and Down opens the guide. Hold OK to put another " +
            "channel beside the first, up to four. In the guide, hold OK on a channel to add " +
            "it to Favorites, or to be reminded when something starts.",
        RemoteKey.DOWN,
    ),
    TourStep(
        "Can't find something?",
        "The Request tab finds movies and shows your server doesn't have yet and asks for " +
            "them. The first time, enter your Reely address there.",
        RemoteKey.NONE,
    ),
    TourStep(
        "You're all set",
        "You can take this tour again from Settings, under About.",
        RemoteKey.NONE,
    ),
)

/**
 * The tour, over whatever is on screen. Next and Back step through it; Skip, or Back on the
 * first step, ends it. It holds the cursor while it is up, and the step it's on survives
 * the screen being rebuilt.
 */
@Composable
fun Tour(onDone: () -> Unit, modifier: Modifier = Modifier) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = TOUR[index]
    val last = index == TOUR.lastIndex
    BackHandler { if (index == 0) onDone() else index-- }

    val next = remember { FocusRequester() }
    // On the step's own button each time: Next becomes Start watching on the last step.
    // Asked for until it's there, as the update prompt does: the tour can come up in the
    // same moment as the screen under it, which is asking for the cursor too.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        repeat(40) {
            if (landed) return@LaunchedEffect
            next.requestWhenReady(attempts = 1)
            delay(50)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.82f))
            // Back taken here, before anything else sees it, as the player does: left to
            // the back dispatcher it could take two presses, or leave the app as well.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    if (index == 0) onDone() else index--
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(760.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceRaised)
                .border(1.dp, Chalk.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(horizontal = 36.dp, vertical = 30.dp)
                .keepCursorInside()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteDrawing(lit = step.key, pulse = step.hold, modifier = Modifier.size(width = 120.dp, height = 220.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepDots(count = TOUR.size, at = index)
                Text(text = step.title, color = Chalk, style = ReelyType.Headline)
                Text(text = step.body, color = Muted, style = ReelyType.Body)
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvActionButton(
                        label = if (last) "Start watching" else "Next",
                        onClick = { if (last) onDone() else index++ },
                        emphasised = true,
                        modifier = Modifier
                            .focusRequester(next)
                            .onFocusChanged { landed = it.isFocused },
                    )
                    if (index > 0 && !last) TvActionButton(label = "Back", onClick = { index-- })
                    if (!last) TvActionButton(label = "Skip tour", onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun StepDots(count: Int, at: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .size(width = if (i == at) 18.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (i == at) Accent else Chalk.copy(alpha = 0.25f)),
            )
        }
    }
}

/**
 * The top of a Fire TV remote: the ring of arrows round OK, then Back, Home and Menu, then
 * rewind, play and fast-forward. Whatever the step is about is lit.
 */
@Composable
private fun RemoteDrawing(lit: RemoteKey, pulse: Boolean, modifier: Modifier = Modifier) {
    // Only animated on the step about holding a button: nothing else needs a frame drawn.
    val on = if (pulse) Accent.copy(alpha = holdBeat()) else Accent
    val body = Chalk.copy(alpha = 0.08f)
    val line = Chalk.copy(alpha = 0.35f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // The body.
        drawRoundRect(body, size = Size(w, h), cornerRadius = CornerRadius(w * 0.42f))
        drawRoundRect(line, size = Size(w, h), cornerRadius = CornerRadius(w * 0.42f), style = Stroke(w * 0.015f))

        // The ring: four arrows round OK.
        val ring = Offset(w / 2, h * 0.3f)
        val outer = w * 0.36f
        drawCircle(line, radius = outer, center = ring, style = Stroke(w * 0.02f))
        fun arrow(dx: Float, dy: Float, color: Color) = drawArrow(ring + Offset(dx, dy) * (outer * 0.72f), dx, dy, w * 0.07f, color)
        arrow(0f, -1f, if (lit == RemoteKey.UP) on else line)
        arrow(0f, 1f, if (lit == RemoteKey.DOWN) on else line)
        arrow(-1f, 0f, if (lit == RemoteKey.LEFT_RIGHT) on else line)
        arrow(1f, 0f, if (lit == RemoteKey.LEFT_RIGHT) on else line)
        drawCircle(if (lit == RemoteKey.SELECT) on else line, radius = outer * 0.42f, center = ring,
            style = if (lit == RemoteKey.SELECT) androidx.compose.ui.graphics.drawscope.Fill else Stroke(w * 0.02f))

        // Back, Home, Menu; then rewind, play, fast-forward.
        listOf(h * 0.62f, h * 0.78f).forEach { y ->
            listOf(0.26f, 0.5f, 0.74f).forEach { x ->
                drawCircle(line, radius = w * 0.075f, center = Offset(w * x, y), style = Stroke(w * 0.02f))
            }
        }
    }
}

/** A slow throb, for a button that is held rather than pressed. */
@Composable
private fun holdBeat(): Float {
    val beat by rememberInfiniteTransition(label = "hold").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "hold",
    )
    return beat
}

private fun DrawScope.drawArrow(tip: Offset, dx: Float, dy: Float, s: Float, color: Color) {
    // A small chevron pointing along (dx, dy).
    val back = Offset(-dx, -dy) * s
    val side = Offset(-dy, dx) * s
    val path = Path().apply {
        moveTo(tip.x + back.x + side.x, tip.y + back.y + side.y)
        lineTo(tip.x, tip.y)
        lineTo(tip.x + back.x - side.x, tip.y + back.y - side.y)
    }
    drawPath(path, color, style = Stroke(width = s * 0.45f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}
