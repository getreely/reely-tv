package tv.reely.ui.screens

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import tv.reely.ui.components.PosterRowPlaceholder
import tv.reely.ui.components.HeroPlaceholder
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import tv.reely.ui.components.glass
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tv.reely.ui.theme.Accent
import tv.reely.ui.components.LocalTint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.formatDuration
import tv.reely.ui.EpisodeGroup
import tv.reely.ui.HomeState
import tv.reely.ui.HomeRow
import tv.reely.ui.PlexState
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.components.FollowRemovals
import tv.reely.ui.components.FocusRow
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.ReelyType
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import tv.reely.ui.components.CardAction
import tv.reely.ui.components.ROWS_BOTTOM
import tv.reely.ui.components.rememberRowSnap
import tv.reely.ui.components.CardMenu
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Ink

/*
 * Sized so a whole row fits beneath it on a 540 dp screen, focus lift and safe area
 * included: a 50 dp title logo, details, three lines of summary. At 184 dp the row below
 * ran off the bottom edge and a focused card lost its caption.
 */
private val HERO_HEIGHT = 166.dp

/** Between a row's heading and its cards: room for a focused card's lift and ring. */
private val HEADING_GAP = 16.dp

@Composable
fun HomeScreen(
    plex: PlexState,
    home: HomeState,
    focused: PlexItem?,
    imageUrl: (String?, String?, Int, Int) -> String?,
    backdropUrl: (String?, String?) -> String?,
    logoUrl: (String?, String?) -> String?,
    onFocusItem: (PlexItem?) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    onPlayItem: (PlexItem, Boolean) -> Unit,
    onToggleWatched: (PlexItem) -> Unit,
    onStartLink: () -> Unit,
    onCancelLink: () -> Unit,
    onDismissPlexError: () -> Unit,
    modifier: Modifier = Modifier,
    /** Signed in with no server to show: look again, or sign in as somebody else. */
    onRetryConnect: () -> Unit = {},
    onSignOutPlex: () -> Unit = {},
    onRemoveFromContinueWatching: (PlexItem) -> Unit = {},
    onPlayNextEpisode: (PlexItem) -> Unit = {},
    /** Rows switched off in Settings; see HomeRow. */
    hidden: Set<String> = emptySet(),
    /** Reely's discovery rows, for Home's Trending and Popular. */
    requestRows: List<tv.reely.requests.RequestRow> = emptyList(),
    requestBadge: (tv.reely.requests.RequestTitle) -> String? = { null },
    onOpenRequest: (tv.reely.requests.RequestTitle) -> Unit = {},
    /** What this account asked for that has arrived. */
    ready: List<tv.reely.requests.RequestTitle> = emptyList(),
    onWatchReady: (tv.reely.requests.RequestTitle) -> Unit = {},
    onDismissReady: (tv.reely.requests.RequestTitle) -> Unit = {},
) {
    if (!plex.isConnected) {
        PlexSignInPanel(
            plex = plex,
            onStartLink = onStartLink,
            onCancelLink = onCancelLink,
            onDismissError = onDismissPlexError,
            modifier = modifier.fillMaxSize(),
            onRetryConnect = onRetryConnect,
            onSignOut = onSignOutPlex,
        )
        return
    }

    // One per row, held at screen level so scrolling a row out of view does not lose
    // where the cursor was in it.
    val resumeFocus = rememberRowFocus("continue")
    val episodeFocus = rememberRowFocus("episodes")
    val movieFocus = rememberRowFocus("movies")
    val watchlistFocus = rememberRowFocus("watchlist")
    val playlistFocus = rememberRowFocus("playlists")

    // A row coming into focus snaps its heading to the top of the rows; see
    // rememberRowSnap. The rows keep the television's own rule for moving sideways.
    val sideways = LocalBringIntoViewSpec.current
    val rowsScroll = rememberRowSnap(HEADING_GAP)

    var menuFor by remember { mutableStateOf<PlexItem?>(null) }
    val menuFocus = remember { FocusRequester() }
    // The card the menu was raised on, for the cursor to go back to when it closes. It went
    // to the top of the page instead; if the card itself has gone, the one beside it.
    var menuFrom by remember { mutableStateOf<Triple<tv.reely.ui.components.RowFocus, String, Int>?>(null) }
    LaunchedEffect(menuFor) {
        if (menuFor != null) {
            menuFocus.requestWhenReady()
            return@LaunchedEffect
        }
        val (row, key, index) = menuFrom ?: return@LaunchedEffect
        menuFrom = null
        if (row.nearest(index) == key) row.land(key) else row.landAt(index)
    }
    FollowRemovals(resumeFocus)
    FollowRemovals(movieFocus)
    FollowRemovals(watchlistFocus)

    // The screen takes its colour from the artwork of what has focus — the glow at the
    // bottom and behind a focused card. See HeroBackdrop and LocalTint.
    var tint by remember { mutableStateOf(Accent) }
    val glow by animateColorAsState(tint, tween(700), label = "ambient")
    CompositionLocalProvider(LocalTint provides glow) {
        Box(modifier = modifier.fillMaxSize()) {
            HeroBackdrop(
                url = backdropUrl(focused?.serverBase, focused?.art ?: focused?.thumb),
                modifier = Modifier.fillMaxSize(),
                onTint = { tint = it },
                glow = glow,
            )

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HERO_HEIGHT)
                        .padding(horizontal = 40.dp, vertical = 6.dp),
                ) {
                    if (focused != null) {
                        // An episode is introduced by its show — the show's logo, or its name —
                        // with the episode's own title in the details beneath.
                        val isEpisode = focused.type == "episode" && focused.grandparentTitle != null
                        HeroText(
                            eyebrow = null,
                            title = if (isEpisode) focused.grandparentTitle!! else focused.title,
                            logoUrl = logoUrl(focused.serverBase, focused.logo),
                            criticRating = null,
                            audienceRating = null,
                            contentRating = null,
                            facts = listOfNotNull(
                                focused.caption,
                                focused.title.takeIf { isEpisode },
                                formatDuration(focused.durationMs).takeIf { it.isNotEmpty() },
                            ),
                            summary = focused.summary,
                            qualities = focused.qualities,
                            modifier = Modifier.widthIn(max = 700.dp),
                        )
                    } else if (home.busy && home.isEmpty) {
                        Shimmer { HeroPlaceholder(modifier = Modifier.padding(top = 8.dp)) }
                    } else {
                        Text(
                            text = "Home",
                            color = Chalk,
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides rowsScroll) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = ROWS_BOTTOM),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (home.error != null) {
                            item { ErrorNote(home.error, modifier = Modifier.padding(horizontal = 40.dp)) }
                        }

                        // Something asked for has arrived: said first, one at a time.
                        ready.firstOrNull()?.let { arrived ->
                            item(key = "ready:${arrived.key}") {
                                ReadyCard(
                                    title = arrived,
                                    more = ready.size - 1,
                                    onWatch = { onWatchReady(arrived) },
                                    onDismiss = { onDismissReady(arrived) },
                                )
                            }
                        }

                        if (home.continueWatching.isNotEmpty() && HomeRow.CONTINUE.id !in hidden) {
                            item {
                                PosterRow(title = "Continue Watching", rowFocus = resumeFocus, sideways = sideways) {
                                    itemsIndexed(home.continueWatching, key = { _, it -> it.listKey }) { index, item ->
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
                                            onLongPress = {
                                                menuFrom = Triple(resumeFocus, item.listKey, index)
                                                menuFor = item
                                            },
                                            modifier = rowItem(resumeFocus, item.listKey, index),
                                        )
                                    }
                                }
                            }
                        }

                        if (home.recentEpisodes.isNotEmpty() && HomeRow.EPISODES.id !in hidden) {
                            item {
                                PosterRow(title = "Recently Added Episodes", rowFocus = episodeFocus, sideways = sideways) {
                                    itemsIndexed(home.recentEpisodes, key = { _, it -> it.listKey }) { index, group ->
                                        EpisodeGroupCard(group, imageUrl, episodeFocus, onFocusItem, onOpenItem, index) { newest ->
                                            menuFrom = Triple(episodeFocus, group.listKey, index)
                                            menuFor = newest
                                        }
                                    }
                                }
                            }
                        }

                        if (home.recentMovies.isNotEmpty() && HomeRow.MOVIES.id !in hidden) {
                            item {
                                PosterRow(title = "Recently Added Movies", rowFocus = movieFocus, sideways = sideways) {
                                    itemsIndexed(home.recentMovies, key = { _, it -> it.listKey }) { index, movie ->
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
                                            onLongPress = {
                                                menuFrom = Triple(movieFocus, movie.listKey, index)
                                                menuFor = movie
                                            },
                                            modifier = rowItem(movieFocus, movie.listKey, index),
                                        )
                                    }
                                }
                            }
                        }

                        // The account's Watchlist, as far as the servers here have it.
                        if (home.watchlist.isNotEmpty() && HomeRow.WATCHLIST.id !in hidden) {
                            item {
                                PosterRow(title = "Watchlist", rowFocus = watchlistFocus, sideways = sideways) {
                                    itemsIndexed(home.watchlist, key = { _, it -> it.listKey }) { index, item ->
                                        PosterCard(
                                            title = item.title,
                                            subtitle = item.caption,
                                            imageUrl = imageUrl(item.serverBase, item.thumb, 300, 450),
                                            progress = item.resumeFraction,
                                            watched = item.isWatched,
                                            onFocus = {
                                                watchlistFocus.onFocused(item.listKey)
                                                onFocusItem(item)
                                            },
                                            onClick = { onOpenItem(item) },
                                            onLongPress = {
                                                menuFrom = Triple(watchlistFocus, item.listKey, index)
                                                menuFor = item
                                            }.takeIf { hasItemMenu(item) },
                                            modifier = rowItem(watchlistFocus, item.listKey, index),
                                        )
                                    }
                                }
                            }
                        }

                        if (home.playlists.isNotEmpty() && HomeRow.PLAYLISTS.id !in hidden) {
                            item {
                                PosterRow(title = "Playlists", rowFocus = playlistFocus, sideways = sideways) {
                                    items(home.playlists, key = { it.listKey }) { playlist ->
                                        PosterCard(
                                            title = playlist.title,
                                            subtitle = playlist.leafCount.let { if (it == 1) "1 item" else "$it items" },
                                            imageUrl = imageUrl(playlist.serverBase, playlist.thumb, 300, 450),
                                            onFocus = {
                                                playlistFocus.onFocused(playlist.listKey)
                                                onFocusItem(playlist)
                                            },
                                            onClick = { onOpenItem(playlist) },
                                            modifier = rowItem(playlistFocus, playlist.listKey),
                                        )
                                    }
                                }
                            }
                        }

                        // From Reely: what's trending and popular, films and shows together,
                        // each opening its page in Requests.
                        listOf(
                            HomeRow.TRENDING to listOf("movies", "shows"),
                            HomeRow.POPULAR to listOf("popularMovies", "popularShows"),
                        ).forEach { (row, ids) ->
                            val titles = interleave(ids.map { id -> requestRows.firstOrNull { it.id == id }?.titles.orEmpty() })
                            if (titles.isNotEmpty() && row.id !in hidden) {
                                item(key = row.id) {
                                    val rowFocus = rememberRowFocus(row.id)
                                    PosterRow(title = row.title, rowFocus = rowFocus, sideways = sideways) {
                                        items(titles, key = { it.key }) { title ->
                                            PosterCard(
                                                title = title.title,
                                                subtitle = title.year?.toString(),
                                                tag = requestBadge(title),
                                                imageUrl = title.poster,
                                                onFocus = { rowFocus.onFocused(title.key) },
                                                onClick = { onOpenRequest(title) },
                                                modifier = rowItem(rowFocus, title.key),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (home.isEmpty && home.busy) {
                            item {
                                Shimmer {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        PosterRowPlaceholder()
                                        PosterRowPlaceholder()
                                    }
                                }
                            }
                        } else if (home.isEmpty) {
                            item {
                                EmptyNote(
                                    "Nothing to show yet. Watch something and it will appear here.",
                                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Drawn last so it sits over the rows. A row clips its children, so a menu
            // raised inside one would appear cut in half.
            menuFor?.let { item ->
                ItemMenu(
                    item = item,
                    focusRequester = menuFocus,
                    backdropUrl = backdropUrl,
                    logoUrl = logoUrl,
                    actions = itemMenuActions(
                        item = item,
                        onPlay = { resume -> menuFor = null; onPlayItem(item, resume) },
                        onToggleWatched = { menuFor = null; onToggleWatched(item) },
                        onDetails = { menuFor = null; onOpenItem(item) },
                        onPlayNext = { menuFor = null; onPlayNextEpisode(item) },
                        onRemoveFromContinueWatching = if (home.continueWatching.any { it.listKey == item.listKey }) {
                            { menuFor = null; onRemoveFromContinueWatching(item) }
                        } else {
                            null
                        },
                    ),
                    onCancel = { menuFor = null },
                )
            }

        }
    }
}

/** "Dune is ready to watch": something asked for through Requests, now on the server. */
@Composable
private fun ReadyCard(
    title: tv.reely.requests.RequestTitle,
    more: Int,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 40.dp)
            .glass()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        coil.compose.AsyncImage(
            model = title.poster,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .height(72.dp)
                .width(48.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.widthIn(max = 520.dp)) {
            Text(text = "${title.title} is ready to watch", color = Chalk, style = ReelyType.Body, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (more > 0) "You asked for it. And ${if (more == 1) "1 more" else "$more more"} after this."
                else "You asked for it, and it's here.",
                color = tv.reely.ui.theme.Muted,
                style = ReelyType.Label,
            )
        }
        tv.reely.ui.components.TvActionButton(label = "Watch", onClick = onWatch, emphasised = true)
        tv.reely.ui.components.TvActionButton(label = "Dismiss", onClick = onDismiss)
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
    /** Its place in the row, so the cursor can find its way back if it goes. */
    index: Int = -1,
    /** Holding OK: the menu for the newest episode, which the menu names. */
    onHold: (PlexItem) -> Unit = {},
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
        onLongPress = { onHold(newest) },
        modifier = rowItem(rowFocus, group.listKey, index),
    )
}

@Composable
internal fun PosterRow(
    title: String,
    rowFocus: tv.reely.ui.components.RowFocus,
    /** How the row scrolls along itself: the screen's own rule, not the rows' snap. */
    sideways: BringIntoViewSpec,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    // Room between heading and cards for a focused card's lift and ring, which reach
    // about 14 dp above the row and ran into the heading.
    Column(verticalArrangement = Arrangement.spacedBy(HEADING_GAP)) {
        Text(
            text = title,
            color = Chalk,
            style = ReelyType.RowTitle,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        // The rest of the row steps back while one card in it has focus. The gap is wide
        // enough for the focus ring, which sits outside the artwork.
        FocusRow { rowFocused ->
            CompositionLocalProvider(LocalBringIntoViewSpec provides sideways) {
                LazyRow(
                    modifier = Modifier.restoreFocusTo(rowFocus).focusGroup().then(rowFocused),
                    contentPadding = PaddingValues(horizontal = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
    }
}

/** Films and shows taken in turn, so neither crowds the other out of the row. */
internal fun <T> interleave(lists: List<List<T>>): List<T> {
    val longest = lists.maxOfOrNull { it.size } ?: 0
    return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
}
