package tv.reely.ui.screens

import tv.reely.core.renderersFor
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.components.sheet
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import tv.reely.ui.components.PlusGlyph
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Chalk

/**
 * Where a tile sits when several channels share the screen.
 *
 * One channel is the ordinary player and is drawn full screen by the caller. Beyond that
 * the split is chosen to suit the count rather than always quartering the screen: two go
 * side by side, three give the first tile the left half and stack the others beside it,
 * and only four actually make a 2x2.
 */
enum class TileSlot { FULL, LEFT, RIGHT, TOP_RIGHT, BOTTOM_RIGHT, TOP_LEFT, BOTTOM_LEFT }

/** Which neighbour a direction leads to, or null when the grid has no tile that way. */
fun tileNeighbour(slots: Int, from: Int, dx: Int, dy: Int): Int? {
    val target = when (slots) {
        2 -> when {
            dx < 0 && from == 1 -> 0
            dx > 0 && from == 0 -> 1
            else -> null
        }
        // Tile 0 owns the left half; 1 and 2 are stacked on the right.
        3 -> when {
            dx > 0 && from == 0 -> 1
            dx < 0 && from != 0 -> 0
            dy > 0 && from == 1 -> 2
            dy < 0 && from == 2 -> 1
            else -> null
        }
        // 0 1
        // 2 3
        4 -> when {
            dx > 0 -> if (from == 0) 1 else if (from == 2) 3 else null
            dx < 0 -> if (from == 1) 0 else if (from == 3) 2 else null
            dy > 0 -> if (from == 0) 2 else if (from == 1) 3 else null
            dy < 0 -> if (from == 2) 0 else if (from == 3) 1 else null
            else -> null
        }

        else -> null
    }
    return target?.takeIf { it in 0 until slots }
}

/**
 * Whether the layout has room to offer a spare cell for another channel.
 *
 * In the focus layout the spare costs nothing but a slice of the side column, so it is
 * offered from the second channel on. The grid would have to shrink a running stream from
 * half the screen to a quarter to show one, so there it waits for the third. Four is the
 * ceiling either way, and the held-OK menu offers the same thing without taking any room.
 */
fun hasSpareCell(tileCount: Int, focusLayout: Boolean): Boolean =
    if (focusLayout) tileCount in 2..3 else tileCount == 3

/**
 * Lays the tiles out for the count in hand and hands each one a slot to draw into. The
 * caller supplies the content so the first tile can keep using the player that is already
 * running rather than starting a second one for the same channel.
 */
@Composable
fun MultiViewGrid(
    slots: Int,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int) -> Unit,
) {
    TileLayout(slots = slots, modifier = modifier, tile = tile) { width, height, gap ->
        gridRects(slots, width, height, gap)
    }
}

/**
 * Every tile's place in the even grid, in pixels. Two go side by side, three give the
 * first the left half and stack the others, and four quarter the screen.
 */
fun gridRects(slots: Int, width: Int, height: Int, gap: Int): List<IntRect> {
    val halfW = (width - gap) / 2
    val halfH = (height - gap) / 2
    val right = width - halfW
    val lower = height - halfH
    return when (slots) {
        2 -> listOf(
            IntRect(0, 0, halfW, height),
            IntRect(right, 0, width, height),
        )
        3 -> listOf(
            IntRect(0, 0, halfW, height),
            IntRect(right, 0, width, halfH),
            IntRect(right, lower, width, height),
        )
        4 -> listOf(
            IntRect(0, 0, halfW, halfH),
            IntRect(right, 0, width, halfH),
            IntRect(0, lower, halfW, height),
            IntRect(right, lower, width, height),
        )
        else -> listOf(IntRect(0, 0, width, height))
    }.take(slots.coerceAtLeast(1))
}

/**
 * Draws each tile once and only ever moves or resizes it.
 *
 * The layouts used to be rows and columns with each tile composed wherever it fell, so
 * moving the cursor in the focus layout took a tile out of the side column and composed
 * it afresh in the middle. That built its picture a new video surface every time, which
 * showed green until the stream's next full frame arrived, and with four channels up it
 * was four new surfaces a press. Here the tiles stay put in the composition, keyed by
 * their place in the line, and only where they are drawn changes.
 */
