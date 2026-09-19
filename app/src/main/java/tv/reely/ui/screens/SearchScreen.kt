package tv.reely.ui.screens

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tv.reely.plex.PlexItem
import tv.reely.ui.SearchState
import tv.reely.ui.components.BlurredBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.TvTextField

@Composable
fun SearchScreen(
    search: SearchState,
    focused: PlexItem?,
    imageUrl: (String?, Int, Int) -> String?,
    blurredUrl: (String?) -> String?,
    onQueryChange: (String) -> Unit,
    onFocusItem: (PlexItem?) -> Unit,
    onOpenDetail: (String) -> Unit,
    onPlay: (PlexItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        BlurredBackdrop(
            url = blurredUrl(focused?.art ?: focused?.thumb),
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
                        search.query.isBlank() -> EmptyNote("Films, shows and episodes, all at once.")
                        search.results.isEmpty() -> EmptyNote("Nothing matched \"${search.query}\".")
                        else -> EmptyNote("${search.results.size} results")
                    }
                }
            }

            items(search.results, key = { it.ratingKey }) { item ->
                PosterCard(
                    title = item.rowTitle,
                    subtitle = episodeLine(item),
                    imageUrl = imageUrl(posterArt(item), 300, 450),
                    progress = item.resumeFraction,
                    watched = item.isWatched,
                    onFocus = { onFocusItem(item) },
                    onClick = {
                        // An episode is a thing to watch; a film or show is a page to open.
                        if (item.type == "episode") onPlay(item)
                        else onOpenDetail(item.ratingKey)
                    },
                )
            }
        }
    }
}
