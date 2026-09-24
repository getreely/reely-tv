package tv.reely.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.formatDuration
import tv.reely.ui.DetailState
import tv.reely.core.minimumScrollDistance
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.CastCircle
import tv.reely.ui.components.CheckGlyph
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.IconAction
import tv.reely.ui.components.InfoGlyph
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.RestartGlyph
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TrailerGlyph
import tv.reely.ui.components.TvChip
import tv.reely.ui.components.EpisodeTile
import tv.reely.ui.theme.Muted
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos

@Composable
fun DetailScreen(
    state: DetailState,
    imageUrl: (String?, String?, Int, Int) -> String?,
    backdropUrl: (String?, String?) -> String?,
    onPlay: (PlexItem) -> Unit,
    onPlayFromStart: (PlexItem) -> Unit,
    onPlayDetail: () -> Unit,
    onPlayDetailFromStart: () -> Unit,
    onPlayTrailer: () -> Unit,
    onToggleWatched: (PlexItem) -> Unit,
    onToggleWatchedDetail: () -> Unit,
    onFocusEpisode: (PlexItem?) -> Unit,
    onSelectSeason: (PlexItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    if (detail == null) {
        Column(modifier = modifier.fillMaxSize().padding(40.dp)) {
            if (state.error != null) ErrorNote(state.error) else EmptyNote("Loading…")
        }
        return
    }

    var expanded by remember(state.ratingKey) { mutableStateOf(false) }
    val episode = state.focusedEpisode

    // Arriving from a row lands on one episode, which in season nineteen is a long way
    // off the left edge. The rail is brought to it once, when the season's episodes
    // arrive — not on every focus change, which would fight the rail's own scrolling.
    val episodeRail = rememberLazyListState()
    // Set only while a landing focus request is in flight; see the rail effect below.
    var holdColumn by remember { mutableStateOf(false) }
    /*
     * Move the page only when something would otherwise be off screen.
     *
     * The ambient rule on a television is a pivot: it holds whatever has focus three
     * tenths down the viewport, so it scrolls on every move of the cursor, including
     * moves between things already in plain sight. Delegating to it was the mistake in
     * the first attempt at this — coming up off the episode row still shifted the page.
     */
    val pageScroll = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = if (holdColumn) 0f
            else minimumScrollDistance(offset, size, containerSize)
        }
    }
    val railFocus = rememberRowFocus()
    var railBroughtTo by remember(state.ratingKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.episodes) {
        val key = state.focusedEpisode?.ratingKey ?: return@LaunchedEffect
        if (railBroughtTo == key) return@LaunchedEffect
        val index = state.episodes.indexOfFirst { it.ratingKey == key }
        if (index >= 0) {
            // Always, not only past the first: a new season inherits the old one's scroll
            // offset, so landing on episode one still needed the rail wound back to it.
            runCatching { episodeRail.scrollToItem(index) }
            /*
             * Focus lands on the episode, but the page stays where it is.
             *
             * Focus arriving anywhere asks the column to bring it into view, and bringing
             * the rail into view scrolled the season buttons, the title and half the
             * summary off the top. Holding the column still for the moment the request
             * goes through is the difference between the two; refusing focus instead
             * would have been the wrong half of the problem.
             */
            holdColumn = true
            railFocus.land(key)
            withFrameNanos { }
            holdColumn = false
        }
        railBroughtTo = key
    }

    // The block of text always describes whatever has focus: the show, or an episode.
    val backdrop = backdropUrl(state.serverBase, episode?.thumb ?: detail.art ?: detail.thumb)

    Box(modifier = modifier.fillMaxSize()) {
        HeroBackdrop(url = backdrop, modifier = Modifier.fillMaxSize())

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageScroll) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (detail.isShow && state.seasons.isNotEmpty()) {
                item {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.seasons, key = { it.ratingKey }) { season ->
                            TvChip(
                                label = season.title,
                                selected = state.selectedSeason?.ratingKey == season.ratingKey,
                                onClick = { onSelectSeason(season) },
                            )
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (state.error != null) ErrorNote(state.error)

                    HeroText(
                        eyebrow = when {
                            episode != null -> listOfNotNull(
                                episode.grandparentTitle,
                                state.selectedSeason?.title,
                            ).joinToString("  ·  ")

                            else -> null
                        },
                        title = episode?.title ?: detail.title,
                        criticRating = if (episode == null) detail.rating else null,
                        audienceRating = if (episode == null) detail.audienceRating else null,
                        contentRating = if (episode == null) detail.contentRating else null,
                        facts = if (episode != null) {
                            listOfNotNull(
                                episode.caption,
                                formatDuration(episode.durationMs).takeIf { it.isNotEmpty() },
                            )
                        } else {
                            listOfNotNull(
                                detail.year?.toString(),
                                detail.childCount.takeIf { it > 0 && detail.isShow }
                                    ?.let { "$it seasons" },
                                formatDuration(detail.durationMs).takeIf { !detail.isShow && it.isNotEmpty() },
                                detail.studio,
                            )
                        },
                        summary = null,
                        modifier = Modifier.widthIn(max = 780.dp),
                    )

                    val summary = episode?.summary ?: detail.summary
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            color = Muted,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                                minLines = if (expanded) 1 else 3,
                            maxLines = if (expanded) 12 else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 780.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val target = episode
                        // What Play would resume. For a show that is the part-watched
                        // episode, which is also what starting over would restart.
                        val resumeFrom = when {
                            target != null -> target.viewOffsetMs
                            detail.isShow ->
                                state.episodes.firstOrNull { it.resumeFraction != null }?.viewOffsetMs ?: 0L

                            else -> detail.viewOffsetMs
                        }
                        IconAction(
                            label = if (resumeFrom > 0) "Resume" else "Play",
                            filled = true,
                            onClick = { if (target != null) onPlay(target) else onPlayDetail() },
                            glyph = { PlayGlyph(it, 20.dp) },
                        )
                        // Only worth offering when Play would pick up part-way through.
                        if (resumeFrom > 0) {
                            IconAction(
                                label = "Restart",
                                filled = false,
                                onClick = {
                                    if (target != null) onPlayFromStart(target) else onPlayDetailFromStart()
                                },
                                glyph = { RestartGlyph(it, 20.dp) },
                            )
                        }
                        IconAction(
                            label = if ((target?.isWatched ?: detail.isWatched)) "Unwatch" else "Watched",
                            filled = false,
                            onClick = { if (target != null) onToggleWatched(target) else onToggleWatchedDetail() },
                            glyph = { CheckGlyph(it, 20.dp) },
                        )
                        // Only appears when the server actually has a trailer to play.
                        if (state.trailers.isNotEmpty() && episode == null) {
                            IconAction(
                                label = "Trailer",
                                filled = false,
                                onClick = onPlayTrailer,
                                glyph = { TrailerGlyph(it, 20.dp) },
                            )
                        }
                        IconAction(
                            label = if (expanded) "Less" else "Info",
                            filled = false,
                            onClick = { expanded = !expanded },
                            glyph = { InfoGlyph(it, 20.dp) },
                        )
                    }
                }
            }

            if (detail.isShow) {
                if (state.busy && state.episodes.isEmpty()) {
                    item {
                        EmptyNote("Loading episodes…", modifier = Modifier.padding(horizontal = 40.dp))
                    }
                }
                if (state.episodes.isNotEmpty()) {
                    item {
                        LazyRow(
                            state = episodeRail,
                            // Coming back down from Play or Watched returns to the
                            // episode those buttons were acting on, rather than whichever
                            // tile happens to be nearest the cursor.
                            modifier = Modifier.restoreFocusTo(railFocus).focusGroup(),
                            contentPadding = PaddingValues(horizontal = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.episodes, key = { it.ratingKey }) { entry ->
                                val isTarget = state.focusedEpisode?.ratingKey == entry.ratingKey
                                EpisodeTile(
                                    number = entry.index?.toString().orEmpty(),
                                    title = entry.title,
                                    duration = formatDuration(entry.durationMs).takeIf { it.isNotEmpty() },
                                    imageUrl = imageUrl(state.serverBase, entry.thumb, 320, 180),
                                    progress = entry.resumeFraction,
                                    watched = entry.isWatched,
                                    selected = isTarget,
                                    onFocus = {
                                        railFocus.onFocused(entry.ratingKey)
                                        onFocusEpisode(entry)
                                    },
                                    onClick = { onPlay(entry) },
                                    modifier = rowItem(railFocus, entry.ratingKey),
                                )
                            }
                        }
                    }
                }
            }

            if (detail.genres.isNotEmpty()) {
                item {
                    Text(
                        text = "Genres   " + detail.genres.joinToString(", "),
                        color = Muted,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                    )
                }
            }

            if (detail.roles.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("Cast", modifier = Modifier.padding(horizontal = 40.dp))
                        LazyRow(
                            modifier = Modifier.focusGroup(),
                            contentPadding = PaddingValues(horizontal = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(detail.roles.take(24)) { role ->
                                CastCircle(
                                    name = role.name,
                                    role = role.role,
                                    imageUrl = imageUrl(state.serverBase, role.thumb, 160, 160),
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}