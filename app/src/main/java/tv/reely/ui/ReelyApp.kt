package tv.reely.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.GearGlyph
import tv.reely.ui.components.SearchGlyph
import tv.reely.ui.components.TabMenu
import tv.reely.ui.components.TAB_MENU_WIDTH
import tv.reely.ui.components.TabMenuItem
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.screens.GuideScreen
import tv.reely.ui.screens.HomeScreen
import tv.reely.ui.screens.LiveCategoriesScreen
import tv.reely.ui.screens.SearchScreen
import tv.reely.ui.screens.LibraryScreen
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.screens.SettingsScreen
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment

private enum class TabIcon { NONE, SEARCH, GEAR }

private data class Destination(
    val label: String,
    val route: Route,
    val icon: TabIcon = TabIcon.NONE,
)

private val destinations = listOf(
    Destination("Search", Route.Search, icon = TabIcon.SEARCH),
    Destination("Home", Route.Home),
    Destination("Movies", Route.Library(LibraryKind.MOVIES)),
    Destination("TV Shows", Route.Library(LibraryKind.SHOWS)),
    Destination("Live TV", Route.Live),
)

/** Settings sits apart from the destinations, over on the right where a gear belongs. */
private val settingsDestination = Destination("Settings", Route.Settings, icon = TabIcon.GEAR)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReelyApp(viewModel: ReelyViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    val playback = state.playback
    if (playback != null) {
        PlayerScreen(
            playback = playback,
            prefs = state.prefs,
            live = state.live,
            guide = state.guide,
            upNext = state.upNext,
            onExit = viewModel::stopPlayback,
            onEnded = viewModel::onPlaybackEnded,
            onPlayUpNext = viewModel::playUpNext,
            onDismissUpNext = viewModel::dismissUpNext,
            livePlayer = viewModel.livePlayer,
            onStepChannel = viewModel::stepChannel,
            onSelectChannel = viewModel::playChannel,
            multiview = state.multiview,
            onAddToMultiview = viewModel::addToMultiview,
            onRemoveTile = viewModel::removeFromMultiview,
            onClearTiles = viewModel::clearMultiview,
            onReplaceTile = viewModel::replaceInMultiview,
            onCollapseToChannel = viewModel::collapseToChannel,
            onStepEpisode = viewModel::stepEpisode,
            onDecodeFailure = viewModel::retryWithTranscode,
            onToggleFormat = viewModel::toggleFormat,
            onReportProgress = viewModel::reportProgress,
            onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
            onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Which tab has dropped its menu, if any. Choosing a tab that has one opens it
    // rather than re-navigating to where you already are.
    var menuFor by remember { mutableStateOf<LibraryKind?>(null) }
    val menuFocus = remember { FocusRequester() }
    // Where the tab that opened the menu sits, so the panel can hang under it.
    var menuAnchorPx by remember { mutableIntStateOf(0) }
    var constraintsWidth by remember { mutableIntStateOf(0) }
    val menuWidthPx = with(LocalDensity.current) { TAB_MENU_WIDTH.roundToPx() }

    // Back walks the stack: out of a season, off a detail page, and only then out of the app.
    BackHandler(enabled = menuFor != null) { menuFor = null }
    BackHandler(enabled = menuFor == null && state.stack.size > 1) { viewModel.goBack() }
    // A library grid is reached from that library's home, so back belongs there and not
    // out of the application. Top-level routes replace the stack, so there is nothing in
    // it to walk back through.
    val gridRoute = state.route as? Route.Library
    BackHandler(
        enabled = menuFor == null && state.stack.size == 1 &&
            gridRoute != null && gridRoute.view == LibraryView.GRID,
    ) {
        gridRoute?.let { viewModel.navigate(Route.Library(it.kind, LibraryView.HOME)) }
    }

    // An audio player that outlives the screen is the bug this app has already shipped
    // once. Backgrounding kills the theme outright.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.silenceTheme()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contentFocus = remember { FocusRequester() }
    /*
     * One requester per tab, attached for the whole life of the row.
     *
     * A single requester moved onto whichever tab was selected, which meant every
     * navigation changed the shape of two tabs' modifier chains. Compose rebuilds a focus
     * node when that happens, the node holding focus went with it, and focus fell back to
     * the first thing in the window — the search tab. That is why choosing anything sent
     * the cursor to Search.
     */
    val tabFocus = remember { List(destinations.size) { FocusRequester() } }
    val settingsFocus = remember { FocusRequester() }
    // Focus landing on a tab only counts as choosing it when a direction key put it
    // there. Focus that arrives any other way — most often the fallback when a screen
    // replaces itself and briefly has nothing focusable — must not navigate.
    var arrivedByDirectionKey by remember { mutableStateOf(false) }
    // The tab row picks a destination as soon as it is focused, which is the television
    // convention. The cost is that focus must never land there by accident: when a screen
    // replaces itself, the focused node goes with it and focus would fall back onto the
    // tabs, silently navigating somewhere nobody asked for. So whenever the tab row does
    // not own focus, focus is put back into the content as soon as it has something in it.
    var tabRowHasFocus by remember { mutableStateOf(true) }



    val topRoute = state.stack.first()
    val selectedIndex = destinations.indexOfFirst { it.route.sameTabAs(topRoute) }
    // Where "up, out of the content" leads: the tab you are actually on.
    val currentTabFocus = tabFocus.getOrNull(selectedIndex) ?: settingsFocus

    LaunchedEffect(Unit) { tabFocus[1].requestWhenReady() }

    // Closing a tab's menu gives the cursor back to the tab that opened it. Guarded on
    // having actually opened one, so this does not fight the effect above at startup.
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuFor) {
        if (menuFor != null) {
            menuWasOpen = true
            menuFocus.requestWhenReady()
        } else if (menuWasOpen) {
            menuWasOpen = false
            currentTabFocus.requestWhenReady()
        }
    }
    // The content of a screen is composed in the same pass that asks for its focus, so a
    // single request throws and is lost — and focus then falls back to the first thing in
    // the window, which is the search tab. That is why opening an episode left the cursor
    // up in the tab row. Keep asking for a few frames instead.
    LaunchedEffect(routeKey(state.route), contentReady(state), menuFor) {
        if (menuFor != null || tabRowHasFocus || !contentReady(state)) return@LaunchedEffect
        contentFocus.requestWhenReady()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    arrivedByDirectionKey = event.key == Key.DirectionUp ||
                        event.key == Key.DirectionDown ||
                        event.key == Key.DirectionLeft ||
                        event.key == Key.DirectionRight
                }
                false
            },
    ) {
        TopBar(
            current = state.stack.first(),
            // Arriving at a tab and choosing a tab are not the same act. The cursor
            // reaching Movies on its way up out of the content must not drop the menu,
            // which is what it was doing — and with the menu then taking focus back off
            // the content, the two spent the rest of the afternoon passing it between
            // them. Only a press opens a menu.
            onNavigate = { route ->
                if (route != state.stack.first()) {
                    menuFor = null
                    viewModel.navigate(route)
                }
            },
            onActivate = { route ->
                if (route is Route.Library) {
                    menuFor = route.kind
                } else {
                    menuFor = null
                    viewModel.navigate(route)
                }
            },
            onTabFocused = { tabRowHasFocus = true },
            onTabPositioned = { route, x -> if (route is Route.Library) menuAnchorPx = x },
            tabFocus = tabFocus,
            settingsFocus = settingsFocus,
            canSelectOnFocus = { arrivedByDirectionKey.also { arrivedByDirectionKey = false } },
            serverName = state.plex.serverName,
        )

        if (state.restoring) {
            EmptyNote("Starting up…", modifier = Modifier.padding(40.dp))
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(contentFocus)
                .focusGroup()
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Up) currentTabFocus else FocusRequester.Default
                    }
                }
                .onGloballyPositioned { constraintsWidth = it.size.width }
                .onFocusChanged {
                    if (it.hasFocus) {
                        tabRowHasFocus = false
                        arrivedByDirectionKey = false
                    }
                },
        ) {
        when (val route = state.route) {
            is Route.Home -> HomeScreen(
                plex = state.plex,
                home = state.home,
                focused = state.focused,
                imageUrl = viewModel::plexImageUrl,
                backdropUrl = viewModel::plexBackdropUrl,
                onFocusItem = viewModel::focusItem,
                onOpenItem = { viewModel.navigate(detailRouteFor(it)) },
                onStartLink = viewModel::startPlexLink,
                onCancelLink = viewModel::cancelPlexLink,
                onDismissPlexError = viewModel::dismissPlexError,
            )

            is Route.Library -> LibraryScreen(
                kind = route.kind,
                view = route.view,
                plex = state.plex,
                home = state.home,
                focused = state.focused,
                imageUrl = viewModel::plexImageUrl,
                backdropUrl = viewModel::plexBackdropUrl,
                onFocusItem = viewModel::focusItem,
                onOpenItem = { viewModel.navigate(detailRouteFor(it)) },
                onStartLink = viewModel::startPlexLink,
                onCancelLink = viewModel::cancelPlexLink,
                onDismissPlexError = viewModel::dismissPlexError,
                onCycleSort = { viewModel.cycleSort(route.kind) },
                onToggleUnwatched = { viewModel.toggleUnwatchedOnly(route.kind) },
                onSelectGenre = { viewModel.selectGenre(route.kind, it) },
                onDismissBrowseError = { viewModel.dismissBrowseError(route.kind) },
            )

            is Route.Search -> SearchScreen(
                search = state.search,
                focused = state.focused,
                imageUrl = viewModel::plexImageUrl,
                backdropUrl = viewModel::plexBackdropUrl,
                onQueryChange = viewModel::setQuery,
                onFocusItem = viewModel::focusItem,
                onOpenItem = { viewModel.navigate(detailRouteFor(it)) },
                onPlayChannel = viewModel::playSearchChannel,
            )

            is Route.Detail -> {
                val detail = state.detail
                if (detail == null) {
                    EmptyNote("Loading…", modifier = Modifier.padding(40.dp))
                } else {
                    DetailScreen(
                        state = detail,
                        imageUrl = viewModel::plexImageUrl,
                        backdropUrl = viewModel::plexBackdropUrl,
                        onPlay = { viewModel.play(it, queue = detail.episodes) },
                        onPlayFromStart = {
                            viewModel.play(it, queue = detail.episodes, resume = false)
                        },
                        onPlayDetail = { viewModel.playFromDetail() },
                        onPlayDetailFromStart = { viewModel.playFromDetail(resume = false) },
                        onPlayTrailer = viewModel::playTrailer,
                        onToggleWatched = viewModel::toggleWatched,
                        onToggleWatchedDetail = viewModel::toggleWatchedDetail,
                        onFocusEpisode = viewModel::focusEpisode,
                        onSelectSeason = viewModel::selectSeason,
                    )
                }
            }

            // Live TV is the grid, but a category has to be chosen before there is one.
            is Route.Live -> if (state.live.selectedCategory == null) {
                LiveCategoriesScreen(
                    live = state.live,
                    onSignIn = viewModel::signInXtream,
                    onSelectCategory = viewModel::openCategory,
                    onDismissError = viewModel::dismissLiveError,
                )
            } else {
                GuideScreen(
                    live = state.live,
                    guide = state.guide,
                    previewEnabled = state.prefs.guidePreview,
                    onMoveChannel = viewModel::guideMoveChannel,
                    onMoveTime = viewModel::guideMoveTime,
                    onJumpToNow = viewModel::guideJumpToNow,
                    onRefresh = { viewModel.refreshGuide(force = true) },
                    onPlaySelected = viewModel::guidePlaySelected,
                    onBackToCategories = viewModel::clearCategory,
                    livePlayer = viewModel.livePlayer,
                )
            }

            is Route.Settings -> SettingsScreen(
                plex = state.plex,
                live = state.live,
                guide = state.guide,
                prefs = state.prefs,
                onSignOutPlex = viewModel::signOutPlex,
                onSignOutXtream = viewModel::signOutXtream,
                onSwitchServer = viewModel::switchServer,
                onToggleFavourite = viewModel::toggleFavouriteLibrary,
                onToggleFormat = viewModel::toggleFormat,
                onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
                onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
                onNudgeUpNext = viewModel::nudgeUpNextSeconds,
                onToggleGuidePreview = viewModel::toggleGuidePreview,
                onToggleMultiviewLayout = viewModel::toggleMultiviewLayout,
                onToggleThemeMusic = viewModel::toggleThemeMusic,
                onNudgeThemeVolume = viewModel::nudgeThemeVolume,
                onCyclePlaybackMode = viewModel::cyclePlaybackMode,
                onCycleMaxBitrate = viewModel::cycleMaxBitrate,
                onRefreshChannels = viewModel::refreshLiveChannels,
                onRefreshGuide = { viewModel.refreshGuide(force = true) },
                update = state.update,
                updateUrl = viewModel.updateUrl,
                onCheckForUpdate = viewModel::checkForUpdate,
                onInstallUpdate = viewModel::installUpdate,
            )
        }

            if (menuFor != null) {
                val kind = menuFor!!
                val browse = state.plex.browseFor(kind)
                val choices = state.plex.menuChoicesFor(kind)
                TabMenu(
                    groups = listOf(
                        "In ${kind.title}" to listOf(
                            TabMenuItem(
                                label = "Home",
                                selected = state.route == Route.Library(kind, LibraryView.HOME),
                            ) {
                                menuFor = null
                                viewModel.navigate(Route.Library(kind, LibraryView.HOME))
                            },
                            TabMenuItem(
                                label = "Library",
                                selected = state.route == Route.Library(kind, LibraryView.GRID),
                            ) {
                                menuFor = null
                                viewModel.navigate(Route.Library(kind, LibraryView.GRID))
                            },
                        ),
                        // One list. Which server a library sits on is only worth saying
                        // when there is more than one server to tell apart.
                        "Libraries" to choices
                            .takeIf { it.size > 1 }
                            .orEmpty()
                            .map { choice ->
                                TabMenuItem(
                                    label = if (state.plex.namesNeedServer) {
                                        "${choice.section.title} — ${choice.serverName}"
                                    } else {
                                        choice.section.title
                                    },
                                    selected = choice.baseUrl == state.plex.baseUrl &&
                                        choice.section.key == browse.section?.key,
                                ) {
                                    menuFor = null
                                    viewModel.openLibrary(kind, choice)
                                    viewModel.navigate(Route.Library(kind, LibraryView.GRID))
                                }
                            },
                    ),
                    focusRequester = menuFocus,
                    // Sits under the tab it belongs to, pulled back from the right edge
                    // when a tab near the end of the bar would push it off screen.
                    modifier = Modifier
                        .offset {
                            val limit = (constraintsWidth - menuWidthPx).coerceAtLeast(0)
                            IntOffset(menuAnchorPx.coerceIn(0, limit), 0)
                        }
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Where a card in a row leads. Nothing plays straight from a row any more: an episode
 * with nowhere to go but the player leaves no way to mark it watched, look at what it
 * is, or start it over. An episode's page is a place inside its show's page.
 */
private fun detailRouteFor(item: PlexItem): Route.Detail {
    val show = item.grandparentRatingKey
    // The server travels with it: a row can hold things from several, and a rating key
    // means nothing anywhere but the server that issued it.
    return if (item.type == "episode" && show != null) {
        Route.Detail(
            ratingKey = show,
            seasonKey = item.parentRatingKey,
            episodeKey = item.ratingKey,
            serverBase = item.serverBase,
        )
    } else {
        Route.Detail(item.ratingKey, serverBase = item.serverBase)
    }
}

/**
 * Whether two routes belong to the same tab. A library's home and its grid are one tab,
 * so comparing the routes outright would leave the tab unhighlighted on the grid and
 * lose track of where "up out of the content" should land.
 */
private fun Route.sameTabAs(other: Route): Boolean = when {
    this is Route.Library && other is Route.Library -> kind == other.kind
    else -> this == other
}

/** Identifies a destination for focus bookkeeping, ignoring data that arrives later. */
private fun routeKey(route: Route): String = when (route) {
    is Route.Home -> "home"
    is Route.Library -> "library:${route.kind}:${route.view}"
    is Route.Detail -> "detail:${route.serverBase}:${route.ratingKey}:${route.episodeKey}"
    is Route.Live -> "live"
    is Route.Search -> "search"
    is Route.Settings -> "settings"
}

/** Whether the current screen has anything focusable in it yet. */
private fun contentReady(state: ReelyState): Boolean = when (val route = state.route) {
    is Route.Home -> !state.plex.isConnected || !state.home.isEmpty
    is Route.Library -> !state.plex.isConnected || state.plex.browseFor(route.kind).items.isNotEmpty()
    is Route.Detail -> state.detail?.detail != null
    is Route.Live -> true
    is Route.Search -> true
    is Route.Settings -> true
}

@Composable
private fun TopBar(
    current: Route,
    onNavigate: (Route) -> Unit,
    onActivate: (Route) -> Unit,
    onTabFocused: () -> Unit,
    onTabPositioned: (Route, Int) -> Unit,
    tabFocus: List<FocusRequester>,
    settingsFocus: FocusRequester,
    canSelectOnFocus: () -> Boolean,
    serverName: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "reely",
            color = Accent,
            fontSize = 20.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 18.dp),
        )

        destinations.forEachIndexed { index, destination ->
            val isSelected = destination.route.sameTabAs(current)
            NavTab(
                label = destination.label,
                selected = isSelected,
                icon = destination.icon,
                onNavigate = { onNavigate(destination.route) },
                onActivate = { onActivate(destination.route) },
                onFocused = onTabFocused,
                canSelectOnFocus = canSelectOnFocus,
                modifier = Modifier
                    .focusRequester(tabFocus[index])
                    .onGloballyPositioned {
                        onTabPositioned(destination.route, it.positionInRoot().x.toInt())
                    },
            )
        }

        Box(modifier = Modifier.weight(1f))

        if (serverName != null) {
            Text(
                text = serverName,
                color = Faint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        val settingsSelected = settingsDestination.route == current
        NavTab(
            label = settingsDestination.label,
            selected = settingsSelected,
            icon = TabIcon.GEAR,
            onNavigate = { onNavigate(settingsDestination.route) },
            onActivate = { onActivate(settingsDestination.route) },
            onFocused = onTabFocused,
            canSelectOnFocus = canSelectOnFocus,
            modifier = Modifier.focusRequester(settingsFocus),
        )
    }
}

/** Arriving at a tab picks it. Focus is placed back into the content elsewhere. */
@Composable
private fun NavTab(
    label: String,
    selected: Boolean,
    icon: TabIcon = TabIcon.NONE,
    onNavigate: () -> Unit,
    onActivate: () -> Unit,
    onFocused: () -> Unit,
    canSelectOnFocus: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    if (canSelectOnFocus()) onNavigate()
                }
            }
            .clip(RoundedCornerShape(99.dp))
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.22f)
                    focused -> Parchment.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = 2.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(99.dp),
            )
            .clickable(onClick = onActivate)
            .padding(horizontal = if (icon == TabIcon.NONE) 18.dp else 13.dp, vertical = 9.dp),
    ) {
        val tint = if (selected || focused) Parchment else Muted
        when (icon) {
            TabIcon.SEARCH -> SearchGlyph(color = tint, size = 20.dp)
            TabIcon.GEAR -> GearGlyph(color = tint, size = 20.dp)
            TabIcon.NONE -> Text(
                text = label,
                color = tint,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
