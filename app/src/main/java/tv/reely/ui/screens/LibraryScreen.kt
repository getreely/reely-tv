package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import tv.reely.plex.PlexLetter
import tv.reely.ui.components.ChoicePanel
import tv.reely.ui.components.ChoiceRequest
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.components.ROWS_BOTTOM
import tv.reely.ui.components.bleed
import tv.reely.ui.components.rememberMarginScroll
import tv.reely.ui.components.rememberRowSnap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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

/** A title, its details and three lines of summary. */
private val HERO_HEIGHT = 156.dp

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
    onSetSort: (LibrarySort) -> Unit,
    onToggleUnwatched: () -> Unit,
    onSelectGenre: (String?) -> Unit,
    onDismissBrowseError: () -> Unit,
    modifier: Modifier = Modifier,
    /** The cursor is near the end of the grid: time for the next page. */
    onLoadMore: () -> Unit = {},
    onSelectDecade: (String?) -> Unit = {},
    onJumpToLetter: (String) -> Unit = {},
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
    val resumeFocus = rememberRowFocus("continue")
    val recentFocus = rememberRowFocus("recent")
    val releasedFocus = rememberRowFocus("released")
    val gridFocus = rememberRowFocus("grid")

    /*
     * Room around a focused card, so its lift and caption are never off the bottom of the
     * screen. The rows snap one at a time, heading at the top, as Home's do; the grid
     * scrolls only as far as it must. The rows keep the television's own rule sideways.
     */
    val sideways = LocalBringIntoViewSpec.current
    val rowSnap = rememberRowSnap(ROW_HEADING_GAP)
    val gridScroll = rememberMarginScroll(above = 14.dp)
    val gridState = rememberLazyGridState()

    // Sort and decade open a list to choose from, drawn over the page.
    var choosing by remember { mutableStateOf<ChoiceRequest?>(null) }
    val sortChip = remember { FocusRequester() }
    val decadeChip = remember { FocusRequester() }

    // The A–Z rail asked for a letter: bring its first title into view and put the cursor on it.
    LaunchedEffect(browse.jump) {
        val jump = browse.jump ?: return@LaunchedEffect
        val item = browse.items.getOrNull(jump.index) ?: return@LaunchedEffect
        // The heading comes first in the grid, then the titles.
        gridState.scrollToItem(1 + jump.index)
        gridFocus.land(item.listKey)
    }
    val showRail = view == LibraryView.GRID && browse.sort == LibrarySort.TITLE &&
        browse.letters.size > 1 && browse.letters.sumOf { it.count } >= RAIL_MIN_TITLES

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
                    // As on Home: an episode goes under its show's name, with its own title
                    // among the details, which keeps the hero one line shorter than a
                    // heading above the title would, and leaves room for three lines of summary.
                    val isEpisode = focused.type == "episode" && focused.grandparentTitle != null
                    HeroText(
                        eyebrow = null,
                        title = if (isEpisode) focused.grandparentTitle!! else focused.title,
                        criticRating = null,
                        audienceRating = null,
                        contentRating = null,
                        facts = listOfNotNull(
                            focused.caption,
                            focused.title.takeIf { isEpisode },
                            formatDuration(focused.durationMs).takeIf { it.isNotEmpty() && focused.isPlayable },
                        ),
                        summary = focused.summary,
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

            CompositionLocalProvider(
                LocalBringIntoViewSpec provides if (view == LibraryView.HOME) rowSnap else gridScroll,
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 36.dp, end = 36.dp, bottom = ROWS_BOTTOM),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (view == LibraryView.HOME && resumable.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            RowBlock("Continue Watching", sideways) {
                                LazyRow(
                                    modifier = Modifier.bleed(PAGE_MARGIN).restoreFocusTo(resumeFocus).focusGroup(),
                                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                                            onLongPress = holdFor(item),
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
                            RowBlock("Recently Added", sideways) {
                                LazyRow(
                                    modifier = Modifier.bleed(PAGE_MARGIN).restoreFocusTo(recentFocus).focusGroup(),
                                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                                            onLongPress = holdFor(movie),
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
                            RowBlock("Recently Added", sideways) {
                                LazyRow(
                                    modifier = Modifier.bleed(PAGE_MARGIN).restoreFocusTo(recentFocus).focusGroup(),
                                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                                            onLongPress = holdFor(group.newest),
                                            modifier = rowItem(recentFocus, group.listKey),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (view == LibraryView.HOME && browse.released.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            RowBlock("Recently Released", sideways) {
                                LazyRow(
                                    modifier = Modifier.bleed(PAGE_MARGIN).restoreFocusTo(releasedFocus).focusGroup(),
                                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                                            onLongPress = holdFor(item),
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
                                EmptyNote("Loading…")
                            }
                        }
                        return@LazyVerticalGrid
                    }

                    // A library's collections: posters, each opening the collection's page.
                    if (view == LibraryView.COLLECTIONS) {
                        val collections = browse.collections
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Collections",
                                    color = Chalk,
                                    style = ReelyType.RowTitle,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                if (collections != null && collections.isEmpty()) {
                                    EmptyNote(
                                        "No collections in ${browse.section?.title ?: "this library"} yet. " +
                                            "Collections made in Plex show up here."
                                    )
                                }
                            }
                        }
                        if (collections == null) {
                            items(PLACEHOLDER_COUNT) { Shimmer { PosterPlaceholder() } }
                        } else {
                            items(collections, key = { it.listKey }) { collection ->
                                PosterCard(
                                    title = collection.title,
                                    subtitle = collection.caption,
                                    imageUrl = imageUrl(collection.serverBase, collection.thumb, 300, 450),
                                    onFocus = {
                                        gridFocus.onFocused(collection.listKey)
                                        onFocusItem(collection)
                                    },
                                    onClick = { onOpenItem(collection) },
                                    modifier = rowItem(gridFocus, collection.listKey),
                                )
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
                                CompositionLocalProvider(LocalBringIntoViewSpec provides sideways) {
                                    LazyRow(
                                        modifier = Modifier.restoreFocusTo(recentFocus).focusGroup(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        item {
                                            TvChip(
                                                label = "Sort · ${browse.sort.label}",
                                                selected = browse.sort != LibrarySort.TITLE,
                                                onClick = {
                                                    val sorts = LibrarySort.entries
                                                    choosing = ChoiceRequest(
                                                        title = "Sort by",
                                                        options = sorts.map { it.label to null },
                                                        selected = sorts.indexOf(browse.sort),
                                                        onPick = { onSetSort(sorts[it]) },
                                                        returnTo = sortChip,
                                                    )
                                                },
                                                modifier = Modifier.focusRequester(sortChip),
                                            )
                                        }
                                        item {
                                            TvChip(
                                                label = "Unwatched",
                                                selected = browse.unwatchedOnly,
                                                onClick = onToggleUnwatched,
                                            )
                                        }
                                        if (browse.decades.size > 1) {
                                            item {
                                                val decade = browse.decades.firstOrNull { it.id == browse.decade }
                                                TvChip(
                                                    label = decade?.title ?: "All decades",
                                                    selected = decade != null,
                                                    onClick = {
                                                        val decades = listOf<String?>(null) + browse.decades.map { it.id }
                                                        choosing = ChoiceRequest(
                                                            title = "Decade",
                                                            options = listOf("All decades" to null) +
                                                                browse.decades.map { it.title to null },
                                                            selected = decades.indexOf(browse.decade).coerceAtLeast(0),
                                                            onPick = { onSelectDecade(decades[it]) },
                                                            returnTo = decadeChip,
                                                        )
                                                    },
                                                    modifier = Modifier.focusRequester(decadeChip),
                                                )
                                            }
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

                    itemsIndexed(browse.items, key = { _, item -> item.listKey }) { index, item ->
                        PosterCard(
                            title = item.title,
                            subtitle = item.caption,
                            imageUrl = imageUrl(item.serverBase, item.thumb, 300, 450),
                            progress = item.resumeFraction,
                            watched = item.isWatched,
                            onFocus = {
                                gridFocus.onFocused(item.listKey)
                                onFocusItem(item)
                                // Eight rows from the end: the next page is in before it's reached.
                                if (index >= browse.items.size - LOAD_AHEAD) onLoadMore()
                            },
                            onClick = { onOpenItem(item) },
                            onLongPress = holdFor(item),
                            modifier = rowItem(gridFocus, item.listKey),
                        )
                    }
                }
            }
        }

        if (showRail) {
            val current = focused?.takeIf { view == LibraryView.GRID }?.let(::letterOf)
            LetterRail(
                letters = browse.letters,
                current = current,
                onJump = onJumpToLetter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 10.dp, bottom = 10.dp, end = 6.dp),
            )
        }

        choosing?.let { request ->
            ChoicePanel(
                request = request,
                onClose = {
                    choosing = null
                    runCatching { request.returnTo.requestFocus() }
                },
            )
        }
    }
}

/** Which letter of the rail a title comes under: "#" for anything but A to Z. */
internal fun letterOf(item: PlexItem): String {
    val first = (item.titleSort ?: item.title).trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

private val RAIL_LETTERS = listOf("#") + ('A'..'Z').map { it.toString() }

/**
 * The A–Z rail down the right of a library in title order. Right from the grid's last
 * column reaches it; OK on a letter moves the cursor to that letter's first title. Letters
 * with nothing under them are shown, so the rail keeps its shape, but can't be chosen.
 */
@Composable
private fun LetterRail(
    letters: List<PlexLetter>,
    current: String?,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val present = remember(letters) { letters.map { it.letter.uppercase() }.toSet() }
    Column(
        modifier = modifier.width(RAIL_WIDTH).focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RAIL_LETTERS.forEach { letter ->
            val available = letter in present
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(RAIL_WIDTH)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused) Chalk else Color.Transparent)
                    .then(
                        if (available) Modifier.clickable {
                            // Plex names the letter as it has it; match it back up.
                            letters.firstOrNull { it.letter.uppercase() == letter }?.let { onJump(it.letter) }
                        } else Modifier.focusProperties { canFocus = false }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    color = when {
                        focused -> Ink
                        !available -> Faint.copy(alpha = 0.5f)
                        letter == current -> Accent
                        else -> Muted
                    },
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    fontWeight = if (focused || letter == current) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

private val RAIL_WIDTH = 24.dp

/** Below this many titles a rail is more in the way than it helps. */
private const val RAIL_MIN_TITLES = 40

@Composable
private fun RowBlock(title: String, sideways: BringIntoViewSpec, content: @Composable () -> Unit) {
    // Room between heading and cards for a focused card's lift and ring.
    Column(verticalArrangement = Arrangement.spacedBy(ROW_HEADING_GAP)) {
        Text(
            text = title,
            color = Chalk,
            style = ReelyType.RowTitle,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides sideways, content = content)
    }
}

private val ROW_HEADING_GAP = 16.dp

/** The grid's side padding, which a row reaches out over to the screen's edge. */
private val PAGE_MARGIN = 36.dp

/** How near the end of the grid, in titles, the next page is asked for. */
private const val LOAD_AHEAD = 48

/** Three rows of a six-across grid: a screenful, and no more. */
private const val PLACEHOLDER_COUNT = 18
