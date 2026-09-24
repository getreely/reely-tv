package tv.reely.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import tv.reely.plex.PlexItem
import tv.reely.ui.components.TitleArt
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.pillColors
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Where the finished episode goes while the next one is offered: top right, in the safe area. */
private val WINDOW_WIDTH = 336.dp
private val WINDOW_TOP = 27.dp
private val WINDOW_END = 48.dp

/**
 * The picture, full screen, or — with something up next — shrunk into a window in the
 * corner while the next episode takes the screen, the way Plex does it. The credits
 * keep playing in the window, and choosing to watch them grows it back.
 *
 * [video] is composed in the same place whichever it is, so the player's surface is
 * never torn down and rebuilt in the middle of the credits.
 */
@Composable
internal fun PostPlay(
    /** What is up next, or null for the picture alone. */
    item: PlexItem?,
    /** The next episode's still, full size. */
    stillUrl: String?,
    logoUrl: String?,
    /** The episode that is finishing, named under its window. */
    nowTitle: String,
    /** Past the end of the file, not just into the credits. */
    ended: Boolean,
    countdownSeconds: Int,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
    /** Watch the credits, or — once there are none left to watch — leave. */
    onDecline: () -> Unit,
    video: @Composable () -> Unit,
) {
    val shrink by animateFloatAsState(
        targetValue = if (item != null) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "post-play",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Drawn over the screen behind it, which the video's surface needs: it shows
        // through a hole in whatever was drawn before it, and is covered by what comes after.
        Box(
            modifier = Modifier
                .zIndex(1f)
                .videoWindow(shrink),
        ) { video() }

        if (item != null) {
            UpNextScreen(
                item = item,
                stillUrl = stillUrl,
                logoUrl = logoUrl,
                nowTitle = nowTitle,
                ended = ended,
                countdownSeconds = countdownSeconds,
                focusRequester = focusRequester,
                onPlay = onPlay,
                onDecline = onDecline,
                modifier = Modifier.graphicsLayer { alpha = shrink },
            )
        }
    }
}

/** Full screen at 0, the corner window at 1, and every size between for the move. */
private fun Modifier.videoWindow(progress: Float) = layout { measurable, constraints ->
    val fullWidth = constraints.maxWidth
    val fullHeight = constraints.maxHeight
    val windowWidth = WINDOW_WIDTH.roundToPx()
    val windowHeight = windowWidth * 9 / 16
    val width = lerp(fullWidth, windowWidth, progress)
    val height = lerp(fullHeight, windowHeight, progress)
    val x = lerp(0, fullWidth - WINDOW_END.roundToPx() - windowWidth, progress)
    val y = lerp(0, WINDOW_TOP.roundToPx(), progress)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(fullWidth, fullHeight) { placeable.place(x, y) }
}

@Composable
private fun UpNextScreen(
    item: PlexItem,
    stillUrl: String?,
    logoUrl: String?,
    nowTitle: String,
    ended: Boolean,
    countdownSeconds: Int,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Ink)) {
        AsyncImage(
            model = stillUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Dark enough on the left and along the bottom for the text; the still carries
        // on through at the top right, where the window sits.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Ink.copy(alpha = 0.9f),
                        0.5f to Ink.copy(alpha = 0.55f),
                        1f to Ink.copy(alpha = 0.1f),
                    )
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Ink.copy(alpha = 0.85f),
                    )
                ),
        )

        // A hairline frame just outside the window, so it reads as a window and not as
        // a hole in the picture. Drawn here, under it: the video cannot be clipped round.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = WINDOW_TOP - 2.dp, end = WINDOW_END - 2.dp)
                .size(width = WINDOW_WIDTH + 4.dp, height = WINDOW_WIDTH * 9 / 16 + 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Chalk.copy(alpha = 0.22f)),
        )

        // Under the window, saying what is in it.
        Text(
            text = if (ended) "Finished  ·  $nowTitle" else "Credits  ·  $nowTitle",
            color = Muted,
            style = ReelyType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = WINDOW_TOP + WINDOW_WIDTH * 9 / 16 + 10.dp, end = WINDOW_END)
                .widthIn(max = WINDOW_WIDTH),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 27.dp, end = 48.dp)
                .widthIn(max = 560.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "UP NEXT",
                color = Accent,
                style = ReelyType.Label,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
            )
            TitleArt(url = logoUrl, title = item.grandparentTitle ?: item.title)
            upNextLine(item)?.let { Text(text = it, color = Muted, style = ReelyType.Meta) }
            if (item.grandparentTitle != null) {
                Text(
                    text = item.title,
                    color = Chalk,
                    style = ReelyType.Headline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Muted,
                    style = ReelyType.Body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CountdownButton(
                    key = item.ratingKey,
                    seconds = countdownSeconds,
                    onPlay = onPlay,
                    modifier = Modifier.focusRequester(focusRequester),
                )
                TvActionButton(label = if (ended) "Close" else "Watch credits", onClick = onDecline)
            }
        }
    }
}

/** "Season 2 · Episode 6 · 44 min", from whichever of those the item has. */
private fun upNextLine(item: PlexItem): String? = listOfNotNull(
    item.parentIndex?.let { "Season $it" },
    item.index?.let { "Episode $it" },
    item.durationMs.takeIf { it > 0 }?.let { "${(it / 60_000).coerceAtLeast(1)} min" },
).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

/**
 * Play, filling up as the countdown runs, and playing when it is full. Zero seconds
 * means the countdown is switched off and the button just waits to be pressed.
 */
@Composable
private fun CountdownButton(
    key: String,
    seconds: Int,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val play by rememberUpdatedState(onPlay)
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, seconds) {
        if (seconds <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = seconds * 1_000, easing = LinearEasing))
        play()
    }

    var focused by remember { mutableStateOf(false) }
    val colors = pillColors(focused)
    val lift by animateFloatAsState(if (focused) 1.04f else 1f, label = "countdown-lift")
    val left = ceil((1f - progress.value) * seconds).roundToInt()

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(50))
            .background(colors.fill)
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (seconds > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        val width = (constraints.maxWidth * progress.value).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                    }
                    .background(if (focused) Accent.copy(alpha = 0.32f) else Accent.copy(alpha = 0.4f)),
            )
        }
        Text(
            text = if (seconds > 0) "Play next  ·  $left" else "Play next",
            color = colors.text,
            style = ReelyType.Meta,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp),
        )
    }
}
