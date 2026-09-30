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
import androidx.compose.ui.focus.focusRequester
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
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.components.TvTextField
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.ReelyType
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
    onOpenPerson: (tv.reely.plex.PlexPerson) -> Unit = {},
    onClearRecent: () -> Unit = {},
) {
    val channelFocus = rememberRowFocus("channels")
    val peopleFocus = rememberRowFocus("people")
    val resultFocus = rememberRowFocus("results")
    val collectionFocus = rememberRowFocus("collections")
    val recentFocus = rememberRowFocus("recent")

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
                        label = "Search",
                        placeholder = "Movies, shows, people, collections and channels",
                        imeAction = ImeAction.Search,
                        modifier = Modifier.widthIn(max = 620.dp),
                    )
                    when {
                        search.busy -> EmptyNote("Searching…")
                        search.query.isBlank() && search.recent.isNotEmpty() -> RecentSearches(
                            recent = search.recent,
                            focus = recentFocus,
                            onPick = onQueryChange,
                            onClear = onClearRecent,
                        )

                        search.query.isBlank() ->
                            EmptyNote("Search movies, shows, people, collections and live channels.")

                        search.results.isEmpty() && search.channels.isEmpty() && search.people.isEmpty() &&
                            search.collections.isEmpty() ->
                            EmptyNote(
                                if (search.unreachable) "Couldn't reach your Plex server to search. Try again in a moment."
                                else "Nothing matched \"${search.query}\"."
                            )

                        else -> EmptyNote(
                            listOfNotNull(
                                "${search.results.size + search.more.size} in your library".takeIf { search.results.isNotEmpty() },
                                "${search.channels.size} live channels".takeIf { search.channels.isNotEmpty() },
                                (if (search.people.size == 1) "1 person" else "${search.people.size} people")
                                    .takeIf { search.people.isNotEmpty() },
                                (if (search.collections.size == 1) "1 collection" else "${search.collections.size} collections")
                                    .takeIf { search.collections.isNotEmpty() },
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
                            color = Chalk,
                            style = ReelyType.RowTitle,
                        )
                        LazyRow(
                            modifier = Modifier.restoreFocusTo(channelFocus).focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(search.channels, key = { it.streamId }) { channel ->
                                ChannelCard(
                                    name = channel.name,
                                    number = channel.number,
                                    logoUrl = channel.icon,
                                    onClick = { onPlayChannel(channel) },
                                    modifier = rowItem(channelFocus, channel.streamId.toString()),
                                )
                            }
                        }
                    }
                }
            }

            // People after channels: a name typed is a person often enough to be worth a row.
            if (search.people.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "People", color = Chalk, style = ReelyType.RowTitle)
                        LazyRow(
                            modifier = Modifier.restoreFocusTo(peopleFocus).focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(search.people, key = { it.id }) { person ->
                                tv.reely.ui.components.CastCircle(
                                    name = person.name,
                                    role = null,
                                    imageUrl = imageUrl(person.serverBase, person.thumb, 160, 160),
                                    onClick = { onOpenPerson(person) },
                                    modifier = rowItem(peopleFocus, person.id),
                                )
                            }
                        }
                    }
                }
            }

            if (search.results.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Movies and shows",
                        color = Chalk,
                        style = ReelyType.RowTitle,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
            }

            items(search.results, key = { it.listKey }) { item ->
                PosterCard(
                    title = item.rowTitle,
                    subtitle = episodeLine(item),
                    imageUrl = imageUrl(item.serverBase, posterArt(item), 300, 450),
                    progress = item.resumeFraction,
                    watched = item.isWatched,
                    onFocus = {
                        resultFocus.onFocused(item.listKey)
                        onFocusItem(item)
                    },
                    onClick = { onOpenItem(item) },
                    onLongPress = holdFor(item),
                    modifier = rowItem(resultFocus, item.listKey),
                )
            }

            // Collections after the titles themselves: typing "bond" is usually after a film,
            // and the collection of all of them is the next best thing.
            if (search.collections.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "Collections", color = Chalk, style = ReelyType.RowTitle)
                        LazyRow(
                            modifier = Modifier.restoreFocusTo(collectionFocus).focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(search.collections, key = { "collection:" + it.listKey }) { item ->
                                PosterCard(
                                    title = item.title,
                                    subtitle = item.caption,
                                    imageUrl = imageUrl(item.serverBase, item.thumb, 300, 450),
                                    onFocus = {
                                        collectionFocus.onFocused(item.listKey)
                                        onFocusItem(item)
                                    },
                                    onClick = { onOpenItem(item) },
                                    modifier = rowItem(collectionFocus, item.listKey),
                                )
                            }
                        }
                    }
                }
            }

            if (search.more.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Other results",
                        color = Chalk,
                        style = ReelyType.RowTitle,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    )
                }
            }

            items(search.more, key = { "more:" + it.listKey }) { item ->
                PosterCard(
                    title = item.rowTitle,
                    subtitle = episodeLine(item),
                    imageUrl = imageUrl(item.serverBase, posterArt(item), 300, 450),
                    progress = item.resumeFraction,
                    watched = item.isWatched,
                    onFocus = {
                        resultFocus.onFocused(item.listKey)
                        onFocusItem(item)
                    },
                    onClick = { onOpenItem(item) },
                    onLongPress = holdFor(item),
                    modifier = rowItem(resultFocus, item.listKey),
                )
            }
        }
    }
}

/**
 * What was searched for lately, offered again while the box is empty: one press to run
 * one, and one to forget them all.
 */
@Composable
private fun RecentSearches(
    recent: List<String>,
    focus: tv.reely.ui.components.RowFocus,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Recent searches", color = Chalk, style = ReelyType.RowTitle)
        LazyRow(
            modifier = Modifier.restoreFocusTo(focus).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(recent, key = { it }) { words ->
                TvActionButton(
                    label = words,
                    onClick = { onPick(words) },
                    modifier = rowItem(focus, words),
                )
            }
            item(key = "reely:clear") {
                TvActionButton(
                    label = "Clear",
                    onClick = onClear,
                    modifier = rowItem(focus, "reely:clear"),
                )
            }
        }
    }
}
