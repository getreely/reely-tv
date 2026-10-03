package tv.reely.ui.touch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tv.reely.plex.PlexItem
import tv.reely.plex.formatAirDate
import tv.reely.plex.formatDuration
import tv.reely.ui.DetailState
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.components.BookmarkGlyph
import tv.reely.ui.components.CheckGlyph
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.RestartGlyph
import tv.reely.ui.components.TrailerGlyph
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.xtream.sourceTag

/** A title's page: what it is, everything the television's page does with it, and its episodes. */
@Composable
internal fun TouchDetail(viewModel: ReelyViewModel, state: ReelyState, actions: TouchActions) {
    val page = state.detail
    val detail = page?.detail
    if (page == null || detail == null) {
        Column(Modifier.fillMaxSize()) {
            TouchPageBar("", onBack = viewModel::goBack)
            when {
                page?.error != null -> TouchNote(page.error, action = "Try again" to viewModel::retryDetail)
                else -> Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }
        }
        return
    }
    val watchlisted = detail.guid?.let { guid -> state.plex.watchlist?.let { guid in it } }
        ?.takeIf { detail.type == "movie" || detail.type == "show" }
    val isCollection = detail.type == "collection"

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "hero") {
            Box(Modifier.heroHeight()) {
                AsyncImage(
                    model = actions.image(page.serverBase, detail.art ?: detail.thumb, 720, 405),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f), Color.Transparent, Ink)),
                    ),
                )
                Box(Modifier.padding(4.dp)) {
                    HeaderButton(onClick = viewModel::goBack) { tv.reely.ui.components.ArrowGlyph(it, left = true, size = 22.dp) }
                }
            }
        }
        item(key = "about") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TouchMargin),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(detail.title, style = MaterialTheme.typography.headlineMedium, color = Chalk)
                val facts = listOfNotNull(
                    detail.facts.takeIf { it.isNotBlank() },
                    detail.qualities.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                ).joinToString("  ·  ")
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = Muted)
                page.error?.let { TouchError(it, modifier = Modifier.padding(0.dp)) }
                if (!isCollection) Actions(viewModel, page, watchlisted)
                detail.tagline?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Chalk) }
                detail.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
                val credits = listOfNotNull(
                    detail.genres.takeIf { it.isNotEmpty() }?.let { "Genres" to it.joinToString(", ") },
                    detail.directors.takeIf { it.isNotEmpty() }?.let { "Directed by" to it.joinToString(", ") },
                    detail.writers.takeIf { it.isNotEmpty() }?.let { "Written by" to it.joinToString(", ") },
                    formatAirDate(detail.airDate)?.let { (if (detail.isShow) "First aired" else "Released") to it },
                )
                credits.forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = Faint, modifier = Modifier.width(96.dp))
                        Text(value, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }

        if (page.seasons.size > 1) item(key = "seasons") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = TouchMargin, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(page.seasons, key = { it.ratingKey }) { season ->
                    TouchChip(
                        season.title,
                        selected = page.selectedSeason?.ratingKey == season.ratingKey,
                        onClick = { viewModel.selectSeason(season) },
                    )
                }
            }
        }
        if (detail.isShow) {
            if (page.busy && page.episodes.isEmpty()) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            }
            items(page.episodes, key = { "e:" + it.ratingKey }) { episode ->
                EpisodeRow(
                    episode = episode,
                    imageUrl = actions.image(episode.serverBase ?: page.serverBase, episode.thumb, 400, 225),
                    current = page.focusedEpisode?.ratingKey == episode.ratingKey,
                    onPlay = { viewModel.play(episode, queue = page.episodes) },
                    onHold = { actions.hold(episode) },
                )
            }
        }

        if (isCollection && page.members.isNotEmpty()) item(key = "members") {
            Rowed("In this collection", page.members, actions)
        }
        if (detail.roles.isNotEmpty()) item(key = "cast") {
            TouchRow("Cast", modifier = Modifier.padding(top = 20.dp)) {
                items(detail.roles, key = { it.name + (it.role ?: "") }) { role ->
                    Column(
                        modifier = Modifier
                            .width(84.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (role.id != null) Modifier.combinedClickableCompat {
                                    viewModel.navigate(Route.Person(role.id, role.name, role.thumb, page.serverBase))
                                } else Modifier
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(SurfaceHigh), contentAlignment = Alignment.Center) {
                            val picture = actions.image(page.serverBase, role.thumb, 150, 150)
                            if (picture != null) {
                                AsyncImage(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(role.name.take(1), style = MaterialTheme.typography.titleLarge, color = Muted)
                            }
                        }
                        Text(role.name, style = MaterialTheme.typography.labelMedium, color = Chalk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        role.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        if (page.related.isNotEmpty()) item(key = "related") {
            Rowed("More like this", page.related, actions, Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun Actions(viewModel: ReelyViewModel, page: DetailState, watchlisted: Boolean?) {
    val detail = page.detail ?: return
    /*
     * As on the television: a show's page is about one episode — the one it was opened
     * on, else the one you're up to — and Play, Restart and Watched are about that one.
     * Without it, Play went to the season's first episode however far through you were.
     */
    val target = page.focusedEpisode?.takeIf { detail.isShow }
    val resumeFrom = when {
        target != null -> target.viewOffsetMs
        detail.isShow -> page.episodes.firstOrNull { it.resumeFraction != null }?.viewOffsetMs ?: 0L
        else -> detail.viewOffsetMs
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        target?.let { episode ->
            Text(
                listOfNotNull(
                    if (resumeFrom > 0) "Continue" else "Up next",
                    listOfNotNull(episode.parentIndex?.let { "S$it" }, episode.index?.let { "E$it" }).joinToString(" · ").ifEmpty { null },
                    episode.title,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Chalk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TouchPrimaryButton(
            label = if (resumeFrom > 0) "Resume" else "Play",
            onClick = { if (target != null) viewModel.play(target, queue = page.episodes) else viewModel.playFromDetail() },
            // A finger's width on a phone; not a bar across a tablet.
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            icon = { PlayGlyph(it, 18.dp) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (resumeFrom > 0) {
                TouchIconAction("Restart", {
                    if (target != null) viewModel.play(target, queue = page.episodes, resume = false)
                    else viewModel.playFromDetail(resume = false)
                }, { RestartGlyph(it, 20.dp) })
            }
            // The show as a whole is marked from its menu; here, as on the television, the episode.
            val watched = target?.isWatched ?: detail.isWatched
            TouchIconAction(if (watched) "Unwatch" else "Watched", {
                if (target != null) viewModel.toggleWatched(target) else viewModel.toggleWatchedDetail()
            }, { CheckGlyph(it, 20.dp) })
            if (watchlisted != null) {
                TouchIconAction("Watchlist", viewModel::toggleWatchlist, { BookmarkGlyph(it, filled = watchlisted, size = 20.dp) })
            }
            if (page.trailers.isNotEmpty()) {
                TouchIconAction("Trailer", viewModel::playTrailer, { TrailerGlyph(it, 20.dp) })
            }
            if (detail.versions.size > 1 && !detail.isShow) {
                var open by remember { mutableStateOf(false) }
                val chosen = detail.versions.getOrNull(page.versionIndex) ?: detail.versions.first()
                Box {
                    TouchIconAction("Quality", { open = true }, { color ->
                        Text(chosen.label.substringBefore(' '), color = color, style = MaterialTheme.typography.labelMedium)
                    })
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        detail.versions.forEachIndexed { index, version ->
                            DropdownMenuItem(
                                text = { Text(listOfNotNull(version.label, version.detail).joinToString(" · ")) },
                                onClick = { open = false; viewModel.selectVersion(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(episode: PlexItem, imageUrl: String?, current: Boolean, onPlay: () -> Unit, onHold: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onHold)
            .background(if (current) SurfaceHigh else Color.Transparent)
            .padding(horizontal = TouchMargin, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(150.dp)) {
            Artwork(imageUrl, ratio = 16f / 9f, progress = episode.resumeFraction, watched = episode.isWatched, tag = episode.sourceTag)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(episode.index?.let { "$it." }, episode.title).joinToString(" "),
                style = MaterialTheme.typography.bodyLarge,
                color = Chalk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(formatDuration(episode.durationMs).takeIf { it.isNotEmpty() }, formatAirDate(episode.airDate)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            episode.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun Rowed(title: String, titles: List<PlexItem>, actions: TouchActions, modifier: Modifier = Modifier) {
    TouchRow(title, modifier = modifier) {
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

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)