@Composable
private fun TileLayout(
    slots: Int,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int) -> Unit,
    rects: (width: Int, height: Int, gap: Int) -> List<IntRect>,
) {
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            repeat(slots) { index ->
                key(index) { Box { tile(index) } }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placed = rects(width, height, TILE_GAP.roundToPx())
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = placed.getOrNull(index) ?: IntRect.Zero
            measurable.measure(Constraints.fixed(rect.width.coerceAtLeast(0), rect.height.coerceAtLeast(0))) to rect
        }
        layout(width, height) {
            placeables.forEach { (placeable, rect) -> placeable.place(rect.left, rect.top) }
        }
    }
}

/** The line between tiles. */
private val TILE_GAP = 3.dp

/**
 * A player for one of the extra channels.
 *
 * Built here but owned by the screen, because a tile moves: going full screen on one and
 * coming back puts it in a different place in the composition, and a player created by
 * the tile itself would be torn down and reconnected each time. The provider is not
 * handing out connections freely enough for that.
 */
fun buildExtraPlayer(context: Context, url: String): ExoPlayer =
    ExoPlayer.Builder(context, renderersFor(context))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 30_000, 1_000, 2_000)
                .build()
        )
        .build()
        .apply {
            // No audio focus handling. The main player already holds it, and a second
            // claimant would spend its time taking it back off the first.
            volume = 0f
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }

/**
 * One of the extra channels. Only the tile with the cursor on it is audible, which is the
 * whole point of moving the cursor around.
 */
