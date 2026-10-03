package tv.reely.ui.touch

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.reely.ui.HomeRow
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.screens.episodeLine
import tv.reely.ui.screens.interleave
import tv.reely.ui.screens.posterArt
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceRaised
import tv.reely.xtream.sourceTag

/** Home: the same rows as the television's, in the same order, switched off the same way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchHome(viewModel: ReelyViewModel, state: ReelyState, actions: TouchActions) {
    if (!state.plex.isConnected) {
        Column(Modifier.fillMaxSize()) {
            TouchHeader("Home", actions)
            TouchSignIn(viewModel, state)
        }
        return
    }
    val home = state.home
    val hidden = state.prefs.hiddenHomeRows
    fun shown(row: HomeRow) = row.id !in hidden

    PullToRefreshBox(isRefreshing = home.busy && !home.isEmpty, onRefresh = viewModel::refreshHome) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item { TouchHeader("Home", actions) }
            home.error?.let { error -> item { TouchError(error) } }

            state.requests.ready.firstOrNull()?.let { arrived ->
                item(key = "ready") {
                    ReadyBanner(
                        title = arrived.title,
                        more = state.requests.ready.size - 1,
                        onWatch = { viewModel.openReady(arrived) },
                        onDismiss = { viewModel.dismissReady(arrived) },
                    )
                }
            }

            if (home.continueWatching.isNotEmpty() && shown(HomeRow.CONTINUE)) item(key = HomeRow.CONTINUE.id) {
                TouchRow(HomeRow.CONTINUE.title) {
                    items(home.continueWatching, key = { it.listKey }) { item ->
                        TouchWide(
                            title = item.rowTitle,
                            subtitle = episodeLine(item),
                            imageUrl = actions.image(item.serverBase, item.art ?: posterArt(item), 480, 270),
                            progress = item.resumeFraction,
                            watched = item.isWatched,
                            tag = item.sourceTag,
                            onClick = { viewModel.play(item) },
                            onLongPress = { actions.hold(item) },
                        )
                    }
                }
            }

            if (home.recentEpisodes.isNotEmpty() && shown(HomeRow.EPISODES)) item(key = HomeRow.EPISODES.id) {
                TouchRow(HomeRow.EPISODES.title) {
                    items(home.recentEpisodes, key = { it.listKey }) { group ->
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

            listOf(
                HomeRow.MOVIES to home.recentMovies,
                HomeRow.IPTV_MOVIES to home.iptvMovies,
                HomeRow.IPTV_SHOWS to home.iptvShows,
                HomeRow.WATCHLIST to home.watchlist,
            ).forEach { (row, titles) ->
                if (titles.isNotEmpty() && shown(row)) item(key = row.id) {
                    TouchRow(row.title) {
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
            }

            if (home.playlists.isNotEmpty() && shown(HomeRow.PLAYLISTS)) item(key = HomeRow.PLAYLISTS.id) {
                TouchRow(HomeRow.PLAYLISTS.title) {
                    items(home.playlists, key = { it.listKey }) { playlist ->
                        TouchPoster(
                            title = playlist.title,
                            subtitle = playlist.leafCount.let { if (it == 1) "1 item" else "$it items" },
                            imageUrl = actions.image(playlist.serverBase, playlist.thumb, 300, 450),
                            onClick = { actions.open(playlist) },
                        )
                    }
                }
            }

            // From Reely: trending and popular, each opening its page in Requests.
            listOf(
                HomeRow.TRENDING to listOf("movies", "shows"),
                HomeRow.POPULAR to listOf("popularMovies", "popularShows"),
            ).forEach { (row, ids) ->
                val titles = interleave(ids.map { id -> state.requests.shownRows.firstOrNull { it.id == id }?.titles.orEmpty() })
                if (titles.isNotEmpty() && shown(row)) item(key = row.id) {
                    TouchRow(row.title) {
                        items(titles, key = { it.key }) { title ->
                            TouchPoster(
                                title = title.title,
                                subtitle = title.year?.toString(),
                                imageUrl = title.poster,
                                tag = state.requests.badgeFor(title),
                                onClick = { viewModel.navigate(Route.RequestTitle(title)) },
                            )
                        }
                    }
                }
            }

            if (home.isEmpty && home.busy) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            } else if (home.isEmpty) {
                item { TouchNote("Nothing to show yet. Watch something and it will appear here.") }
            }
        }
    }
}

@Composable
private fun ReadyBanner(title: String, more: Int, onWatch: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TouchMargin)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ready to watch", style = MaterialTheme.typography.labelMedium, color = Accent)
        Text(
            if (more > 0) "$title, and $more more you asked for, are here." else "$title, which you asked for, is here.",
            style = MaterialTheme.typography.bodyLarge,
            color = Chalk,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TouchPrimaryButton("Watch", onWatch)
            TouchSecondaryButton("Dismiss", onDismiss)
        }
    }
}

/**
 * Signing in on a phone: Plex's own page opens in the browser, with this phone already
 * named, and the app is signed in when you come back to it. The code is there too, for
 * signing in from another device at plex.tv/link.
 */
@Composable
internal fun TouchSignIn(viewModel: ReelyViewModel, state: ReelyState) {
    val plex = state.plex
    val context = LocalContext.current
    // Opened once per code: coming back to the app mustn't send you back to the browser.
    var openedFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(plex.linkUrl) {
        val url = plex.linkUrl ?: return@LaunchedEffect
        if (openedFor == url) return@LaunchedEffect
        openedFor = url
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TouchMargin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (plex.token != null && plex.linkCode == null) {
            // Signed in, with no server to show yet: the same as the television says.
            Text(
                if (plex.busy) "Finding your Plex server" else "Can't find your Plex server",
                style = MaterialTheme.typography.headlineSmall,
                color = Chalk,
            )
            Text(
                if (plex.busy) "You're signed in. Looking for your server, at home first, then over the internet."
                else "Make sure Plex Media Server is running and signed in to the same account. Reely keeps looking, or try again now.",
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
            )
            plex.error?.takeIf { !plex.busy }?.let { TouchError(it, onDismiss = viewModel::dismissPlexError, modifier = Modifier.padding(horizontal = 0.dp)) }
            if (plex.busy) {
                CircularProgressIndicator(color = Accent)
            } else {
                TouchPrimaryButton("Try again", viewModel::retryConnect, Modifier.fillMaxWidth())
                TouchSecondaryButton("Use a different Plex account", viewModel::signOutPlex, Modifier.fillMaxWidth())
            }
            return@Column
        }

        Text("Sign in to watch your library", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        Text(
            "Your movies and shows from Plex, including libraries shared with you.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
        )
        plex.error?.let { TouchError(it, onDismiss = viewModel::dismissPlexError) }
        val code = plex.linkCode
        if (code == null) {
            TouchPrimaryButton(
                if (plex.busy) "Getting ready…" else "Sign in with Plex",
                viewModel::startPlexLink,
                Modifier.fillMaxWidth(),
                enabled = !plex.busy,
            )
        } else {
            Text("Finish signing in on Plex's page in your browser, then come back here.", style = MaterialTheme.typography.bodyMedium, color = Chalk)
            plex.linkUrl?.let { url ->
                TouchPrimaryButton("Open Plex sign-in", {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.fillMaxWidth())
            }
            Text("Or, on another device, go to plex.tv/link and enter", style = MaterialTheme.typography.bodyMedium, color = Muted)
            Text(code, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Chalk, letterSpacing = 6.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Waiting for you to sign in…", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = viewModel::cancelPlexLink) { Text("Cancel", color = Chalk) }
            }
        }
    }
}
