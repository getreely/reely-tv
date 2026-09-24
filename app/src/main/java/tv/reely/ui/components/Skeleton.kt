package tv.reely.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.geometry.Size
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.reely.ui.theme.Chalk

/*
 * Placeholders in the shape of what is on its way, instead of a line of text saying
 * "Loading…". The screen keeps its layout while it fills in, so nothing jumps, and the
 * wait reads as the screen arriving rather than as something having gone wrong.
 */

/** Where the light is in its sweep across the screen, 0 to 1; negative when it is held still. */
private val LocalShimmer = staticCompositionLocalOf<State<Float>?> { null }

/**
 * One sweep of light shared by every placeholder inside, so they shimmer as one sheet
 * rather than each on its own clock. Held still when the system's animations are off.
 */
@Composable
fun Shimmer(content: @Composable () -> Unit) {
    val phase = if (reducedMotion()) {
        remember { mutableFloatStateOf(-1f) }
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmer-phase",
        )
    }
    CompositionLocalProvider(LocalShimmer provides phase, content = content)
}

/** Animations switched off in the system's settings, which some people need. */
@Composable
private fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * A faint shape where content will be. The sweep is placed by window position, so it
 * crosses a row of these as one band of light instead of flashing in each separately.
 */
fun Modifier.placeholder(corner: Dp = 6.dp): Modifier = composed {
    val phase = LocalShimmer.current
    val screenWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    var left by remember { mutableFloatStateOf(0f) }
    this
        .onGloballyPositioned { left = it.positionInWindow().x }
        .clip(RoundedCornerShape(corner))
        .background(Chalk.copy(alpha = 0.07f))
        .drawBehind {
            val p = phase?.value ?: return@drawBehind
            if (p < 0f) return@drawBehind
            val band = size.height.coerceAtLeast(160.dp.toPx()) * 1.5f
            val centre = -band + (screenWidth + 2 * band) * p - left
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Chalk.copy(alpha = 0.07f),
                    1f to Color.Transparent,
                    startX = centre - band,
                    endX = centre + band,
                )
            )
        }
}

/** A line of text that has not arrived yet. */
@Composable
fun TextPlaceholder(width: Dp, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(width).height(height).placeholder(corner = height / 2))
}

/** A poster card's shape: the art, then its two lines, at the card's own size. */
@Composable
fun PosterPlaceholder(modifier: Modifier = Modifier, width: Dp = 132.dp) {
    Column(modifier = modifier.width(width).padding(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).placeholder(PosterCorner))
        Column(
            modifier = Modifier.height(40.dp).padding(top = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TextPlaceholder(width = width * 0.7f, height = 10.dp)
            TextPlaceholder(width = width * 0.45f, height = 8.dp)
        }
    }
}

/** An episode tile's shape: a 16:9 still and its caption. */
@Composable
fun EpisodePlaceholder(modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(186.dp).padding(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).placeholder(WideCorner))
        Column(
            modifier = Modifier.height(44.dp).padding(top = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TextPlaceholder(width = 130.dp, height = 10.dp)
            TextPlaceholder(width = 60.dp, height = 8.dp)
        }
    }
}

/** A row of posters under its heading, laid out the way Home lays out the real thing. */
@Composable
fun PosterRowPlaceholder(count: Int = 8) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextPlaceholder(width = 200.dp, height = 16.dp, modifier = Modifier.padding(start = 40.dp))
        OffTheEdge { repeat(count) { PosterPlaceholder() } }
    }
}

/**
 * A row as wide as its cards, running off the right of the screen as a real row does,
 * rather than squeezed to fit — which crushed the last cards into slivers.
 */
@Composable
private fun OffTheEdge(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        Row(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

/** The title, facts and summary a hero will have. */
@Composable
fun HeroPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextPlaceholder(width = 340.dp, height = 34.dp)
        TextPlaceholder(width = 220.dp, height = 12.dp, modifier = Modifier.padding(top = 4.dp))
        TextPlaceholder(width = 540.dp, height = 12.dp, modifier = Modifier.padding(top = 6.dp))
        TextPlaceholder(width = 500.dp, height = 12.dp)
        TextPlaceholder(width = 380.dp, height = 12.dp)
    }
}

/** A detail page before its details: hero text, the buttons, and a rail of episodes. */
@Composable
fun DetailPlaceholder(modifier: Modifier = Modifier) {
    Shimmer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            HeroPlaceholder(modifier = Modifier.padding(start = 48.dp, top = 40.dp))
            Row(
                modifier = Modifier.padding(start = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.width(132.dp).height(44.dp).placeholder(corner = 22.dp))
                repeat(3) { Box(modifier = Modifier.size(44.dp).placeholder(corner = 22.dp)) }
            }
            EpisodeRailPlaceholder()
        }
    }
}

/** A rail of episode tiles, inset like the real one. */
@Composable
fun EpisodeRailPlaceholder(count: Int = 6) {
    OffTheEdge { repeat(count) { EpisodePlaceholder() } }
}

/**
 * Something is on its way and there is no shape to hold for it — a stream starting.
 * A ring turning, still when animations are off.
 */
@Composable
fun LoadingRing(modifier: Modifier = Modifier, diameter: Dp = 44.dp) {
    val angle by if (reducedMotion()) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "ring").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "ring-angle",
        )
    }
    Canvas(modifier = modifier.size(diameter)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        drawCircle(
            color = Chalk.copy(alpha = 0.18f),
            radius = diameter.toPx() / 2 - inset,
            style = Stroke(stroke),
        )
        rotate(angle) {
            drawArc(
                color = Chalk,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}
