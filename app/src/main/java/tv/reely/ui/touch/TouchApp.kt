package tv.reely.ui.touch

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import tv.reely.plex.PlexItem
import tv.reely.ui.BROWSE_REFRESH_MS
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibraryView
import tv.reely.ui.PlayerHost
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.detailRouteFor
import tv.reely.ui.screens.itemMenuActions
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceRaised

/** The bottom bar's places. Search and Settings are up in each one's corner. */
internal enum class TouchTab(val label: String, val route: Route) {
    HOME("Home", Route.Home),
    MOVIES("Movies", Route.Library(LibraryKind.MOVIES)),
    SHOWS("TV Shows", Route.Library(LibraryKind.SHOWS)),
    LIVE("Live TV", Route.Live),
    REQUESTS("Requests", Route.Requests),
}

internal fun TouchTab.matches(route: Route): Boolean = when (this) {
    TouchTab.HOME -> route is Route.Home
    TouchTab.MOVIES -> route is Route.Library && route.kind == LibraryKind.MOVIES
    TouchTab.SHOWS -> route is Route.Library && route.kind == LibraryKind.SHOWS
    TouchTab.LIVE -> route is Route.Live
    TouchTab.REQUESTS -> route is Route.Requests || route is Route.RequestTitle
}

/**
 * Reely on a phone or tablet. The same app underneath — the same account, libraries,
 * player and everything kept — with screens laid out for touch: a bottom bar, pages that
 * scroll, a press to open and a hold for a title's menu. The television's screens are
 * left exactly as they are.
 */
@Composable
fun TouchApp(
    viewModel: ReelyViewModel = viewModel(),
    /** Where pictures come from; the server's, unless a test has its own. */
    imageUrl: ((String?, String?, Int, Int) -> String?)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    tv.reely.ui.screens.InstallerLauncher(
        status = state.update,
        onHandled = viewModel::installerRequestHandled,
        onRetry = viewModel::openInstaller,
        onDidNotOpen = viewModel::installerDidNotOpen,
    )
    val livePlayers = remember { listOf(viewModel.livePlayer.player) }
    tv.reely.ui.components.FollowAudioOutput(livePlayers, state.prefs.audioOutput)

    // As on the television: nothing plays on behind the app, and coming back picks up
    // whatever changed meanwhile.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.silenceTheme()
                viewModel.livePlayer.park()
                wasStopped = true
            }
            if (event == Lifecycle.Event.ON_START && wasStopped) {
                viewModel.livePlayer.unpark()
                viewModel.dropStaleReminder()
                viewModel.refreshVisible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BROWSE_REFRESH_MS)
            viewModel.refreshVisible()
        }
    }

    // "Who's watching?": from the header, and once after signing in to a Plex Home.
    var pickingProfile by remember { mutableStateOf(false) }
    LaunchedEffect(state.plex.askWho) {
        if (state.plex.askWho) {
            pickingProfile = true
            viewModel.askedWho()
        }
    }
    var wasSwitching by remember { mutableStateOf(false) }
    LaunchedEffect(state.plex.switchingTo, state.plex.switchError) {
        if (state.plex.switchingTo != null) wasSwitching = true
        else if (wasSwitching) {
            wasSwitching = false
            if (state.plex.switchError == null) pickingProfile = false
        }
    }

    val playing = state.playback != null
    WatchingFullScreen(playing)
    if (playing) {
        PlayerHost(viewModel, state, touch = true)
        return
    }

    if (pickingProfile) {
        TouchTheme {
            TouchProfilePicker(
                users = state.plex.homeUsers,
                current = state.plex.user,
                switchingTo = state.plex.switchingTo,
                error = state.plex.switchError,
                onPick = viewModel::switchUser,
                onDismissError = viewModel::dismissSwitchError,
                onClose = { pickingProfile = false },
            )
        }
        return
    }

    // Back walks back through pages, then to Home; from Home it leaves, as phones do.
    BackHandler(enabled = state.stack.size > 1 || state.route !is Route.Home) {
        if (state.stack.size > 1) viewModel.goBack() else viewModel.navigate(Route.Home)
    }

    var menuFor by remember { mutableStateOf<PlexItem?>(null) }
    val actions = TouchActions(
        open = { item ->
            viewModel.navigate(
                if (item.type == "playlist") Route.Playlist(item.ratingKey, item.title, item.serverBase)
                else detailRouteFor(item)
            )
        },
        hold = { menuFor = it },
        image = imageUrl ?: { base, path, w, h -> viewModel.plexImageUrl(base, path, w, h) },
        search = { viewModel.navigate(Route.Search) },
        settings = { viewModel.navigate(Route.Settings) },
        profile = if (state.plex.canSwitchUser) ({ pickingProfile = true }) else null,
    )

    TouchTheme {
        // A phone held sideways has the height for little but what it's showing: the tabs
        // go down the side instead of across the bottom.
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val sideways = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenHeightDp < 560
        Scaffold(
            containerColor = Ink,
            // The app draws edge to edge: clear of the bars and of a camera in the screen's
            // edge, and above the keyboard while one is up.
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
            bottomBar = { if (!sideways) TouchNavBar(state, onSelect = { viewModel.navigate(it.route) }) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (sideways) TouchNavRail(state, onSelect = { viewModel.navigate(it.route) })
                Box(Modifier.weight(1f).fillMaxSize()) {
                    TouchContent(viewModel, state, actions)
                    // Inside the bar's padding, so it sits above the tabs rather than on them.
                    state.dueReminder?.let { due ->
                        TouchReminder(
                            reminder = due,
                            onWatch = viewModel::watchReminder,
                            onDismiss = viewModel::dismissReminder,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        )
                    }
                }
            }
        }

        menuFor?.let { item ->
            TouchActionSheet(
                title = if (item.type == "episode") item.title else item.rowTitle,
                subtitle = item.grandparentTitle.takeIf { item.type == "episode" } ?: item.caption,
                actions = itemMenuActions(
                    item = item,
                    onPlay = { resume -> viewModel.play(item, resume = resume) },
                    onToggleWatched = { viewModel.toggleWatched(item) },
                    onDetails = { actions.open(item) },
                    onRemoveFromContinueWatching = { viewModel.removeFromContinueWatching(item) }
                        .takeIf { state.home.continueWatching.any { it.listKey == item.listKey } },
                    onPlayNext = { viewModel.playNextEpisode(item) },
                ),
                onDismiss = { menuFor = null },
            )
        }

        if (state.updatePrompt) {
            TouchUpdatePrompt(
                status = state.update,
                onUpdate = viewModel::installUpdate,
                onInstall = viewModel::openInstaller,
                onLater = viewModel::dismissUpdatePrompt,
            )
        }
    }
}