@Composable
fun ExtraTile(
    player: ExoPlayer,
    name: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(player, focused) { player.volume = if (focused) 1f else 0f }

    TileFrame(
        name = name,
        focused = focused,
        modifier = modifier,
        aspectRatio = rememberVideoAspect(player),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { if (it.player !== player) it.player = player },
            // Otherwise the player keeps a listener on a view nobody can see any more.
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The border and name plate every tile wears once there is more than one of them.
 *
 * The outline follows the picture rather than the cell. A player letterboxes to keep the
 * source's shape, so in a two-way split — where each cell is half the width but the full
 * height — the picture fills a band across the middle and a ring drawn on the cell stood
 * a long way off it on all four sides. A quartered screen only looked right because those
 * cells happen to be about sixteen by nine already.
 */
@Composable
fun TileFrame(
    name: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Whichever edge runs out first is the one the picture is bounded by.
            val ratio = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 16f / 9f
            val byWidth = maxWidth / ratio <= maxHeight
            val pictureWidth = if (byWidth) maxWidth else maxHeight * ratio
            val pictureHeight = if (byWidth) maxWidth / ratio else maxHeight
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(pictureWidth)
                    .height(pictureHeight)
                    // Only the tile you are hearing is outlined. A ring on every one of
                    // them said nothing, and the whole point of moving the cursor is to
                    // see which is live.
                    .border(
                        width = if (focused) 3.dp else 0.dp,
                        color = if (focused) Chalk else Color.Transparent,
                    ),
            ) {
                Text(
                    text = name,
                    color = if (focused) Chalk else Chalk.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Ink.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** The shape of what a player is actually showing, so a tile can be drawn around it. */
@Composable
fun rememberVideoAspect(player: Player?): Float {
    var ratio by remember(player) { mutableStateOf(aspectOf(player?.videoSize)) }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                ratio = aspectOf(videoSize)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return ratio
}

/** Sixteen by nine until a stream says otherwise, which it cannot before its first frame. */
private fun aspectOf(size: VideoSize?): Float {
    if (size == null || size.width <= 0 || size.height <= 0) return 16f / 9f
    return size.width * size.pixelWidthHeightRatio / size.height
}

/**
 * What holding OK on a tile offers. Going straight to the guide made replacing the only
 * thing a held press could do, so there was no way to make one tile full screen or to
 * drop one without walking back to it and pressing back.
 */
@Composable
fun TileMenu(
    name: String,
    focusRequester: FocusRequester,
    canClose: Boolean,
    /** False once four channels are up, which is all the screen holds. */
    canAdd: Boolean,
    /** False with one channel up, where the tile already fills the screen. */
    canMaximize: Boolean,
    onMaximize: () -> Unit,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 300.dp)
            .sheet()
            .padding(24.dp)
            // Without this the requester has nothing focusable of its own to hand focus
            // to, and every button here would be dead.
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = name,
            color = Chalk,
            style = ReelyType.RowTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (canMaximize) {
            TvActionButton(label = "Full screen", onClick = onMaximize, emphasised = true)
        }
        /*
         * The only way to a third channel that does not have to be guessed at.
         *
         * The spare cell covers one case — a grid with exactly three channels up — and
         * the transport's own button disappears the moment there is more than one. That
         * left holding OK on a channel in the guide, which nobody would think to try, so
         * the side-by-side layout stopped at two channels and looked like its limit.
         */
        if (canAdd) {
            TvActionButton(
                label = "Add another channel",
                onClick = onAdd,
                // The obvious thing to want from a menu opened on the only channel up.
                emphasised = !canMaximize,
            )
        }
        TvActionButton(label = "Replace channel", onClick = onReplace)
        // The main tile is the player itself; closing it would be closing the screen.
        if (canClose) TvActionButton(label = "Close channel", onClick = onClose)
        TvActionButton(label = "Cancel", onClick = onCancel)
    }
}

/**
 * The spare cell in a grid that is not full: somewhere obvious to put another channel.
 * Adding one otherwise meant knowing that a channel in the guide can be held down, which
 * is not a thing anybody would guess.
 */
@Composable
fun AddTile(focused: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.6f))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Chalk else Chalk.copy(alpha = 0.22f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlusGlyph(
                color = if (focused) Chalk else Chalk.copy(alpha = 0.5f),
                size = 34.dp,
            )
            Text(
                text = "Add a channel",
                color = if (focused) Chalk else Chalk.copy(alpha = 0.5f),
                style = ReelyType.Label,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The other way to share a screen: whichever channel you are listening to takes most of
 * it, and the rest are kept small beside it. Better than an even grid when one game
 * matters and the others are being kept an eye on.
 *
 * Every channel keeps its place, left to right. Moving the cursor makes the one it lands
 * on large where it is: the channels before it close up into a column on its left, the
 * ones after it into a column on its right. It used to always put the large one on the
 * left, so moving right to the second channel picked it up and carried it across the
 * screen, and the first one jumped over to where it had been.
 */
@Composable
fun FocusLayout(
    slots: Int,
    focused: Int,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int) -> Unit,
) {
    TileLayout(slots = slots, modifier = modifier, tile = tile) { width, height, gap ->
        focusRects(slots, focused, width, height, gap)
    }
}

/**
 * Every tile's place in the focus layout, in pixels: the large one full height in its own
 * place in the line, the ones before and after it in picture-shaped boxes stacked down
 * the middle of a column either side.
 */
fun focusRects(slots: Int, focused: Int, width: Int, height: Int, gap: Int): List<IntRect> {
    if (slots <= 1) return listOf(IntRect(0, 0, width, height))
    val (before, after) = focusSides(slots, focused)
    val columns = listOf(before, after).count { it.isNotEmpty() }
    val side = if (columns == 2) SIDE_BOTH else SIDE_ONE
    val room = width - gap * columns
    val sideW = (room * side).toInt()
    val largeW = room - sideW * columns
    val largeLeft = if (before.isNotEmpty()) sideW + gap else 0
    val rects = arrayOfNulls<IntRect>(slots)
    rects[focused.coerceIn(0, slots - 1)] = IntRect(largeLeft, 0, largeLeft + largeW, height)
    fun column(indices: List<Int>, left: Int) {
        val tileH = sideW * 9 / 16
        val total = tileH * indices.size + gap * (indices.size - 1)
        var top = (height - total) / 2
        indices.forEach { index ->
            rects[index] = IntRect(left, top, left + sideW, top + tileH)
            top += tileH + gap
        }
    }
    column(before, 0)
    column(after, largeLeft + largeW + gap)
    return rects.map { it ?: IntRect.Zero }
}

/** The tiles either side of the large one in the focus layout, in their own order. */
fun focusSides(slots: Int, focused: Int): Pair<List<Int>, List<Int>> {
    val large = focused.coerceIn(0, (slots - 1).coerceAtLeast(0))
    return (0 until large).toList() to (large + 1 until slots).toList()
}

/** The share of the width a side column takes: less of it when there's one each side. */
private const val SIDE_ONE = 0.3f
private const val SIDE_BOTH = 0.2f
