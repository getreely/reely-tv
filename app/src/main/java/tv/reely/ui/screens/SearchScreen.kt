package tv.reely.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.ui.SearchState
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ChannelCard
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.TvTextField
import tv.reely.ui.theme.Parchment
import tv.reely.xtream.XtreamChannel

@Composable
fun SearchScreen(
    search: SearchState,
    focused: PlexItem?,
    imageUrl: (String?, String?, Int, Int) -> String?,
    backdropUrl: (String?, String?) -> String?,
    onQueryChange: (String) -> Unit,
    onFocusItem: (PlexItem?) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    onPlayChannel: (XtreamChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        HeroBackdrop(
            url = backdropUrl(focused?.serverBase, focused?.art ?: focused?.thumb),
            modifier = Modifier.fillMaxSize(),
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 20.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TvTextField(
                        value = search.query,
                        onValueChange = onQueryChange,
                        label = "Search your library",
                        placeholder = "Title, show or episode",
                        imeAction = ImeAction.Search,
                        modifier = Modifier.widthIn(max = 620.dp),
                    )
                    when {
                        search.busy -> EmptyNote("Searching…")
                        search.query.isBlank() ->
                            EmptyNote("Films, shows, episodes and live channels, all at once.")

                        search.results.isEmpty() && search.channels.isEmpty() ->
                            EmptyNote("Nothing matched \"${search.query}\".")

                        else -> EmptyNote(
                            listOfNotNull(
                                "${search.results.size} in your library".takeIf { search.results.isNotEmpty() },
                                "${search.channels.size} live channels".takeIf { search.channels.isNotEmpty() },
                            ).joinToString("  ·  ")
                        )
                    }
                }
            }

            // Channels come first: a match on a channel name is almost always the thing
            // that was being looked for, and there are never many of them.
            if (search.channels.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Live TV",
                            color = Parchment,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LazyRow(
                            modifier = Modifier.focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(search.channels, key = { it.streamId }) { channel ->
                                ChannelCard(
                                    name = channel.name,
                                    number = channel.number,
                                    logoUrl = channel.icon,
                                    onClick = { onPlayChannel(channel) },
                                )
                            }
                        }
                    }
                }
            }

            items(search.results, key = { it.ratingKey }) { item ->
                PosterCard(
                    title = item.rowTitle,
                    subtitle = episodeLine(item),
                    imageUrl = imageUrl(item.serverBase, posterArt(item), 300, 450),
                    progress = item.resumeFraction,
                    watched = item.isWatched,
                    onFocus = { onFocusItem(item) },
                    onClick = { onOpenItem(item) },
                )
            }
        }
    }
}
