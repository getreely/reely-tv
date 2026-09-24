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
import tv.reely.ui.PlexState
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HeroText
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.components.restoreFocusTo
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
import tv.reely.ui.components.CardMenu
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Ink

// Sized for the type scale: a 72 dp title logo, details, two lines of summary.
private val HERO_HEIGHT = 184.dp

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

    var menuFor by remember { mutableStateOf<PlexItem?>(null) }
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(menuFor) { if (menuFor != null) menuFocus.requestWhenReady() }

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
                        .padding(horizontal = 40.dp, vertical = 10.dp),
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
                            modifier = Modifier.widthIn(max = 700.dp),
                        )
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
                                        onLongPress = { menuFor = item },
                                        modifier = rowItem(resumeFocus, item.listKey),
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
                                        onLongPress = { menuFor = movie },
                                        modifier = rowItem(movieFocus, movie.listKey),
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

            // Drawn last so it sits over the rows. A row clips its children, so a menu
            // raised inside one would appear cut in half.
            menuFor?.let { item ->
                Box(
                    modifier = Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CardMenu(
                        title = item.rowTitle,
                        subtitle = episodeLine(item) ?: item.caption,
                        focusRequester = menuFocus,
                        actions = buildList {
                            val resumable = (item.resumeFraction ?: 0f) > 0f
                            add(
                                CardAction(
                                    label = if (resumable) "Resume" else "Play",
                                    emphasised = true,
                                ) { menuFor = null; onPlayItem(item, true) },
                            )
                            if (resumable) {
                                add(CardAction("Play from the beginning") {
                                    menuFor = null; onPlayItem(item, false)
                                })
                            }
                            add(
                                CardAction(
                                    if (item.isWatched) "Mark unwatched" else "Mark watched",
                                ) { menuFor = null; onToggleWatched(item) },
                            )
                            add(CardAction("Details") { menuFor = null; onOpenItem(item) })
                        },
                        onCancel = { menuFor = null },
                    )
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
        modifier = rowItem(rowFocus, group.listKey),
    )
}

@Composable
private fun PosterRow(
    title: String,
    rowFocus: tv.reely.ui.components.RowFocus,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    // Room between heading and cards for a focused card's lift and ring, which reach
    // about 14 dp above the row and ran into the heading.
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = title,
            color = Chalk,
            style = ReelyType.RowTitle,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        // The rest of the row steps back while one card in it has focus. The gap is wide
        // enough for the focus ring, which sits outside the artwork.
        FocusRow { rowFocused ->
            LazyRow(
                modifier = Modifier.restoreFocusTo(rowFocus).focusGroup().then(rowFocused),
                contentPadding = PaddingValues(horizontal = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}
