package tv.reely.ui.touch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.reely.plex.PlexItem
import tv.reely.ui.BrowseState
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibrarySort
import tv.reely.ui.LibraryView
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.screens.episodeLine
import tv.reely.ui.screens.posterArt
import tv.reely.ui.theme.Accent
import tv.reely.xtream.isIptvSource
import tv.reely.xtream.sourceTag

/**
 * Movies or TV Shows: the tab's own home, the whole library as a grid, its collections,
 * and the IPTV provider's when they're switched on — the same four as the television's
 * tab menu, as chips along the top.
 */
@Composable
internal fun TouchLibrary(viewModel: ReelyViewModel, state: ReelyState, route: Route.Library, actions: TouchActions) {
    val kind = route.kind
    val view = route.view
    val iptvOn = state.iptv.on
    if (!state.plex.isConnected && view != LibraryView.IPTV) {
        Column(Modifier.fillMaxSize()) {
            TouchHeader(kind.title, actions)
            TouchSignIn(viewModel, state)
        }
        return
    }
    val showingIptv = view == LibraryView.IPTV
    val browse: BrowseState = if (showingIptv) state.iptv.browseFor(kind) else state.plex.browseFor(kind)

    Column(Modifier.fillMaxSize()) {
        TouchHeader(if (showingIptv) "IPTV ${kind.title}" else browse.section?.title ?: kind.title, actions)
        // Where in the tab: its home, the library, collections, IPTV; and which library.
        LazyRow(
            contentPadding = PaddingValues(horizontal = TouchMargin),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val views = buildList {
                add(LibraryView.HOME to "For you")
                add(LibraryView.GRID to "Library")
                add(LibraryView.COLLECTIONS to "Collections")
                if (iptvOn && state.prefs.iptvInMenus) add(LibraryView.IPTV to "IPTV")
            }
            items(views, key = { it.first.name }) { (target, label) ->
                TouchChip(label, selected = view == target, onClick = { viewModel.navigate(Route.Library(kind, target)) })
            }
            val choices = state.plex.menuChoicesFor(kind)
            if (choices.size > 1 && !showingIptv) item(key = "libraries") {
                var open by remember { mutableStateOf(false) }
                Box {
                    TouchChip("Libraries ▾", selected = false, onClick = { open = true })
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        choices.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.plex.namesNeedServer) "${choice.section.title} — ${choice.serverName}" else choice.section.title)
                                },
                                onClick = {
                                    open = false
                                    viewModel.openLibrary(kind, choice)
                                    viewModel.navigate(Route.Library(kind, LibraryView.GRID))
                                },
                            )
                        }
                    }
                }
            }
        }
        when (view) {
            LibraryView.HOME -> TabHome(viewModel, state, kind, actions)
            LibraryView.COLLECTIONS -> Collections(browse, actions)
            LibraryView.GRID, LibraryView.IPTV -> Grid(viewModel, state, kind, browse, showingIptv, actions)
        }
    }
}

/** The tab's own home: what you're part-way through, what's arrived, what's newly out. */
@Composable
private fun TabHome(viewModel: ReelyViewModel, state: ReelyState, kind: LibraryKind, actions: TouchActions) {
    val home = state.home
    val browse = state.plex.browseFor(kind)
    val sectionKey = browse.section?.key
    fun here(serverBase: String?, id: String?): Boolean {
        if (isIptvSource(serverBase)) return true
        if (serverBase != null && serverBase != state.plex.baseUrl) return false
        return sectionKey == null || id == null || id == sectionKey
    }
    val resumable = home.continueWatching.filter {
        val rightKind = if (kind == LibraryKind.MOVIES) it.type == "movie" else it.type == "episode"
        rightKind && here(it.serverBase, it.librarySectionId)
    }
    val iptvNew = if (kind == LibraryKind.MOVIES) home.iptvMovies else home.iptvShows
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (resumable.isNotEmpty()) item {
            TouchRow("Continue Watching") {
                items(resumable, key = { it.listKey }) { item ->
                    TouchWide(
                        title = item.rowTitle,
                        subtitle = episodeLine(item),
                        imageUrl = actions.image(item.serverBase, item.art ?: posterArt(item), 480, 270),
                        progress = item.resumeFraction,
                        tag = item.sourceTag,
                        onClick = { viewModel.play(item) },
                        onLongPress = { actions.hold(item) },
                    )
                }
            }
        }
        if (kind == LibraryKind.MOVIES) {
            val recent = home.recentMovies.filter { here(it.serverBase, it.librarySectionId) }
            if (recent.isNotEmpty()) item { PosterRow("Recently Added", recent, actions) }
        } else {
            val groups = home.recentEpisodes.filter { here(it.serverBase, it.librarySectionId) }
            if (groups.isNotEmpty()) item {
                TouchRow("Recently Added") {
                    items(groups, key = { it.listKey }) { group ->
                        TouchPoster(
                            title = group.showTitle,
                            subtitle = if (group.count > 1) "${group.count} new episodes" else group.newest.caption,
                            imageUrl = actions.image(group.serverBase, group.thumb, 300, 450),
                            badge = group.count,
                            onClick = { actions.open(group.newest) },
                            onLongPress = { actions.hold(group.newest) },
                        )
                    }
                }
            }
        }
        if (iptvNew.isNotEmpty()) item { PosterRow("New on IPTV", iptvNew, actions) }
        if (browse.released.isNotEmpty()) item { PosterRow("Recently Released", browse.released, actions) }
        if (home.busy && resumable.isEmpty() && browse.released.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
        }
    }
}

