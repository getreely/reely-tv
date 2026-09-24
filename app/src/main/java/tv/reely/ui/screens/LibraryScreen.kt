package tv.reely.ui.screens

import tv.reely.ui.components.PosterPlaceholder
import tv.reely.ui.components.Shimmer
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.formatDuration
import tv.reely.ui.BrowseState
import tv.reely.ui.HomeState
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibrarySort
import tv.reely.ui.LibraryView
import tv.reely.ui.PlexState
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.ReelyType

private val HERO_HEIGHT = 150.dp

/**
 * A library tab is its own small home — what you are part-way through, what arrived and
 * what is newly out — or the whole library as a grid. The two used to share one scrolling
 * surface, which meant scrolling past the rows every time to reach the library itself.
 */
@Composable
fun LibraryScreen(
    kind: LibraryKind,
    view: LibraryView,
    plex: PlexState,
    home: HomeState,
    focused: PlexItem?,
    imageUrl: (String?, String?, Int, Int) -> String?,
    backdropUrl: (String?, String?) -> String?,
    onFocusItem: (PlexItem?) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    onStartLink: () -> Unit,
    onCancelLink: () -> Unit,
    onDismissPlexError: () -> Unit,
    onCycleSort: () -> Unit,
    onToggleUnwatched: () -> Unit,
    onSelectGenre: (String?) -> Unit,
    onDismissBrowseError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!plex.isConnected) {
        PlexSignInPanel(
            plex = plex,
            onStartLink = onStartLink,
            onCancelLink = onCancelLink,
            onDismissError = onDismissPlexError,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val sections = plex.sectionsFor(kind)
    val browse: BrowseState = plex.browseFor(kind)

    // These rows belong to the library the tab is showing, not to every library of that
    // kind on the server. Picking a library from the tab menu has to change them, or the
    // page would claim a different library's things are in this one.
    val sectionKey = browse.section?.key
    // Section keys repeat across servers, so the server has to match as well or another
    // machine's library number two would pass for this one.
    fun here(serverBase: String?, id: String?): Boolean {
        if (serverBase != null && serverBase != plex.baseUrl) return false
        return sectionKey == null || id == null || id == sectionKey
    }

    val resumable = home.continueWatching.filter {
        val rightKind = if (kind == LibraryKind.MOVIES) it.type == "movie" else it.type == "episode"
        rightKind && here(it.serverBase, it.librarySectionId)
    }
    val recentMovies = home.recentMovies.filter { here(it.serverBase, it.librarySectionId) }
    val recentEpisodes = home.recentEpisodes.filter { here(it.serverBase, it.librarySectionId) }

    // Held at screen level so a row scrolling out of view does not forget its place.
    val resumeFocus = rememberRowFocus()
    val recentFocus = rememberRowFocus()
    val releasedFocus = rememberRowFocus()
    val gridFocus = rememberRowFocus()

    Box(modifier = modifier.fillMaxSize()) {
        HeroBackdrop(
            url = backdropUrl(focused?.serverBase, focused?.art ?: focused?.thumb),
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_HEIGHT)
                    .padding(horizontal = 40.dp, vertical = 8.dp),
            ) {
                if (focused != null) {
                    HeroText(
                        eyebrow = if (focused.type == "episode") focused.grandparentTitle else null,
                        title = focused.title,
                        criticRating = null,
                        audienceRating = null,
                        contentRating = null,
                        facts = listOfNotNull(
                            focused.caption,
                            formatDuration(focused.durationMs).takeIf { it.isNotEmpty() },
                        ),
                        summary = focused.summary,
                        // One line: the grid is what this page is for, and two lines at
                        // body size would push it down.
                        summaryMaxLines = 1,
                        modifier = Modifier.widthIn(max = 700.dp),
                    )
                } else {
                    Text(
                        text = browse.section?.title ?: kind.title,
                        color = Chalk,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (view == LibraryView.HOME && resumable.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Continue Watching") {
                            LazyRow(
                                modifier = Modifier.restoreFocusTo(resumeFocus).focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(resumable, key = { it.listKey }) { item ->
                                    PosterCard(
                                        title = item.rowTitle,
                                        subtitle = episodeLine(item),
                                        imageUrl = imageUrl(item.serverBase, posterArt(item), 300, 450),
                                        progress = item.resumeFraction,
                                        watched = item.isWatched,
                                        onFocus = {
                                            resumeFocus.onFocused(item.listKey)
                                            onFocusItem(item)
                                        },
                                        onClick = { onOpenItem(item) },
                                        modifier = rowItem(resumeFocus, item.listKey),
                                    )
                                }
                            }
                        }
                    }
                }

                if (view == LibraryView.HOME && kind == LibraryKind.MOVIES &&
                    recentMovies.isNotEmpty()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Recently Added") {
                            LazyRow(
                                modifier = Modifier.restoreFocusTo(recentFocus).focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(recentMovies, key = { it.listKey }) { movie ->
                                    PosterCard(
                                        title = movie.title,
                                        subtitle = movie.caption,
                                        imageUrl = imageUrl(movie.serverBase, movie.thumb, 300, 450),
                                        progress = movie.resumeFraction,
                                        watched = movie.isWatched,
                                        onFocus = {
                                            recentFocus.onFocused(movie.listKey)
                                            onFocusItem(movie)
                                        },
                                        onClick = { onOpenItem(movie) },
                                        modifier = rowItem(recentFocus, movie.listKey),
                                    )
                                }
                            }
                        }
                    }
                }

                if (view == LibraryView.HOME && kind == LibraryKind.SHOWS &&
                    recentEpisodes.isNotEmpty()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Recently Added") {
                            LazyRow(
                                modifier = Modifier.restoreFocusTo(recentFocus).focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(
                                    recentEpisodes,
                                    key = { it.listKey },
                                ) { group ->
                                    PosterCard(
                                        title = group.showTitle,
                                        subtitle = if (group.count > 1) "${group.count} new episodes"
                                        else group.newest.caption,
                                        imageUrl = imageUrl(group.serverBase, group.thumb, 300, 450),
                                        badge = group.count,
                                        onFocus = {
                                            recentFocus.onFocused(group.listKey)
                                            onFocusItem(group.newest)
                                        },
                                        onClick = { onOpenItem(group.newest) },
                                        modifier = rowItem(recentFocus, group.listKey),
                                    )
                                }
                            }
                        }
                    }
                }

                if (view == LibraryView.HOME && browse.released.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Recently Released") {
                            LazyRow(
                                modifier = Modifier.restoreFocusTo(releasedFocus).focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(browse.released, key = { it.listKey }) { item ->
                                    PosterCard(
                                        title = item.title,
                                        subtitle = item.caption,
                                        imageUrl = imageUrl(item.serverBase, item.thumb, 300, 450),
                                        progress = item.resumeFraction,
                                        watched = item.isWatched,
                                        onFocus = {
                                            releasedFocus.onFocused(item.listKey)
                                            onFocusItem(item)
                                        },
                                        onClick = { onOpenItem(item) },
                                        modifier = rowItem(releasedFocus, item.listKey),
                                    )
                                }
                            }
                        }
                    }
                }

                if (view == LibraryView.HOME) {
                    if (home.busy && resumable.isEmpty() && browse.released.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyNote("Reading your library…")
                        }
                    }
                    return@LazyVerticalGrid
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = browse.section?.title ?: "All ${kind.title}",
                            color = Chalk,
                            style = ReelyType.RowTitle,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        // Sort, then the watched filter, then genres. One row that runs
                        // off to the right, so a library with forty genres still fits.
                        if (sections.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.restoreFocusTo(recentFocus).focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    TvChip(
                                        label = "Sort · ${browse.sort.label}",
                                        selected = browse.sort != LibrarySort.TITLE,
                                        onClick = onCycleSort,
                                    )
                                }
                                item {
                                    TvChip(
                                        label = "Unwatched",
                                        selected = browse.unwatchedOnly,
                                        onClick = onToggleUnwatched,
                                    )
                                }
                                if (browse.genres.isNotEmpty()) {
                                    item {
                                        TvChip(
                                            label = "All genres",
                                            selected = browse.genreId == null,
                                            onClick = { onSelectGenre(null) },
                                        )
                                    }
                                    items(browse.genres, key = { it.id }) { genre ->
                                        TvChip(
                                            label = genre.title,
                                            selected = browse.genreId == genre.id,
                                            onClick = { onSelectGenre(genre.id) },
                                        )
                                    }
                                }
                            }
                        }
                        if (browse.error != null) {
                            ErrorNote(message = browse.error, onDismiss = onDismissBrowseError)
                        }
                        when {
                            sections.isEmpty() -> EmptyNote(
                                "No ${kind.title.lowercase()} library on ${plex.serverName ?: "this server"}."
                            )

                            browse.items.isEmpty() && browse.isFiltered ->
                                EmptyNote("Nothing in this library matches those filters.")
                        }
                    }
                }

                // The grid's own shape while the first page is on its way.
                if (sections.isNotEmpty() && browse.busy && browse.items.isEmpty()) {
                    items(PLACEHOLDER_COUNT) { Shimmer { PosterPlaceholder() } }
                }

                items(browse.items, key = { it.listKey }) { item ->
                    PosterCard(
                        title = item.title,
                        subtitle = item.caption,
                        imageUrl = imageUrl(item.serverBase, item.thumb, 300, 450),
                        progress = item.resumeFraction,
                        watched = item.isWatched,
                        onFocus = {
                            gridFocus.onFocused(item.listKey)
                            onFocusItem(item)
                        },
                        onClick = { onOpenItem(item) },
                        modifier = rowItem(gridFocus, item.listKey),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = Chalk,
            style = ReelyType.RowTitle,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}

/** Three rows of a six-across grid: a screenful, and no more. */
private const val PLACEHOLDER_COUNT = 18
