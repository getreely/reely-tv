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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import tv.reely.ui.EpisodeGroup
import tv.reely.ui.HomeState
import tv.reely.ui.PlexState
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.theme.Parchment

private val HERO_HEIGHT = 168.dp

@Composable
fun HomeScreen(
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

    // One per row, held at screen level so scrolling a row out of view does not lose
    // where the cursor was in it.
    val resumeFocus = rememberRowFocus()
    val episodeFocus = rememberRowFocus()
    val movieFocus = rememberRowFocus()

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
                    .padding(horizontal = 40.dp, vertical = 10.dp),
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
                        text = "Home",
                        color = Parchment,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (home.error != null) {
                    item { ErrorNote(home.error, modifier = Modifier.padding(horizontal = 40.dp)) }
                }

                if (home.continueWatching.isNotEmpty()) {
                    item {
                        PosterRow(title = "Continue Watching", rowFocus = resumeFocus) {
                            items(home.continueWatching, key = { it.listKey }) { item ->
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
                                    modifier = Modifier
                                        .focusRequester(resumeFocus.requesterFor(item.listKey)),
                                )
                            }
                        }
                    }
                }

                if (home.recentEpisodes.isNotEmpty()) {
                    item {
                        PosterRow(title = "Recently Added Episodes", rowFocus = episodeFocus) {
                            items(home.recentEpisodes, key = { it.listKey }) { group ->
                                EpisodeGroupCard(group, imageUrl, episodeFocus, onFocusItem, onOpenItem)
                            }
                        }
                    }
                }

                if (home.recentMovies.isNotEmpty()) {
                    item {
                        PosterRow(title = "Recently Added Movies", rowFocus = movieFocus) {
                            items(home.recentMovies, key = { it.listKey }) { movie ->
                                PosterCard(
                                    title = movie.title,
                                    subtitle = movie.caption,
                                    imageUrl = imageUrl(movie.serverBase, movie.thumb, 300, 450),
                                    progress = movie.resumeFraction,
                                    watched = movie.isWatched,
                                    onFocus = {
                                        movieFocus.onFocused(movie.listKey)
                                        onFocusItem(movie)
                                    },
                                    onClick = { onOpenItem(movie) },
                                    modifier = Modifier
                                        .focusRequester(movieFocus.requesterFor(movie.listKey)),
                                )
                            }
                        }
                    }
                }

                if (home.isEmpty) {
                    item {
                        EmptyNote(
                            if (home.busy) "Reading your library…"
                            else "Nothing to show yet. Watch something and it will appear here.",
                            modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** An episode's own still is nearly always better than its show's poster in a row. */
internal fun posterArt(item: PlexItem): String? =
    if (item.type == "episode") item.grandparentThumb ?: item.thumb else item.thumb

internal fun episodeLine(item: PlexItem): String? = when (item.type) {
    "episode" -> listOfNotNull(item.caption, item.title).joinToString(" · ")
    else -> item.caption
}

@Composable
private fun EpisodeGroupCard(
    group: EpisodeGroup,
    imageUrl: (String?, String?, Int, Int) -> String?,
    rowFocus: tv.reely.ui.components.RowFocus,
    onFocusItem: (PlexItem?) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
) {
    val newest = group.newest
    PosterCard(
        title = group.showTitle,
        subtitle = if (group.count > 1) "${group.count} new episodes" else newest.caption,
        imageUrl = imageUrl(group.serverBase, group.thumb, 300, 450),
        badge = group.count,
        onFocus = {
            rowFocus.onFocused(group.listKey)
            onFocusItem(newest)
        },
        // Whether one episode arrived or twelve, this opens the show at the newest one.
        onClick = { onOpenItem(newest) },
        modifier = Modifier.focusRequester(rowFocus.requesterFor(group.listKey)),
    )
}

@Composable
private fun PosterRow(
    title: String,
    rowFocus: tv.reely.ui.components.RowFocus,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = Parchment,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        LazyRow(
            modifier = Modifier.restoreFocusTo(rowFocus).focusGroup(),
            contentPadding = PaddingValues(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
