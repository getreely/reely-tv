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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.SearchGlyph
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.screens.GuideScreen
import tv.reely.ui.screens.HomeScreen
import tv.reely.ui.screens.LiveCategoriesScreen
import tv.reely.ui.screens.SearchScreen
import tv.reely.ui.screens.LibraryScreen
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.screens.StatusScreen
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment

private data class Destination(val label: String, val route: Route, val isSearch: Boolean = false)

private val destinations = listOf(
    Destination("Search", Route.Search, isSearch = true),
    Destination("Home", Route.Home),
    Destination("Movies", Route.Library(LibraryKind.MOVIES)),
    Destination("TV Shows", Route.Library(LibraryKind.SHOWS)),
    Destination("Live TV", Route.Live),
    Destination("Status", Route.Status),
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReelyApp(viewModel: ReelyViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    val playback = state.playback
    if (playback != null) {
        PlayerScreen(
            playback = playback,
            prefs = state.prefs,
            upNext = state.upNext,
            onExit = viewModel::stopPlayback,
            onEnded = viewModel::onPlaybackEnded,
            onPlayUpNext = viewModel::playUpNext,
            onDismissUpNext = viewModel::dismissUpNext,
            onStepChannel = viewModel::stepChannel,
            onStepEpisode = viewModel::stepEpisode,
            onToggleFormat = viewModel::toggleFormat,
            onReportProgress = viewModel::reportProgress,
            onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
            onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Back walks the stack: out of a season, off a detail page, and only then out of the app.
    BackHandler(enabled = state.stack.size > 1) { viewModel.goBack() }

    val contentFocus = remember { FocusRequester() }
    // Always attached to whichever tab is currently selected, so leaving the content
    // upwards returns to the tab you are actually on rather than the nearest one.
    val selectedTab = remember { FocusRequester() }
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

    LaunchedEffect(Unit) { runCatching { selectedTab.requestFocus() } }
    LaunchedEffect(routeKey(state.route), contentReady(state)) {
        if (!tabRowHasFocus && contentReady(state)) {
            runCatching { contentFocus.requestFocus() }
        }
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
            onSelect = viewModel::navigate,
            onTabFocused = { tabRowHasFocus = true },
            selectedTab = selectedTab,
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
                        if (direction == FocusDirection.Up) selectedTab else FocusRequester.Default
                    }
                }
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
                onPlay = { viewModel.play(it) },
                onOpenDetail = { viewModel.navigate(Route.Detail(it)) },
                onStartLink = viewModel::startPlexLink,
                onCancelLink = viewModel::cancelPlexLink,
                onDismissPlexError = viewModel::dismissPlexError,
            )

            is Route.Library -> LibraryScreen(
                kind = route.kind,
                plex = state.plex,
                home = state.home,
                focused = state.focused,
                imageUrl = viewModel::plexImageUrl,
                backdropUrl = viewModel::plexBackdropUrl,
                onFocusItem = viewModel::focusItem,
                onPlay = { viewModel.play(it) },
                onOpenDetail = { viewModel.navigate(Route.Detail(it)) },
                onStartLink = viewModel::startPlexLink,
                onCancelLink = viewModel::cancelPlexLink,
                onDismissPlexError = viewModel::dismissPlexError,
                onSelectSection = { viewModel.openSection(route.kind, it) },
                onDismissBrowseError = { viewModel.dismissBrowseError(route.kind) },
            )

            is Route.Search -> SearchScreen(
                search = state.search,
                focused = state.focused,
                imageUrl = viewModel::plexImageUrl,
                backdropUrl = viewModel::plexBackdropUrl,
                onQueryChange = viewModel::setQuery,
                onFocusItem = viewModel::focusItem,
                onOpenDetail = { viewModel.navigate(Route.Detail(it)) },
                onPlay = { viewModel.play(it) },
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
                        onPlayDetail = viewModel::playFromDetail,
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
                )
            }

            is Route.Status -> StatusScreen(
                plex = state.plex,
                live = state.live,
                prefs = state.prefs,
                onSignOutPlex = viewModel::signOutPlex,
                onSignOutXtream = viewModel::signOutXtream,
                onToggleFormat = viewModel::toggleFormat,
                onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
                onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
                onNudgeUpNext = viewModel::nudgeUpNextSeconds,
                onToggleGuidePreview = viewModel::toggleGuidePreview,
            )
        }
        }
    }
}

/** Identifies a destination for focus bookkeeping, ignoring data that arrives later. */
private fun routeKey(route: Route): String = when (route) {
    is Route.Home -> "home"
    is Route.Library -> "library:${route.kind}"
    is Route.Detail -> "detail:${route.ratingKey}"
    is Route.Live -> "live"
    is Route.Search -> "search"
    is Route.Status -> "status"
}

/** Whether the current screen has anything focusable in it yet. */
private fun contentReady(state: ReelyState): Boolean = when (val route = state.route) {
    is Route.Home -> !state.plex.isConnected || !state.home.isEmpty
    is Route.Library -> !state.plex.isConnected || state.plex.browseFor(route.kind).items.isNotEmpty()
    is Route.Detail -> state.detail?.detail != null
    is Route.Live -> true
    is Route.Search -> true
    is Route.Status -> true
}

@Composable
private fun TopBar(
    current: Route,
    onSelect: (Route) -> Unit,
    onTabFocused: () -> Unit,
    selectedTab: FocusRequester,
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

        destinations.forEach { destination ->
            val isSelected = destination.route == current
            NavTab(
                label = destination.label,
                selected = isSelected,
                iconOnly = destination.isSearch,
                onSelect = { onSelect(destination.route) },
                onFocused = onTabFocused,
                canSelectOnFocus = canSelectOnFocus,
                modifier = if (isSelected) Modifier.focusRequester(selectedTab) else Modifier,
            )
        }

        Box(modifier = Modifier.weight(1f))

        if (serverName != null) {
            Text(text = serverName, color = Faint, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Arriving at a tab picks it. Focus is placed back into the content elsewhere. */
@Composable
private fun NavTab(
    label: String,
    selected: Boolean,
    iconOnly: Boolean = false,
    onSelect: () -> Unit,
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
                    if (canSelectOnFocus()) onSelect()
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
            .clickable(onClick = onSelect)
            .padding(horizontal = if (iconOnly) 13.dp else 18.dp, vertical = 9.dp),
    ) {
        val tint = if (selected || focused) Parchment else Muted
        if (iconOnly) {
            SearchGlyph(color = tint, size = 20.dp)
        } else {
            Text(
                text = label,
                color = tint,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
