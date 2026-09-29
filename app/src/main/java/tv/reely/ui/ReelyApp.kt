package tv.reely.ui

import tv.reely.ui.components.DetailPlaceholder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceRaised
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
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
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.screens.PersonScreen
import tv.reely.ui.screens.PlaylistScreen
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
import tv.reely.ui.components.pillColors
import tv.reely.ui.theme.Chalk

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

    /*
     * "Who's watching?", over everything else while it is up. Closed by Back, by picking
     * the profile already in use, or by a switch going through.
     */
    var pickingProfile by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var crashNotice by remember { mutableStateOf(tv.reely.core.CrashLog.takeUnseen(context)) }
    LaunchedEffect(crashNotice) {
        if (crashNotice) {
            delay(10_000)
            crashNotice = false
        }
    }
    var wasSwitching by remember { mutableStateOf(false) }
    LaunchedEffect(state.plex.switchingTo, state.plex.switchError) {
        if (state.plex.switchingTo != null) {
            wasSwitching = true
        } else if (wasSwitching) {
            wasSwitching = false
            if (state.plex.switchError == null) pickingProfile = false
        }
    }
    if (pickingProfile && state.playback == null) {
        tv.reely.ui.screens.ProfilePicker(
            users = state.plex.homeUsers,
            current = state.plex.user,
            switchingTo = state.plex.switchingTo,
            error = state.plex.switchError,
            onPick = viewModel::switchUser,
            onDismissError = viewModel::dismissSwitchError,
            onClose = { pickingProfile = false },
        )
        return
    }

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
            onCredits = viewModel::onCreditsReached,
            onPlayUpNext = viewModel::playUpNext,
            onDismissUpNext = viewModel::dismissUpNext,
            onToggleFavoriteChannel = viewModel::toggleFavoriteChannel,
            livePlayer = viewModel.livePlayer,
            onStepChannel = viewModel::stepChannel,
            onSelectChannel = viewModel::playChannel,
            onOpenCategory = viewModel::openCategory,
            multiview = state.multiview,
            onAddToMultiview = viewModel::addToMultiview,
            onRemoveTile = viewModel::removeFromMultiview,
            onClearTiles = viewModel::clearMultiview,
            onReplaceTile = viewModel::replaceInMultiview,
            onCollapseToChannel = viewModel::collapseToChannel,
            onStepEpisode = viewModel::stepEpisode,
            onDecodeFailure = viewModel::retryWithTranscode,
            onConvertAudio = viewModel::convertAudio,
            onToggleFormat = viewModel::toggleFormat,
            onReportProgress = viewModel::reportProgress,
            onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
            onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
            imageUrl = viewModel::plexImageUrl,
            logoUrl = viewModel::plexLogoUrl,
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
    // A library grid is reached from that library's home, so back belongs there and not
    // out of the application. Top-level routes replace the stack, so there is nothing in
    // it to walk back through.
    val gridRoute = state.route as? Route.Library
    var confirmExit by remember { mutableStateOf(false) }
    val activity = LocalActivity.current

    /*
     * One handler, in order, rather than several fighting over which is enabled. The last
     * step asks instead of leaving: closing the whole application on a single press of
     * back, from a screen somebody only wandered into, is not something to do quietly.
     */
    BackHandler {
        when {
            confirmExit -> confirmExit = false
            menuFor != null -> menuFor = null
            state.stack.size > 1 -> viewModel.goBack()
            gridRoute != null && gridRoute.view != LibraryView.HOME ->
                viewModel.navigate(Route.Library(gridRoute.kind, LibraryView.HOME))

            else -> confirmExit = true
        }
    }

    // An audio player that outlives the screen is the bug this app has already shipped
    // once. Backgrounding kills the theme outright.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.silenceTheme()
                wasStopped = true
            }
            // Back from the launcher or a screensaver: whatever was added meanwhile.
            // Not the first start, which loads everything anyway.
            if (event == Lifecycle.Event.ON_START && wasStopped) viewModel.refreshVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A browsing screen left up keeps itself current. See ReelyViewModel.refreshVisible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(BROWSE_REFRESH_MS)
            viewModel.refreshVisible()
        }
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
    // The page on screen was reached by the cursor moving along the tabs, and the cursor
    // is still up there. A page must not pull it down into itself: see GuideScreen.
    var cameByTabs by remember { mutableStateOf(false) }



    // Settings sits on top of wherever it was opened from, so it is the thing to
    // highlight while it is showing rather than the tab underneath it.
    val topRoute = if (state.route is Route.Settings) Route.Settings else state.stack.first()
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

    /*
     * Opening something from a page — a card, a search result — always goes into the page
     * it opens. The page being left takes the focused card with it, and focus drops to the
     * first thing in the window, the search tab, which then reads as the tab row having
     * been chosen. That is how opening a movie from the Movies tab left the cursor on
     * Search instead of Play. Set on opening, and spent once focus is in the new page.
     */
    var openedFromPage by remember { mutableStateOf(false) }
    fun open(item: PlexItem) {
        openedFromPage = true
        viewModel.navigate(
            if (item.type == "playlist") Route.Playlist(item.ratingKey, item.title, item.serverBase)
            else detailRouteFor(item)
        )
    }
    // The content of a screen is composed in the same pass that asks for its focus, so a
    // single request throws and is lost — and focus then falls back to the first thing in
    // the window, which is the search tab. That is why opening an episode left the cursor
    // up in the tab row. Keep asking for a few frames instead.
    LaunchedEffect(routeKey(state.route), contentReady(state), menuFor) {
        if (menuFor != null || !contentReady(state)) return@LaunchedEffect
        if (tabRowHasFocus && !openedFromPage) return@LaunchedEffect
        contentFocus.requestWhenReady()
        openedFromPage = false
    }

    /*
     * The net under all of it. When whatever has the cursor is taken off the screen — a
     * button that turns into a different button once pressed, a row that changes what it
     * is, a panel closing — the cursor has nowhere to be, and the next press of a key
     * puts it on the first thing in the window: Search. Whenever nothing in the app has
     * focus, it goes back where the person was: the tab they were on, or the page.
     */
    var appHasFocus by remember { mutableStateOf(true) }
    LaunchedEffect(appHasFocus) {
        if (appHasFocus) return@LaunchedEffect
        // Give anything that is moving the cursor on purpose a moment to do it first.
        delay(FOCUS_RESCUE_DELAY_MS)
        if (tabRowHasFocus) currentTabFocus.requestWhenReady() else contentFocus.requestWhenReady()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .onFocusChanged { appHasFocus = it.hasFocus }
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
            current = topRoute,
            // Arriving at a tab and choosing a tab are not the same act. The cursor
            // reaching Movies on its way up out of the content must not drop the menu,
            // which is what it was doing — and with the menu then taking focus back off
            // the content, the two spent the rest of the afternoon passing it between
            // them. Only a press opens a menu.
            onNavigate = { route ->
                if (route != state.stack.first()) {
                    menuFor = null
                    cameByTabs = true
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
            profile = state.plex.user,
            onProfile = if (state.plex.canSwitchUser) ({ pickingProfile = true }) else null,
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
                .pageArea(upTo = currentTabFocus)
                .onGloballyPositioned { constraintsWidth = it.size.width }
                .onFocusChanged {
                    if (it.hasFocus) {
                        tabRowHasFocus = false
                        arrivedByDirectionKey = false
                        cameByTabs = false
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
                logoUrl = viewModel::plexLogoUrl,
                onFocusItem = viewModel::focusItem,
                onOpenItem = ::open,
                onPlayItem = { item, resume -> viewModel.play(item, resume = resume) },
                onToggleWatched = viewModel::toggleWatched,
                onRemoveFromContinueWatching = viewModel::removeFromContinueWatching,
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
                onOpenItem = ::open,
                onStartLink = viewModel::startPlexLink,
                onCancelLink = viewModel::cancelPlexLink,
                onDismissPlexError = viewModel::dismissPlexError,
                onSetSort = { viewModel.setSort(route.kind, it) },
                onToggleUnwatched = { viewModel.toggleUnwatchedOnly(route.kind) },
                onSelectGenre = { viewModel.selectGenre(route.kind, it) },
                onDismissBrowseError = { viewModel.dismissBrowseError(route.kind) },
                onLoadMore = { viewModel.loadMoreBrowse(route.kind) },
                onSelectDecade = { viewModel.selectDecade(route.kind, it) },
                onJumpToLetter = { viewModel.jumpToLetter(route.kind, it) },
            )

            is Route.Search -> SearchScreen(
                search = state.search,
                focused = state.focused,
                imageUrl = viewModel::plexImageUrl,
                backdropUrl = viewModel::plexBackdropUrl,
                onQueryChange = viewModel::setQuery,
                onFocusItem = viewModel::focusItem,
                onOpenItem = ::open,
                onPlayChannel = viewModel::playSearchChannel,
                onOpenPerson = { person ->
                    openedFromPage = true
                    viewModel.navigate(Route.Person(person.id, person.name, person.thumb, person.serverBase))
                },
            )

            is Route.Detail -> {
                val detail = state.detail
                if (detail == null) {
                    DetailPlaceholder(modifier = Modifier.fillMaxSize())
                } else key(detail.ratingKey) {
                    // One title's page to another, from "More like this", starts at the top
                    // of the new one rather than wherever the last one was scrolled to.
                    DetailScreen(
                        state = detail,
                        imageUrl = viewModel::plexImageUrl,
                        backdropUrl = viewModel::plexBackdropUrl,
                logoUrl = viewModel::plexLogoUrl,
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
                        onOpenRelated = ::open,
                        onSelectVersion = viewModel::selectVersion,
                        watchlisted = detail.detail?.guid?.let { guid ->
                            state.plex.watchlist?.let { guid in it }
                        }?.takeIf { detail.detail?.type == "movie" || detail.detail?.type == "show" },
                        onToggleWatchlist = viewModel::toggleWatchlist,
                        onOpenPerson = { role ->
                            val id = role.id ?: return@DetailScreen
                            openedFromPage = true
                            viewModel.navigate(Route.Person(id, role.name, role.thumb, detail.serverBase))
                        },
                    )
                }
            }

            is Route.Playlist -> {
                val playlist = state.playlist
                if (playlist != null && playlist.route == route) {
                    PlaylistScreen(
                        state = playlist,
                        imageUrl = viewModel::plexImageUrl,
                        onPlay = viewModel::playPlaylist,
                        onOpenItem = ::open,
                    )
                }
            }

            is Route.Person -> {
                val person = state.person
                if (person != null && person.route == route) {
                    PersonScreen(
                        state = person,
                        imageUrl = viewModel::plexImageUrl,
                        onOpenItem = ::open,
                    )
                }
            }

            // Live TV is the grid, but a category has to be chosen before there is one.
            is Route.Live -> if (state.live.selectedCategory == null) {
                LiveCategoriesScreen(
                    live = state.live,
                    onSignIn = viewModel::signInXtream,
                    onSignInPlaylist = viewModel::signInPlaylist,
                    onSelectCategory = viewModel::openCategory,
                    onDismissError = viewModel::dismissLiveError,
                )
            } else {
                GuideScreen(
                    takeFocus = !cameByTabs,
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
                onSwitchProfile = { pickingProfile = true },
                onToggleFavourite = viewModel::toggleFavouriteLibrary,
                onToggleFormat = viewModel::toggleFormat,
                onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
                onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
                onNudgeUpNext = viewModel::nudgeUpNextSeconds,
                onToggleGuidePreview = viewModel::toggleGuidePreview,
                onToggleMultiviewLayout = viewModel::toggleMultiviewLayout,
                onToggleThemeMusic = viewModel::toggleThemeMusic,
                onToggleMatchFrameRate = viewModel::toggleMatchFrameRate,
                onToggleLargerBuffer = viewModel::toggleLargerBuffer,
                onNudgeThemeVolume = viewModel::nudgeThemeVolume,
                onSetPlaybackMode = viewModel::setPlaybackMode,
                onSetMaxBitrate = viewModel::setMaxBitrate,
                onRefreshChannels = viewModel::refreshLiveChannels,
                onRefreshGuide = { viewModel.refreshGuide(force = true) },
                update = state.update,
                updateUrl = viewModel.updateUrl,
                onCheckForUpdate = viewModel::checkForUpdate,
                onInstallUpdate = viewModel::installUpdate,
            )
        }

            // Once, after the app fell over last time: an apology, and where the details are.
            if (crashNotice) {
                Text(
                    text = "Reely closed unexpectedly last time. Sorry about that. " +
                        "The details are in Settings, About.",
                    color = Chalk,
                    style = ReelyType.Label,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 40.dp, bottom = 24.dp)
                        .widthIn(max = 420.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceRaised.copy(alpha = 0.96f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // A newer version found at startup. Not over something playing: it waits.
            if (state.updatePrompt && !confirmExit) {
                tv.reely.ui.screens.UpdatePrompt(
                    status = state.update,
                    onUpdate = viewModel::installUpdate,
                    onLater = viewModel::dismissUpdatePrompt,
                )
            }

            if (confirmExit) {
                ConfirmExit(
                    onLeave = { activity?.finish() },
                    onStay = { confirmExit = false },
                    modifier = Modifier.align(Alignment.Center),
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
                            TabMenuItem(
                                label = "Collections",
                                selected = state.route == Route.Library(kind, LibraryView.COLLECTIONS),
                            ) {
                                menuFor = null
                                viewModel.navigate(Route.Library(kind, LibraryView.COLLECTIONS))
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
    is Route.Person -> "person:${route.serverBase}:${route.id}"
    is Route.Playlist -> "playlist:${route.serverBase}:${route.ratingKey}"
    is Route.Live -> "live"
    is Route.Search -> "search"
    is Route.Settings -> "settings"
}

/** Whether the current screen has anything focusable in it yet. */
private fun contentReady(state: ReelyState): Boolean = when (val route = state.route) {
    is Route.Home -> !state.plex.isConnected || !state.home.isEmpty
    is Route.Library -> !state.plex.isConnected || when (route.view) {
        LibraryView.COLLECTIONS -> !state.plex.browseFor(route.kind).collections.isNullOrEmpty()
        else -> state.plex.browseFor(route.kind).items.isNotEmpty()
    }
    is Route.Detail -> state.detail?.detail != null
    // Something to put the cursor on: a title, or nothing at all once it's known there are none.
    is Route.Person -> state.person?.let { !it.busy } == true
    is Route.Playlist -> state.playlist?.let { !it.busy } == true
    is Route.Live -> true
    is Route.Search -> true
    is Route.Settings -> true
}

@Composable
internal fun TopBar(
    current: Route,
    onNavigate: (Route) -> Unit,
    onActivate: (Route) -> Unit,
    onTabFocused: () -> Unit,
    onTabPositioned: (Route, Int) -> Unit,
    tabFocus: List<FocusRequester>,
    settingsFocus: FocusRequester,
    canSelectOnFocus: () -> Boolean,
    serverName: String?,
    /** Whose profile is in use, shown in place of the server's name when known. */
    profile: tv.reely.plex.PlexHomeUser? = null,
    /** Opens "Who's watching?"; null when there is nobody else to switch to. */
    onProfile: (() -> Unit)? = null,
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
            fontSize = 22.sp,
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

        if (profile != null) {
            ProfileChip(
                user = profile,
                onClick = onProfile,
                onFocused = onTabFocused,
                modifier = Modifier.padding(end = 6.dp),
            )
        } else if (serverName != null) {
            Text(
                text = serverName,
                color = Faint,
                fontSize = 14.sp,
                lineHeight = 18.sp,
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

/**
 * The profile in use, top right as Plex has it: a picture and a name. With others in the
 * Plex Home to switch to it can be selected, and opens "Who's watching?"; with nobody
 * else it only says whose this is, and focus passes it by.
 */
@Composable
private fun ProfileChip(
    user: tv.reely.plex.PlexHomeUser,
    onClick: (() -> Unit)?,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = pillColors(focused)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onFocused()
                        }
                        .background(if (focused) colors.fill else Color.Transparent)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tv.reely.ui.screens.Avatar(user, size = 28.dp)
        Text(
            text = user.title,
            color = if (focused) colors.text else Muted,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
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
            /*
             * A white pill means focus, here as everywhere. The page you are on gets a
             * different shape entirely — bold text over a short coral line — because a
             * dimmer pill for it was read as focus: same shape, so the eye took it for
             * the same thing.
             */
            .drawBehind {
                if (selected && !focused) {
                    val w = 18.dp.toPx(); val h = 3.dp.toPx()
                    drawRoundRect(
                        color = Accent,
                        topLeft = Offset((size.width - w) / 2, size.height - h - 2.dp.toPx()),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2, h / 2),
                    )
                }
            }
            .clip(RoundedCornerShape(99.dp))
            .background(if (focused) Chalk else Color.Transparent)
            .clickable(onClick = onActivate)
            .padding(horizontal = if (icon == TabIcon.NONE) 18.dp else 13.dp, vertical = 9.dp),
    ) {
        val tint = when {
            focused -> Ink
            selected -> Chalk
            else -> Muted
        }
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

/** Asked before the application closes, which a single press of back should not do. */
@Composable
private fun ConfirmExit(
    onLeave: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stay = remember { FocusRequester() }
    LaunchedEffect(Unit) { stay.requestWhenReady() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.copy(alpha = 0.97f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(24.dp)
            // Nothing leaves this while it is up. A question with two answers should not
            // be escapable by pressing a direction key at the screen behind it.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Close Reely?",
            color = Chalk,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvActionButton(
                label = "Stay",
                onClick = onStay,
                emphasised = true,
                modifier = Modifier.focusRequester(stay),
            )
            TvActionButton(label = "Close", onClick = onLeave)
        }
    }
}

/**
 * The area under the tab row that every page is drawn in.
 *
 * Up is the only way out of a page, and it goes to that page's own tab. Any other
 * direction off the edge of a page used to carry on to whatever lay that way on screen,
 * and the only thing there is the tab row above: right from a checkbox in Settings landed
 * on TV Shows, which a tab takes as being chosen, so the screen changed under a press
 * meant to do nothing.
 *
 * The properties come before the group, which is what they apply to. Placed after it,
 * they reached past it to every page's own groups instead, so moving left from Settings'
 * options to its sections counted as leaving sideways, and was cancelled.
 */
internal fun Modifier.pageArea(upTo: FocusRequester): Modifier = this
    .focusProperties {
        onExit = {
            if (requestedFocusDirection == FocusDirection.Up) upTo.requestFocus()
            else cancelFocusChange()
        }
    }
    .focusGroup()

/** How long nothing may have the cursor before it is put back. See the net in ReelyApp. */
private const val FOCUS_RESCUE_DELAY_MS = 120L