@Composable
private fun PosterRow(title: String, titles: List<PlexItem>, actions: TouchActions) {
    TouchRow(title) {
        items(titles, key = { it.listKey }) { item ->
            TouchPoster(
                title = item.title,
                subtitle = item.caption,
                imageUrl = actions.image(item.serverBase, item.thumb, 300, 450),
                progress = item.resumeFraction,
                watched = item.isWatched,
                tag = item.sourceTag,
                onClick = { actions.open(item) },
                onLongPress = { actions.hold(item) },
            )
        }
    }
}

/** The whole library, or the provider's, sorted and narrowed as on the television. */
@Composable
private fun Grid(
    viewModel: ReelyViewModel,
    state: ReelyState,
    kind: LibraryKind,
    browse: BrowseState,
    iptv: Boolean,
    actions: TouchActions,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(browse.jump) {
        val jump = browse.jump ?: return@LaunchedEffect
        gridState.scrollToItem(1 + jump.index)
    }
    val sections = state.plex.sectionsFor(kind)
    val hasLibrary = iptv || sections.isNotEmpty()
    // The A–Z rail, in title order and when there are enough titles to need it, as on the TV.
    val rail = browse.sort == LibrarySort.TITLE && browse.letters.size > 1 && browse.letters.sumOf { it.count } >= RAIL_MIN_TITLES
    var pointing by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = touchPosterColumns(),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TouchMargin, end = if (rail) TouchMargin + 14.dp else TouchMargin, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasLibrary) GridFilters(viewModel, kind, browse, iptv)
                browse.total?.takeIf { hasLibrary }?.let { total ->
                    Text(
                        countLabel(total, kind),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = tv.reely.ui.theme.Muted,
                    )
                }
                browse.error?.let { TouchError(it, onDismiss = { viewModel.dismissBrowseError(kind) }, modifier = Modifier.padding(0.dp)) }
                if (iptv && state.iptv.error != null && browse.items.isEmpty()) TouchError(state.iptv.error)
                when {
                    !hasLibrary -> TouchNote("No ${kind.title.lowercase()} library on ${state.plex.serverName ?: "this server"}.")
                    iptv && !state.iptv.loading && browse.items.isEmpty() && !browse.isFiltered ->
                        TouchNote("No ${kind.title.lowercase()} from your IPTV provider that aren't already in Plex.")
                    browse.items.isEmpty() && browse.isFiltered && !browse.busy -> TouchNote("Nothing in this library matches those filters.")
                }
            }
        }
        val loading = if (iptv) state.iptv.loading else browse.busy
        if (hasLibrary && loading && browse.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            }
        }
        itemsIndexed(browse.items, key = { _, item -> item.listKey }) { index, item ->
            // Near the end, the next page: in before it's reached.
            if (!iptv && index >= browse.items.size - LOAD_AHEAD) LaunchedEffect(browse.items.size) { viewModel.loadMoreBrowse(kind) }
            TouchPoster(
                title = item.title,
                subtitle = item.caption,
                imageUrl = actions.image(item.serverBase, item.thumb, 300, 450),
                progress = item.resumeFraction,
                watched = item.isWatched,
                tag = item.sourceTag,
                width = null,
                onClick = { actions.open(item) },
                onLongPress = { actions.hold(item) },
            )
        }
    }
    if (rail) {
        LetterRail(
            letters = browse.letters.map { it.letter },
            onLetter = { letter -> if (iptv) viewModel.jumpToIptvLetter(kind, letter) else viewModel.jumpToLetter(kind, letter) },
            onPointing = { pointing = it },
            modifier = Modifier.align(Alignment.CenterEnd).padding(top = 12.dp, bottom = 12.dp, end = 2.dp),
        )
    }
    // The letter under the finger, big enough to read past it.
    pointing?.let { letter ->
        Box(
            Modifier.align(Alignment.Center).size(72.dp)
                .background(tv.reely.ui.theme.SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(letter, fontSize = 34.sp, color = tv.reely.ui.theme.Chalk) }
    }
    }
}

