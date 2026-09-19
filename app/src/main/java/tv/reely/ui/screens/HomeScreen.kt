package tv.reely.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.formatDuration
import tv.reely.ui.EpisodeGroup
import tv.reely.ui.HomeState
import tv.reely.ui.PlexState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.WideCard
import tv.reely.ui.theme.Parchment

@Composable
fun HomeScreen(
    plex: PlexState,
    home: HomeState,
    imageUrl: (String?, Int, Int) -> String?,
    onPlay: (PlexItem) -> Unit,
    onOpenDetail: (String) -> Unit,
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (home.error != null) {
            item {
                ErrorNote(
                    message = home.error,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        }

        if (home.continueWatching.isNotEmpty()) {
            item {
                Row(title = "Continue Watching") {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(home.continueWatching, key = { it.ratingKey }) { item ->
                            WideCard(
                                title = if (item.type == "episode") item.grandparentTitle ?: item.title
                                else item.title,
                                subtitle = when (item.type) {
                                    "episode" -> listOfNotNull(item.caption, item.title)
                                        .joinToString("  ·  ")

                                    else -> item.caption
                                },
                                imageUrl = imageUrl(item.art ?: item.thumb, 480, 270),
                                progress = item.resumeFraction,
                                onClick = { onPlay(item) },
                            )
                        }
                    }
                }
            }
        }

        if (home.recentEpisodes.isNotEmpty()) {
            item {
                Row(title = "Recently Added Episodes") {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = home.recentEpisodes,
                            key = { index, group -> group.showRatingKey ?: index.toString() },
                        ) { _, group ->
                            EpisodeGroupCard(group, imageUrl, onOpenDetail, onPlay)
                        }
                    }
                }
            }
        }

        if (home.recentMovies.isNotEmpty()) {
            item {
                Row(title = "Recently Added Movies") {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = 36.dp),
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

        if (home.isEmpty) {
            item {
                EmptyNote(
                    if (home.busy) "Reading your library…"
                    else "Nothing to show yet. Watch something and it will appear here.",
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun EpisodeGroupCard(
    group: EpisodeGroup,
    imageUrl: (String?, Int, Int) -> String?,
    onOpenDetail: (String) -> Unit,
    onPlay: (PlexItem) -> Unit,
) {
    val newest = group.newest
    PosterCard(
        title = group.showTitle,
        subtitle = if (group.count > 1) "${group.count} new episodes" else newest.caption,
        imageUrl = imageUrl(group.thumb, 300, 450),
        badge = group.count,
        onClick = {
            // One new episode is a thing to watch; several is a thing to choose from.
            val showKey = group.showRatingKey
            if (group.count == 1 || showKey == null) onPlay(newest) else onOpenDetail(showKey)
        },
    )
}

@Composable
private fun Row(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = Parchment,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        content()
    }
}

/** Kept next to the rows because runtime formatting is the only thing it is used for. */
internal fun episodeDuration(item: PlexItem): String? =
    formatDuration(item.durationMs).takeIf { it.isNotEmpty() }
