package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.reely.plex.PlexItem
import tv.reely.ui.PersonState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.PosterPlaceholder
import tv.reely.ui.components.ROWS_BOTTOM
import tv.reely.ui.components.Shimmer
import tv.reely.ui.components.rememberMarginScroll
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.components.rowItem
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceHigh

/**
 * Someone from a title's cast, and what else of theirs is on the server: films and shows
 * together, newest first. Reached with OK on them in the cast row.
 */
@Composable
fun PersonScreen(
    state: PersonState,
    imageUrl: (String?, String?, Int, Int) -> String?,
    onOpenItem: (PlexItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val person = state.route
    val gridFocus = rememberRowFocus("grid")
    val gridScroll = rememberMarginScroll(above = 14.dp)

    CompositionLocalProvider(LocalBringIntoViewSpec provides gridScroll) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = modifier.fillMaxSize().restoreFocusTo(gridFocus),
            contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 18.dp, bottom = ROWS_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(112.dp).clip(CircleShape).background(SurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        val photo = imageUrl(person.serverBase, person.thumb, 224, 224)
                        if (photo != null) {
                            AsyncImage(
                                model = photo,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(text = person.name.take(1).uppercase(), color = Faint, fontSize = 40.sp, lineHeight = 44.sp)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = person.name, color = Chalk, style = ReelyType.Display)
                        Text(
                            text = when {
                                state.busy -> "Looking through your libraries…"
                                else -> countLine(state.items)
                            },
                            color = Muted,
                            style = ReelyType.Body,
                        )
                    }
                }
            }

            if (state.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorNote(state.error) }
            } else if (!state.busy && state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyNote("Nothing else with ${person.name} is on this server.")
                }
            }

            if (state.busy) {
                items(12) { Shimmer { PosterPlaceholder() } }
            }

            items(state.items, key = { it.listKey }) { item ->
                PosterCard(
                    title = item.title,
                    subtitle = item.year?.toString() ?: item.caption,
                    imageUrl = imageUrl(item.serverBase, item.thumb, 300, 450),
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

/** "4 films and 2 shows on your server". */
internal fun countLine(items: List<PlexItem>): String {
    val films = items.count { it.type == "movie" }
    val shows = items.count { it.type == "show" }
    val parts = listOfNotNull(
        films.takeIf { it > 0 }?.let { if (it == 1) "1 film" else "$it films" },
        shows.takeIf { it > 0 }?.let { if (it == 1) "1 show" else "$it shows" },
    )
    return if (parts.isEmpty()) "Nothing on your server" else parts.joinToString(" and ") + " on your server"
}