/** What every screen needs to hand on: opening a title, its menu, pictures, the corner buttons. */
internal class TouchActions(
    val open: (PlexItem) -> Unit,
    val hold: (PlexItem) -> Unit,
    val image: (String?, String?, Int, Int) -> String?,
    val search: () -> Unit,
    val settings: () -> Unit,
    val profile: (() -> Unit)?,
)

@Composable
private fun TouchNavBar(state: ReelyState, onSelect: (TouchTab) -> Unit) {
    val top = state.stack.first()
    NavigationBar(containerColor = SurfaceRaised) {
        TouchTab.entries.forEach { tab ->
            val selected = tab.matches(top)
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    val color = if (selected) Ink else Muted
                    when (tab) {
                        TouchTab.HOME -> HomeTabGlyph(color)
                        TouchTab.MOVIES -> FilmTabGlyph(color)
                        TouchTab.SHOWS -> ShowTabGlyph(color)
                        TouchTab.LIVE -> LiveTabGlyph(color)
                        TouchTab.REQUESTS -> RequestTabGlyph(color)
                    }
                },
                label = { Text(tab.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Chalk,
                    selectedTextColor = Chalk,
                    unselectedTextColor = Muted,
                ),
            )
        }
    }
}

@Composable
private fun TouchNavRail(state: ReelyState, onSelect: (TouchTab) -> Unit) {
    val top = state.stack.first()
    androidx.compose.material3.NavigationRail(containerColor = SurfaceRaised) {
        TouchTab.entries.forEach { tab ->
            val selected = tab.matches(top)
            androidx.compose.material3.NavigationRailItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { tabGlyph(tab, if (selected) Ink else Muted) },
                label = { Text(tab.label, maxLines = 1) },
                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                    indicatorColor = Chalk,
                    selectedTextColor = Chalk,
                    unselectedTextColor = Muted,
                ),
            )
        }
    }
}

@Composable
private fun tabGlyph(tab: TouchTab, color: Color) = when (tab) {
    TouchTab.HOME -> HomeTabGlyph(color)
    TouchTab.MOVIES -> FilmTabGlyph(color)
    TouchTab.SHOWS -> ShowTabGlyph(color)
    TouchTab.LIVE -> LiveTabGlyph(color)
    TouchTab.REQUESTS -> RequestTabGlyph(color)
}

@Composable
private fun TouchContent(viewModel: ReelyViewModel, state: ReelyState, actions: TouchActions) {
    when (val route = state.route) {
        is Route.Home -> TouchHome(viewModel, state, actions)
        is Route.Library -> TouchLibrary(viewModel, state, route, actions)
        is Route.Detail -> TouchDetail(viewModel, state, actions)
        is Route.Search -> TouchSearch(viewModel, state, actions)
        is Route.Live -> TouchLive(viewModel, state, actions)
        is Route.Requests -> TouchRequests(viewModel, state, actions)
        is Route.RequestTitle -> TouchRequestTitle(viewModel, state, route)
        is Route.Playlist -> TouchPlaylist(viewModel, state, route, actions)
        is Route.Person -> TouchPerson(viewModel, state, route, actions)
        is Route.Settings -> TouchSettings(viewModel, state, onSwitchProfile = actions.profile ?: {})
    }
}

/**
 * Something playing takes the whole screen, sideways, with the system's bars out of the
 * way; leaving the player gives the phone back as it was.
 */
@Composable
private fun WatchingFullScreen(watching: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(watching) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (watching) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

/** The top of a tab: its name, and Search, the profile and Settings in the corner. */
@Composable
internal fun TouchHeader(title: String, actions: TouchActions, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = TouchMargin, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Chalk, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HeaderButton(onClick = actions.search) { tv.reely.ui.components.SearchGlyph(it, 22.dp) }
            actions.profile?.let { onProfile ->
                HeaderButton(onClick = onProfile) { color ->
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                        Text("☺", color = Ink, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HeaderButton(onClick = actions.settings) { tv.reely.ui.components.GearGlyph(it, 22.dp) }
        }
    }
}

@Composable
internal fun HeaderButton(onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph(Chalk) }
}

/** A page's top: back, and its title. */
@Composable
internal fun TouchPageBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderButton(onClick = onBack) { tv.reely.ui.components.ArrowGlyph(it, left = true, size = 22.dp) }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Chalk,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        trailing?.invoke()
    }
}
