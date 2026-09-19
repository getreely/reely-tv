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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.screens.HomeScreen
import tv.reely.ui.screens.LibraryScreen
import tv.reely.ui.screens.LiveScreen
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.screens.StatusScreen
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment

private data class Destination(val label: String, val route: Route)

private val destinations = listOf(
    Destination("Home", Route.Home),
    Destination("Movies", Route.Library(LibraryKind.MOVIES)),
    Destination("TV Shows", Route.Library(LibraryKind.SHOWS)),
    Destination("Live TV", Route.Live),
    Destination("Status", Route.Status),
)

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
    val firstTab = remember { FocusRequester() }
    // The tab row picks a destination as soon as it is focused, which is the television
    // convention. The cost is that focus must never land there by accident: when a screen
    // replaces itself, the focused node goes with it and focus would fall back onto the
    // tabs, silently navigating somewhere nobody asked for. So whenever the tab row does
    // not own focus, focus is put back into the content as soon as it has something in it.
    var tabRowHasFocus by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }
    LaunchedEffect(routeKey(state.route), contentReady(state)) {
        if (!tabRowHasFocus && contentReady(state)) {
            runCatching { contentFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        TopBar(
            current = state.stack.first(),
            onSelect = viewModel::navigate,
            onTabFocused = { tabRowHasFocus = true },
            firstTab = firstTab,
            serverName = state.plex.serverName,
        )

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))

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
                .onFocusChanged { if (it.hasFocus) tabRowHasFocus = false },
        ) {
        when (val route = state.route) {
            is Route.Home -> HomeScreen(
                plex = state.plex,
                home = state.home,
                imageUrl = viewModel::plexImageUrl,
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
                imageUrl = viewModel::plexImageUrl,
                onPlay = { viewModel.play(it) },
                onOpenDetail = { viewModel.navigate(Route.Detail(it)) },
                onStartLink = viewModel::startPlexLink,
                onCancelLink = viewModel::cancelPlexLink,
                onDismissPlexError = viewModel::dismissPlexError,
                onSelectSection = { viewModel.openSection(route.kind, it) },
                onDismissBrowseError = { viewModel.dismissBrowseError(route.kind) },
            )

            is Route.Detail -> {
                val detail = state.detail
                if (detail == null) {
                    EmptyNote("Loading…", modifier = Modifier.padding(40.dp))
                } else {
                    DetailScreen(
                        state = detail,
                        imageUrl = viewModel::plexImageUrl,
                        onPlay = { viewModel.play(it, queue = detail.episodes) },
                        onPlayDetail = { viewModel.playFromDetail() },
                        onSelectSeason = viewModel::selectSeason,
                        onBack = viewModel::goBack,
                    )
                }
            }

            is Route.Live -> LiveScreen(
                live = state.live,
                onSignIn = viewModel::signInXtream,
                onSelectCategory = viewModel::openCategory,
                onFocusChannel = viewModel::focusChannel,
                onPlayChannel = viewModel::playChannel,
                onDismissError = viewModel::dismissLiveError,
            )

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
    is Route.Status -> "status"
}

/** Whether the current screen has anything focusable in it yet. */
private fun contentReady(state: ReelyState): Boolean = when (val route = state.route) {
    is Route.Home -> !state.plex.isConnected || !state.home.isEmpty
    is Route.Library -> !state.plex.isConnected || state.plex.browseFor(route.kind).items.isNotEmpty()
    is Route.Detail -> state.detail?.detail != null
    is Route.Live -> true
    is Route.Status -> true
}

@Composable
private fun TopBar(
    current: Route,
    onSelect: (Route) -> Unit,
    onTabFocused: () -> Unit,
    firstTab: FocusRequester,
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
            NavTab(
                label = destination.label,
                selected = destination.route == current,
                onSelect = { onSelect(destination.route) },
                onFocused = onTabFocused,
                modifier = if (index == 0) Modifier.focusRequester(firstTab) else Modifier,
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
    onSelect: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    onSelect()
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
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = when {
                selected -> Parchment
                focused -> Parchment
                else -> Muted
            },
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
