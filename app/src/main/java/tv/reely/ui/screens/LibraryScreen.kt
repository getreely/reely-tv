package tv.reely.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexSection
import tv.reely.plex.formatDuration
import tv.reely.ui.BrowseState
import tv.reely.ui.HomeState
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibrarySort
import tv.reely.ui.PlexState
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Parchment

private val HERO_HEIGHT = 150.dp

/**
 * A library tab is its own small home: what you are part-way through and what just
 * arrived, with everything else underneath, all on one scrolling surface.
 */
@Composable
fun LibraryScreen(
    kind: LibraryKind,
    plex: PlexState,
    home: HomeState,
    focused: PlexItem?,
    imageUrl: (String?, Int, Int) -> String?,
    backdropUrl: (String?) -> String?,
    onFocusItem: (PlexItem?) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    onStartLink: () -> Unit,
    onCancelLink: () -> Unit,
    onDismissPlexError: () -> Unit,
    onSelectSection: (PlexSection) -> Unit,
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
    val resumable = home.continueWatching.filter {
        if (kind == LibraryKind.MOVIES) it.type == "movie" else it.type == "episode"
    }

    Box(modifier = modifier.fillMaxSize()) {
        HeroBackdrop(
            url = backdropUrl(focused?.art ?: focused?.thumb),
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
                        modifier = Modifier.widthIn(max = 700.dp),
                    )
                } else {
                    Text(
                        text = browse.section?.title ?: kind.title,
                        color = Parchment,
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
                if (resumable.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Continue Watching") {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(resumable, key = { it.ratingKey }) { item ->
                                    PosterCard(
                                        title = item.rowTitle,
                                        subtitle = episodeLine(item),
                                        imageUrl = imageUrl(posterArt(item), 300, 450),
                                        progress = item.resumeFraction,
                                        watched = item.isWatched,
                                        onFocus = { onFocusItem(item) },
                                        onClick = { onOpenItem(item) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (kind == LibraryKind.MOVIES && home.recentMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Recently Added") {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(home.recentMovies, key = { it.ratingKey }) { movie ->
                                    PosterCard(
                                        title = movie.title,
                                        subtitle = movie.caption,
                                        imageUrl = imageUrl(movie.thumb, 300, 450),
                                        progress = movie.resumeFraction,
                                        watched = movie.isWatched,
                                        onFocus = { onFocusItem(movie) },
                                        onClick = { onOpenItem(movie) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (kind == LibraryKind.SHOWS && home.recentEpisodes.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RowBlock("Recently Added") {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(
                                    home.recentEpisodes,
                                    key = { it.showRatingKey ?: it.showTitle },
                                ) { group ->
                                    PosterCard(
                                        title = group.showTitle,
                                        subtitle = if (group.count > 1) "${group.count} new episodes"
                                        else group.newest.caption,
                                        imageUrl = imageUrl(group.thumb, 300, 450),
                                        badge = group.count,
                                        onFocus = { onFocusItem(group.newest) },
                                        onClick = { onOpenItem(group.newest) },
                                    )
                                }
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = browse.section?.title ?: "All ${kind.title}",
                            color = Parchment,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        if (sections.size > 1) {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(sections, key = { it.key }) { section ->
                                    TvChip(
                                        label = section.title,
                                        selected = browse.section?.key == section.key,
                                        onClick = { onSelectSection(section) },
                                    )
                                }
                            }
                        }
                        // Sort, then the watched filter, then genres. One row that runs
                        // off to the right, so a library with forty genres still fits.
                        if (sections.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
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

                            browse.busy && browse.items.isEmpty() ->
                                EmptyNote("Loading ${kind.title.lowercase()}…")

                            browse.items.isEmpty() && browse.isFiltered ->
                                EmptyNote("Nothing in this library matches those filters.")
                        }
                    }
                }

                items(browse.items, key = { it.ratingKey }) { item ->
                    PosterCard(
                        title = item.title,
                        subtitle = item.caption,
                        imageUrl = imageUrl(item.thumb, 300, 450),
                        progress = item.resumeFraction,
                        watched = item.isWatched,
                        onFocus = { onFocusItem(item) },
                        onClick = { onOpenItem(item) },
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
            color = Parchment,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}
