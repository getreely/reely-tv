package tv.reely.ui.screens

import tv.reely.ui.components.EpisodeRailPlaceholder
import tv.reely.ui.components.Shimmer
import tv.reely.ui.components.DetailPlaceholder
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
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import tv.reely.ui.theme.Accent
import tv.reely.ui.components.LocalTint
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.formatAirDate
import tv.reely.plex.formatDuration
import tv.reely.ui.DetailState
import tv.reely.core.minimumScrollDistance
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.CastCircle
import tv.reely.ui.components.FocusRow
import tv.reely.ui.components.CheckGlyph
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.IconAction
import tv.reely.ui.components.ExpandableSummary
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos

/** The episode rail's key in the page, so it can be found to bring into view. */
private const val EPISODE_RAIL = "episode-rail"

@Composable
fun DetailScreen(
    state: DetailState,
    imageUrl: (String?, String?, Int, Int) -> String?,
    backdropUrl: (String?, String?) -> String?,
    logoUrl: (String?, String?) -> String?,
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
        if (state.error != null) {
            Column(modifier = modifier.fillMaxSize().padding(40.dp)) { ErrorNote(state.error) }
        } else {
            DetailPlaceholder(modifier = modifier.fillMaxSize())
        }
        return
    }

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
    val page = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Set while a landing is placing focus in the rail itself, which scrolls on its own.
    var landing by remember { mutableStateOf(false) }

    /*
     * The page's one resting place with the rail in use: the whole rail on screen, names
     * and the focused tile's lift included, sat at the bottom so as much of the title and
     * summary above it shows as will fit. Scrolls down to it when the rail is cut off,
     * and back up to it when coming up from the cast below, which otherwise left the
     * title and summary off the top: the tile was already in view, so nothing moved.
     */
    suspend fun settleOnRail() {
        val info = page.layoutInfo
        val rail = info.visibleItemsInfo.firstOrNull { it.key == EPISODE_RAIL } ?: return
        val bottom = info.viewportEndOffset - info.afterContentPadding
        val distance = rail.offset + rail.size - bottom
        if (distance != 0) page.animateScrollBy(distance.toFloat())
    }
    // Set by choosing a season from its row, and spent once that season's episodes land.
    var seasonChosen by remember(state.ratingKey) { mutableStateOf(false) }
    LaunchedEffect(state.episodes) {
        val key = state.focusedEpisode?.ratingKey ?: return@LaunchedEffect
        if (railBroughtTo == key) return@LaunchedEffect
        val index = state.episodes.indexOfFirst { it.ratingKey == key }
        if (index >= 0) {
            // Always, not only past the first: a new season inherits the old one's scroll
            // offset, so landing on episode one still needed the rail wound back to it.
            runCatching { episodeRail.scrollToItem(index) }
            /*
             * Focus lands on the episode without the page leaping after it.
             *
             * Focus arriving anywhere asks the column to bring it into view, and bringing
             * the rail into view scrolled the season buttons, the title and half the
             * summary off the top. Holding the column still for the moment the request
             * goes through is the difference between the two; refusing focus instead
             * would have been the wrong half of the problem.
             */
            holdColumn = true
            landing = true
            railFocus.land(key)
            withFrameNanos { }
            holdColumn = false
            landing = false
            /*
             * Then the page settles on the rail (see settleOnRail), which is what pressing
             * Right along it used to be needed for: landing held the page still, and left
             * the bottom of the rail off the edge.
             *
             * A season picked by hand goes further, and takes the row of seasons up off the
             * top: it is a move on to that season's episodes. So only ever down from there.
             */
            runCatching {
                if (seasonChosen) {
                    page.animateScrollToItem(1)
                    val info = page.layoutInfo
                    val rail = info.visibleItemsInfo.firstOrNull { it.key == EPISODE_RAIL }
                    val overflow = rail?.let { it.offset + it.size - (info.viewportEndOffset - info.afterContentPadding) } ?: 0
                    if (overflow > 0) page.animateScrollBy(overflow.toFloat())
                } else {
                    settleOnRail()
                }
            }
            seasonChosen = false
        }
        railBroughtTo = key
    }

    // The block of text always describes whatever has focus: the show, or an episode.
    val backdrop = backdropUrl(state.serverBase, episode?.thumb ?: detail.art ?: detail.thumb)

    // The screen takes its colour from the artwork of what has focus — the glow at the
    // bottom and behind a focused card. See HeroBackdrop and LocalTint.
    var tint by remember { mutableStateOf(Accent) }
    val glow by animateColorAsState(tint, tween(700), label = "ambient")
    CompositionLocalProvider(LocalTint provides glow) {
        Box(modifier = modifier.fillMaxSize()) {
            HeroBackdrop(url = backdrop, modifier = Modifier.fillMaxSize(), onTint = { tint = it }, glow = glow)

            CompositionLocalProvider(LocalBringIntoViewSpec provides pageScroll) {
            LazyColumn(
                state = page,
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
                                    onClick = {
                                        seasonChosen = true
                                        onSelectSeason(season)
                                    },
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

                        /*
                         * The show's logo stays at the top of its own page, with the focused
                         * episode's title under it. With no logo it is as it was: the show and
                         * season above, the episode's title as the heading.
                         */
                        val logo = logoUrl(state.serverBase, detail.logo)
                        HeroText(
                            eyebrow = when {
                                episode == null -> null
                                logo != null -> state.selectedSeason?.title
                                else -> listOfNotNull(
                                    episode.grandparentTitle,
                                    state.selectedSeason?.title,
                                ).joinToString("  ·  ")
                            },
                            title = if (logo != null) detail.title else episode?.title ?: detail.title,
                            logoUrl = logo,
                            subtitle = episode?.title?.takeIf { logo != null },
                            criticRating = if (episode == null) detail.rating else null,
                            audienceRating = if (episode == null) detail.audienceRating else null,
                            contentRating = if (episode == null) detail.contentRating else null,
                            facts = if (episode != null) {
                                listOfNotNull(
                                    episode.caption,
                                    formatAirDate(episode.airDate),
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
                            qualities = episode?.qualities ?: detail.qualities,
                            modifier = Modifier.widthIn(max = 780.dp),
                        )

                        val summary = episode?.summary ?: detail.summary
                        if (!summary.isNullOrBlank()) {
                            // Body size at a reading width, like the hero's: at 780 dp a line
                            // was too long to find the start of the next one from the sofa.
                            // Three lines; select it to read the rest.
                            ExpandableSummary(text = summary, maxWidth = 640.dp)
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
                        }
                    }
                }

                if (detail.isShow) {
                    if (state.busy && state.episodes.isEmpty()) {
                        item { Shimmer { EpisodeRailPlaceholder() } }
                    }
                    if (state.episodes.isNotEmpty()) {
                        item(key = EPISODE_RAIL) {
                            FocusRow { rowFocused ->
                                LazyRow(
                                    state = episodeRail,
                                    // Coming back down from Play or Watched returns to the
                                    // episode those buttons were acting on, rather than whichever
                                    // tile happens to be nearest the cursor.
                                    modifier = Modifier
                                        .onFocusChanged { focus ->
                                            // Coming into the rail from above or below. The
                                            // column's own scroll is held for the frame the
                                            // tile asks to be brought into view, so the page
                                            // makes one move rather than two.
                                            if (focus.hasFocus && !landing) {
                                                holdColumn = true
                                                scope.launch {
                                                    withFrameNanos { }
                                                    holdColumn = false
                                                    runCatching { settleOnRail() }
                                                }
                                            }
                                        }
                                        .restoreFocusTo(railFocus)
                                        .focusGroup()
                                        .then(rowFocused),
                                    contentPadding = PaddingValues(horizontal = 36.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
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
}