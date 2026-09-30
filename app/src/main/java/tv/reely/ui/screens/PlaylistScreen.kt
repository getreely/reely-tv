package tv.reely.ui.screens

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.xtream.sourceTag
import tv.reely.plex.formatDuration
import tv.reely.ui.PlaylistState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.PosterPlaceholder
import tv.reely.ui.components.ROWS_BOTTOM
import tv.reely.ui.components.Shimmer
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.rememberMarginScroll
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/**
 * A playlist: Play from the top, Shuffle, or pick one thing in it. Each goes on to the
 * next when it ends, with the usual Up Next between them.
 */
@Composable
fun PlaylistScreen(
    state: PlaylistState,
    imageUrl: (String?, String?, Int, Int) -> String?,
    onPlay: (shuffle: Boolean) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridFocus = rememberRowFocus("grid")
    val gridScroll = rememberMarginScroll(above = 14.dp)

    CompositionLocalProvider(LocalBringIntoViewSpec provides gridScroll) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 18.dp, bottom = ROWS_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = state.route.title, color = Chalk, style = ReelyType.Display)
                    Text(
                        text = if (state.busy) "Loading…" else playlistLine(state.items),
                        color = Muted,
                        style = ReelyType.Body,
                    )
                    if (state.items.any { it.isPlayable }) {
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TvActionButton(label = "Play", onClick = { onPlay(false) }, emphasised = true)
                            TvActionButton(label = "Shuffle", onClick = { onPlay(true) })
                        }
                    }
                }
            }

            if (state.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorNote(state.error) }
            } else if (!state.busy && state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote("This playlist is empty.") }
            }

            if (state.busy) {
                items(12) { Shimmer { PosterPlaceholder() } }
            }

            items(state.items, key = { it.listKey }) { item ->
                PosterCard(
                    title = item.rowTitle,
                    subtitle = episodeLine(item) ?: item.caption,
                    imageUrl = imageUrl(item.serverBase, posterArt(item), 300, 450),
                    tag = item.sourceTag,
                    progress = item.resumeFraction,
                    watched = item.isWatched,
                    onFocus = { gridFocus.onFocused(item.listKey) },
                    onClick = { onOpenItem(item) },
                    onLongPress = holdFor(item),
                    modifier = rowItem(gridFocus, item.listKey),
                )
            }
        }
    }
}

/** "12 items · 5h 20m". */
internal fun playlistLine(items: List<PlexItem>): String {
    val count = if (items.size == 1) "1 item" else "${items.size} items"
    val length = formatDuration(items.sumOf { it.durationMs })
    return listOf(count, length).filter { it.isNotEmpty() }.joinToString("  ·  ")
}
