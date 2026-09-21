package tv.reely.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import tv.reely.ui.components.PlusGlyph
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Parchment

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
    when (slots) {
        2 -> Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(0) }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(1) }
        }

        3 -> Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(0) }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { tile(1) }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { tile(2) }
            }
        }

        4 -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(0) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(1) }
            }
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(2) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(3) }
            }
        }

        else -> Box(modifier = modifier.fillMaxSize()) { tile(0) }
    }
}

/**
 * A player for one of the extra channels.
 *
 * Built here but owned by the screen, because a tile moves: going full screen on one and
 * coming back puts it in a different place in the composition, and a player created by
 * the tile itself would be torn down and reconnected each time. The provider is not
 * handing out connections freely enough for that.
 */
fun buildExtraPlayer(context: Context, url: String): ExoPlayer =
    ExoPlayer.Builder(context)
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

    TileFrame(name = name, focused = focused, modifier = modifier) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The border and name plate every tile wears once there is more than one of them. */
@Composable
fun TileFrame(
    name: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Only the tile you are hearing is outlined. A ring on every one of them said
            // nothing, and the whole point of moving the cursor is to see which is live.
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Parchment else Color.Transparent,
            ),
    ) {
        content()
        Text(
            text = name,
            color = if (focused) Parchment else Parchment.copy(alpha = 0.7f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
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
                color = if (focused) Parchment else Parchment.copy(alpha = 0.22f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlusGlyph(
                color = if (focused) Parchment else Parchment.copy(alpha = 0.5f),
                size = 34.dp,
            )
            Text(
                text = "Add a channel",
                color = if (focused) Parchment else Parchment.copy(alpha = 0.5f),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The other way to share a screen: whichever channel you are listening to takes most of
 * it, and the rest line up beside it. Better than an even grid when one game matters and
 * the others are being kept an eye on.
 *
 * Moving the cursor moves which one is large, so the layout reorders as you go. That is
 * the point of it rather than a side effect.
 */
@Composable
fun FocusLayout(
    slots: Int,
    focused: Int,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(modifier = Modifier.weight(0.7f).fillMaxHeight()) { tile(focused) }
        Column(
            modifier = Modifier.weight(0.3f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            (0 until slots).filter { it != focused }.forEach { index ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { tile(index) }
            }
        }
    }
}
