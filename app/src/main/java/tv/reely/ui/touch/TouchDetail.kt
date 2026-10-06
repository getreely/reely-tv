package tv.reely.ui.touch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + barSpace())) {
        item(key = "hero") { DetailHeader(page, actions, onBack = viewModel::goBack) }
        item(key = "about") {
            Column(
                // A finger's width on a phone; not a bar across a tablet.
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = TouchMargin),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                page.error?.let { TouchError(it, modifier = Modifier.padding(0.dp)) }
                if (!isCollection) PlayButton(viewModel, page)
                Story(page)
                if (!isCollection) Actions(viewModel, page, watchlisted)
            }
        }

        if (page.seasons.size > 1) item(key = "seasons") {
            SeasonPicker(page, onPick = viewModel::selectSeason)
        } else if (detail.isShow && page.episodes.isNotEmpty()) item(key = "episodes-title") {
            TouchSectionTitle("Episodes", Modifier.padding(top = 18.dp))
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

/**
 * The top of a title's page, as the iPhone's: the backdrop to the screen's edges, its logo
 * (or name) over the foot of it, what it is and in what quality, and back in a frosted
 * circle in the corner.
 */
@Composable
private fun DetailHeader(page: DetailState, actions: TouchActions, onBack: () -> Unit) {
    val detail = page.detail ?: return
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        AsyncImage(
            model = actions.image(page.serverBase, detail.art ?: detail.thumb, 1280, 720),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Ink.copy(alpha = 0.5f), 0.3f to Color.Transparent, 0.75f to Ink.copy(alpha = 0.7f), 1f to Ink),
            ),
        )
        Box(Modifier.padding(start = TouchMargin, top = 8.dp)) {
            GlassCircle(onClick = onBack, description = "Back") { tv.reely.ui.components.ArrowGlyph(Chalk, left = true, size = 20.dp) }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val logo = actions.logo(page.serverBase, detail.logo)
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = detail.title, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 100.dp))
            } else {
                Text(
                    detail.title, color = Chalk, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val facts = listOfNotNull(
                    detail.year?.toString(),
                    if (detail.isShow && detail.childCount > 0) (if (detail.childCount == 1) "1 season" else "${detail.childCount} seasons") else null,
                    formatDuration(detail.durationMs).takeIf { !detail.isShow && detail.durationMs > 0 },
                    detail.contentRating,
                ).joinToString(" · ")
                if (facts.isNotEmpty()) Text(facts, color = Chalk.copy(alpha = 0.75f), fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                (page.focusedEpisode?.qualities?.takeIf { it.isNotEmpty() } ?: detail.qualities).take(3).forEach { quality ->
                    Text(
                        quality, color = Chalk, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.border(1.dp, Chalk.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * As on the television: a show's page is about one episode — the one it was opened on,
 * else the one you're up to — and Play, Restart and Watched are about that one.
 */
private fun targetOf(page: DetailState): PlexItem? = page.focusedEpisode?.takeIf { page.detail?.isShow == true }

private fun resumeOf(page: DetailState): Long {
    val detail = page.detail ?: return 0
    val target = targetOf(page)
    return when {
        target != null -> target.viewOffsetMs
        detail.isShow -> page.episodes.firstOrNull { it.resumeFraction != null }?.viewOffsetMs ?: 0L
        else -> detail.viewOffsetMs
    }
}

/** One wide Play: the film, or the episode you're up to, from where it was left; and how long is left. */
@Composable
private fun PlayButton(viewModel: ReelyViewModel, page: DetailState) {
    val detail = page.detail ?: return
    val target = targetOf(page)
    val resumeFrom = resumeOf(page)
    val duration = target?.durationMs ?: detail.durationMs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Chalk)
                .clickable { if (target != null) viewModel.play(target, queue = page.episodes) else viewModel.playFromDetail() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayGlyph(Ink, 16.dp)
            Spacer(Modifier.width(10.dp))
            val verb = if (resumeFrom > 0) "Resume" else "Play"
            Text(
                listOfNotNull(verb, target?.let { it.caption ?: it.title }).joinToString(" "),
                color = Ink, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1,
            )
        }
        if (resumeFrom > 0 && duration > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Chalk.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth((resumeFrom.toFloat() / duration).coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Accent))
                }
                Text("${maxOf(1, (duration - resumeFrom) / 60_000)} min left", color = Muted, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
        }
    }
}

/** What it's about: the episode's name, the story (a press opens the rest of it), and who made it. */
@Composable
private fun Story(page: DetailState) {
    val detail = page.detail ?: return
    val episode = targetOf(page)
    var open by remember(detail.ratingKey) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        episode?.let {
            Text(
                listOfNotNull(it.caption, it.title).joinToString(" · "),
                color = Chalk, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        detail.tagline?.takeIf { episode == null }?.let { Text(it, color = Chalk, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        (episode?.summary ?: detail.summary)?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                summary, color = Chalk.copy(alpha = 0.82f), fontFamily = tv.reely.ui.theme.Geist, fontSize = 15.sp, lineHeight = 21.sp,
                maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open },
            )
        }
        val credits = listOfNotNull(
            detail.genres.take(3).joinToString(", ").ifEmpty { null },
            detail.directors.firstOrNull()?.let { "Directed by $it" },
            formatAirDate(detail.airDate)?.takeIf { open }?.let { (if (detail.isShow) "First aired " else "Released ") + it },
        )
        if (credits.isNotEmpty()) Text(credits.joinToString(" · "), color = Muted, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 2)
    }
}

/** The rest of what can be done with it: a row of symbols with their names under them. */
@Composable
private fun Actions(viewModel: ReelyViewModel, page: DetailState, watchlisted: Boolean?) {
    val detail = page.detail ?: return
    val target = targetOf(page)
    val resumeFrom = resumeOf(page)
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        if (watchlisted != null) {
            PageAction("Watchlist", viewModel::toggleWatchlist, on = watchlisted) { BookmarkGlyph(it, filled = watchlisted, size = 22.dp) }
        }
        // The show as a whole is marked from its menu; here, as on the television, the episode.
        val watched = target?.isWatched ?: detail.isWatched
        PageAction(if (watched) "Watched" else "Mark watched", {
            if (target != null) viewModel.toggleWatched(target) else viewModel.toggleWatchedDetail()
        }, on = watched) { CheckGlyph(it, 22.dp) }
        if (resumeFrom > 0) {
            PageAction("Restart", {
                if (target != null) viewModel.play(target, queue = page.episodes, resume = false)
                else viewModel.playFromDetail(resume = false)
            }) { RestartGlyph(it, 22.dp) }
        }
        if (page.trailers.isNotEmpty()) {
            PageAction("Trailer", viewModel::playTrailer) { TrailerGlyph(it, 22.dp) }
        }
        if (detail.versions.size > 1 && !detail.isShow) {
            var choosing by remember { mutableStateOf(false) }
            val chosen = detail.versions.getOrNull(page.versionIndex) ?: detail.versions.first()
            Box(Modifier.weight(1f)) {
                PageAction("Quality", { choosing = true }, modifier = Modifier.fillMaxWidth()) { color ->
                    Text(chosen.label.substringBefore(' '), color = color, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    detail.versions.forEachIndexed { index, version ->
                        DropdownMenuItem(
                            text = { Text(listOfNotNull(version.label, version.detail).joinToString(" · ")) },
                            onClick = { choosing = false; viewModel.selectVersion(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.PageAction(label: String, onClick: () -> Unit, on: Boolean = false, glyph: @Composable (Color) -> Unit) =
    PageAction(label, onClick, on, Modifier.weight(1f), glyph)

@Composable
private fun PageAction(label: String, onClick: () -> Unit, on: Boolean = false, modifier: Modifier, glyph: @Composable (Color) -> Unit) {
    val color = if (on) Accent else Chalk
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { glyph(color) }
        Text(label, color = if (on) Accent else Muted, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
    }
}

/** A show's seasons: the one showing, large, opening a list of the rest. */
@Composable
private fun SeasonPicker(page: DetailState, onPick: (PlexItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = TouchMargin - 4.dp, top = 18.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(page.selectedSeason?.title ?: "Seasons", color = Chalk, fontFamily = tv.reely.ui.theme.Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Chevron(Muted, 14.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            page.seasons.forEach { season ->
                DropdownMenuItem(
                    text = { Text(season.title, fontWeight = if (season.ratingKey == page.selectedSeason?.ratingKey) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onPick(season) },
                )
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
            .padding(horizontal = TouchMargin - 6.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) SurfaceHigh else Color.Transparent)
            .combinedClickable(onClick = onPlay, onLongClick = onHold)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(140.dp)) {
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
