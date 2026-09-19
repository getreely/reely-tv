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
import tv.reely.ui.PlexState
import tv.reely.ui.components.BlurredBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Parchment

private val HERO_HEIGHT = 230.dp

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
    blurredUrl: (String?) -> String?,
    onFocusItem: (PlexItem?) -> Unit,
    onPlay: (PlexItem) -> Unit,
    onOpenDetail: (String) -> Unit,
    onStartLink: () -> Unit,
    onCancelLink: () -> Unit,
    onDismissPlexError: () -> Unit,
    onSelectSection: (PlexSection) -> Unit,
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
        BlurredBackdrop(
            url = blurredUrl(focused?.art ?: focused?.thumb),
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_HEIGHT)
                    .padding(horizontal = 40.dp, vertical = 14.dp),
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
                        fontSize = 34.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 158.dp),
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
                                        onClick = { onPlay(item) },
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
                                        onClick = { onOpenDetail(movie.ratingKey) },
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
                                        onClick = {
                                            val showKey = group.showRatingKey
                                            if (group.count == 1 || showKey == null) onPlay(group.newest)
                                            else onOpenDetail(showKey)
                                        },
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
                            fontSize = 19.sp,
                            lineHeight = 24.sp,
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
                        if (browse.error != null) {
                            ErrorNote(message = browse.error, onDismiss = onDismissBrowseError)
                        }
                        if (sections.isEmpty()) {
                            EmptyNote(
                                "No ${kind.title.lowercase()} library on ${plex.serverName ?: "this server"}."
                            )
                        } else if (browse.busy && browse.items.isEmpty()) {
                            EmptyNote("Loading ${kind.title.lowercase()}…")
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
                        onClick = { onOpenDetail(item.ratingKey) },
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
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}
