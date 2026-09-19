package tv.reely.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import tv.reely.ui.BrowseState
import tv.reely.ui.HomeState
import tv.reely.ui.LibraryKind
import tv.reely.ui.PlexState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.TvChip
import tv.reely.ui.components.WideCard
import tv.reely.ui.theme.Parchment

/**
 * A library tab is its own small home: what you are part-way through and what just arrived,
 * with everything else underneath. One scrolling surface, so the remote never gets stuck
 * between two scroll containers.
 */
@Composable
fun LibraryScreen(
    kind: LibraryKind,
    plex: PlexState,
    home: HomeState,
    imageUrl: (String?, Int, Int) -> String?,
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

    // Continue Watching on a library tab only shows that library's kind of thing.
    val resumable = home.continueWatching.filter {
        if (kind == LibraryKind.MOVIES) it.type == "movie" else it.type == "episode"
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 158.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 12.dp, bottom = 40.dp),
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
                            WideCard(
                                title = if (item.type == "episode") item.grandparentTitle ?: item.title
                                else item.title,
                                subtitle = if (item.type == "episode")
                                    listOfNotNull(item.caption, item.title).joinToString("  ·  ")
                                else item.caption,
                                imageUrl = imageUrl(item.art ?: item.thumb, 480, 270),
                                progress = item.resumeFraction,
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
                        items(home.recentEpisodes, key = { it.showRatingKey ?: it.showTitle }) { group ->
                            PosterCard(
                                title = group.showTitle,
                                subtitle = if (group.count > 1) "${group.count} new episodes"
                                else group.newest.caption,
                                imageUrl = imageUrl(group.thumb, 300, 450),
                                badge = group.count,
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
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
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
                onClick = { onOpenDetail(item.ratingKey) },
            )
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