/** "212 movies", "1 show": how many there are, with the filters on. */
internal fun countLabel(total: Int, kind: LibraryKind): String {
    val noun = if (kind == LibraryKind.MOVIES) "movie" else "show"
    return "%,d %s".format(total, if (total == 1) noun else noun + "s")
}

/**
 * The A–Z rail down the right edge: a tap on a letter goes there, and so does a finger run
 * down it, the letter under it shown big ([onPointing]) while it's down.
 */
@Composable
private fun LetterRail(letters: List<String>, onLetter: (String) -> Unit, onPointing: (String?) -> Unit, modifier: Modifier) {
    var height by remember { mutableStateOf(0) }
    var current by remember { mutableStateOf<String?>(null) }
    fun pick(y: Float) {
        if (letters.isEmpty() || height <= 0) return
        val letter = letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
        if (letter != current) {
            current = letter
            onPointing(letter)
            onLetter(letter)
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(22.dp)
            .onSizeChanged { height = it.height }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position.y)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                pick(change.position.y)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    current = null
                    onPointing(null)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Each letter the middle of an equal share of the height, as [pick] reads it.
        letters.forEach { letter ->
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    letter,
                    fontSize = 11.sp,
                    color = if (letter == current) Accent else tv.reely.ui.theme.Muted,
                )
            }
        }
    }
}

/** Sort, unwatched, decade and genre, or the provider's categories for IPTV. */
@Composable
private fun GridFilters(viewModel: ReelyViewModel, kind: LibraryKind, browse: BrowseState, iptv: Boolean) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "sort") {
            var open by remember { mutableStateOf(false) }
            Box {
                TouchChip("Sort · ${browse.sort.label}", selected = browse.sort != LibrarySort.TITLE, onClick = { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    val sorts = if (iptv) LibrarySort.entries - LibrarySort.AUDIENCE else LibrarySort.entries
                    sorts.forEach { sort ->
                        DropdownMenuItem(text = { Text(sort.label) }, onClick = {
                            open = false
                            if (iptv) viewModel.setIptvSort(kind, sort) else viewModel.setSort(kind, sort)
                        })
                    }
                }
            }
        }
        item(key = "unwatched") {
            TouchChip("Unwatched", selected = browse.unwatchedOnly, onClick = {
                if (iptv) viewModel.toggleIptvUnwatched(kind) else viewModel.toggleUnwatchedOnly(kind)
            })
        }
        if (browse.decades.size > 1 && !iptv) item(key = "decade") {
            var open by remember { mutableStateOf(false) }
            val decade = browse.decades.firstOrNull { it.id == browse.decade }
            Box {
                TouchChip(decade?.title ?: "All decades", selected = decade != null, onClick = { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("All decades") }, onClick = { open = false; viewModel.selectDecade(kind, null) })
                    browse.decades.forEach { option ->
                        DropdownMenuItem(text = { Text(option.title) }, onClick = { open = false; viewModel.selectDecade(kind, option.id) })
                    }
                }
            }
        }
        if (browse.genres.isNotEmpty()) {
            item(key = "allGenres") {
                TouchChip(if (iptv) "All categories" else "All genres", selected = browse.genreId == null, onClick = {
                    if (iptv) viewModel.selectIptvCategory(kind, null) else viewModel.selectGenre(kind, null)
                })
            }
            items(browse.genres, key = { "genre:" + it.id }) { genre ->
                TouchChip(genre.title, selected = browse.genreId == genre.id, onClick = {
                    if (iptv) viewModel.selectIptvCategory(kind, genre.id) else viewModel.selectGenre(kind, genre.id)
                })
            }
        }
    }
}

@Composable
private fun Collections(browse: BrowseState, actions: TouchActions) {
    val collections = browse.collections
    LazyVerticalGrid(
        columns = touchPosterColumns(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TouchMargin, end = TouchMargin, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (collections == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            }
        } else if (collections.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TouchNote("No collections in ${browse.section?.title ?: "this library"} yet. Collections made in Plex show up here.")
            }
        } else {
            itemsIndexed(collections, key = { _, it -> it.listKey }) { _, collection ->
                TouchPoster(
                    title = collection.title,
                    subtitle = collection.caption,
                    imageUrl = actions.image(collection.serverBase, collection.thumb, 300, 450),
                    width = null,
                    onClick = { actions.open(collection) },
                )
            }
        }
    }
}

private const val LOAD_AHEAD = 24

/** Fewer titles than this and the rail is more in the way than useful, as on the TV. */
private const val RAIL_MIN_TITLES = 40
