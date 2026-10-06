package tv.reely.ui.touch

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.xtream.sourceTag

/** Someone from a cast: what else they're in. */
@Composable
internal fun TouchPerson(viewModel: ReelyViewModel, state: ReelyState, route: Route.Person, actions: TouchActions) {
    val person = state.person?.takeIf { it.route == route }
    TitleGrid(
        title = route.name,
        busy = person?.busy != false,
        error = person?.error,
        empty = "Nothing else with ${route.name} in your libraries.",
        items = person?.items.orEmpty(),
        onBack = viewModel::goBack,
        actions = actions,
    )
}

/** A playlist: what's in it, to play from the top or shuffled. */
@Composable
internal fun TouchPlaylist(viewModel: ReelyViewModel, state: ReelyState, route: Route.Playlist, actions: TouchActions) {
    val playlist = state.playlist?.takeIf { it.route == route }
    TitleGrid(
        title = route.title,
        busy = playlist?.busy != false,
        error = playlist?.error,
        empty = "This playlist is empty.",
        items = playlist?.items.orEmpty(),
        onBack = viewModel::goBack,
        actions = actions,
        header = {
            if (playlist?.items?.any { it.isPlayable } == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    TouchPrimaryButton("Play", { viewModel.playPlaylist(shuffle = false) }, icon = { tv.reely.ui.components.PlayGlyph(it, 18.dp) })
                    TouchSecondaryButton("Shuffle", { viewModel.playPlaylist(shuffle = true) })
                }
            }
        },
    )
}

@Composable
private fun TitleGrid(
    title: String,
    busy: Boolean,
    error: String?,
    empty: String,
    items: List<tv.reely.plex.PlexItem>,
    onBack: () -> Unit,
    actions: TouchActions,
    header: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TouchPageBar(title, onBack)
        LazyVerticalGrid(
            columns = touchPosterColumns(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = TouchMargin, end = TouchMargin, top = 8.dp, bottom = 24.dp + barSpace()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    header()
                    error?.let { TouchError(it, modifier = Modifier.padding(0.dp)) }
                    if (busy && items.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                    } else if (items.isEmpty() && error == null) {
                        TouchNote(empty)
                    }
                }
            }
            items(items, key = { it.listKey }) { item ->
                TouchPoster(
                    title = item.rowTitle,
                    subtitle = item.caption,
                    imageUrl = actions.image(item.serverBase, item.grandparentThumb.takeIf { item.type == "episode" } ?: item.thumb, 300, 450),
                    progress = item.resumeFraction,
                    watched = item.isWatched,
                    tag = item.sourceTag,
                    width = null,
                    onClick = { actions.open(item) },
                    onLongPress = { actions.hold(item) },
                )
            }
        }
    }
}

/** Search: everything the television's finds, on a phone's keyboard. */
@Composable
internal fun TouchSearch(viewModel: ReelyViewModel, state: ReelyState, actions: TouchActions) {
    val search = state.search
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = TouchMargin, top = 4.dp)) {
            HeaderButton(onClick = viewModel::goBack) { tv.reely.ui.components.ArrowGlyph(it, left = true, size = 22.dp) }
            OutlinedTextField(
                value = search.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Movies, shows, people, channels") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Chalk,
                    unfocusedBorderColor = Line,
                    cursorColor = Accent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + barSpace()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (search.query.isBlank()) {
                if (search.recent.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = TouchMargin), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleMedium, color = Chalk, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearRecentSearches) { Text("Clear", color = Muted) }
                        }
                    }
                    items(search.recent, key = { "recent:$it" }) { recent ->
                        TouchListRow(recent, onClick = { viewModel.setQuery(recent) })
                    }
                }
                return@LazyColumn
            }
            if (search.busy && search.results.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            }
            if (search.unreachable) item { TouchError("Couldn't reach your Plex server to search it.") }
            val peopleRow: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                if (search.people.isNotEmpty()) item {
                    TouchRow("People") {
                        items(search.people, key = { "p:" + it.id + it.name }) { person ->
                            Column(
                                modifier = Modifier
                                    .width(84.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .combinedClickableCompat {
                                        viewModel.rememberSearch()
                                        viewModel.navigate(Route.Person(person.id, person.name, person.thumb, person.serverBase))
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(Modifier.size(72.dp).clip(CircleShape).background(SurfaceHigh), contentAlignment = Alignment.Center) {
                                    val picture = actions.image(person.serverBase, person.thumb, 150, 150)
                                    if (picture != null) AsyncImage(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Text(person.name.take(1), color = Muted)
                                }
                                Text(person.name, style = MaterialTheme.typography.labelMedium, color = Chalk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (search.peopleFirst) peopleRow()
            if (search.results.isNotEmpty()) item {
                TouchRow("Results") {
                    items(search.results, key = { it.listKey }) { item ->
                        TouchPoster(
                            title = item.rowTitle,
                            subtitle = if (item.type == "episode") item.title else item.caption,
                            imageUrl = actions.image(item.serverBase, item.grandparentThumb.takeIf { item.type == "episode" } ?: item.thumb, 300, 450),
                            progress = item.resumeFraction,
                            watched = item.isWatched,
                            tag = item.sourceTag,
                            onClick = { viewModel.rememberSearch(); actions.open(item) },
                            onLongPress = { actions.hold(item) },
                        )
                    }
                }
            }
            if (search.channels.isNotEmpty()) item {
                TouchRow("Channels") {
                    items(search.channels, key = { "ch:" + it.streamId }) { channel ->
                        Column(
                            modifier = Modifier
                                .width(120.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickableCompat { viewModel.rememberSearch(); viewModel.playSearchChannel(channel) },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().size(68.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (channel.icon != null) {
                                    AsyncImage(channel.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
                                } else {
                                    Text(channel.name.take(2), color = Muted)
                                }
                            }
                            Text(channel.name, style = MaterialTheme.typography.bodySmall, color = Chalk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (search.collections.isNotEmpty()) item { Rowed("Collections", search.collections, actions) }
            if (search.more.isNotEmpty()) item { Rowed("More results", search.more, actions) }
            if (!search.peopleFirst) peopleRow()
            if (!search.busy && !search.unreachable && search.results.isEmpty() && search.channels.isEmpty() &&
                search.people.isEmpty() && search.collections.isEmpty() && search.more.isEmpty()
            ) {
                item { TouchNote("Nothing found for \"${search.query}\".") }
            }
        }
    }
}
