package tv.reely.ui.touch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.reely.ui.LiveState
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.ui.theme.SurfaceRaised
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.catchUpProgramme
import java.text.DateFormat
import java.util.Date

/**
 * Live TV on a phone: categories, then a category's channels with what's on now and
 * next. A press plays a channel; a hold has its schedule — reminders for what's to come,
 * and, where the channel keeps an archive, what's been on to watch again — and Favorites.
 */
@Composable
internal fun TouchLive(viewModel: ReelyViewModel, state: ReelyState, actions: TouchActions) {
    val live = state.live
    if (!live.isConnected) {
        Column(Modifier.fillMaxSize()) {
            TouchHeader("Live TV", actions)
            LiveSignIn(viewModel, live)
        }
        return
    }
    val category = live.selectedCategory
    if (category == null) {
        Column(Modifier.fillMaxSize()) {
            TouchHeader("Live TV", actions)
            live.error?.let { TouchError(it, onDismiss = viewModel::dismissLiveError) }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + barSpace())) {
                items(live.shownCategories, key = { it.id }) { item ->
                    TouchListRow(item.name, onClick = { viewModel.openCategory(item) })
                }
                if (live.busy && live.categories.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
                }
            }
        }
        return
    }
    androidx.activity.compose.BackHandler { viewModel.clearCategory() }
    // The clock the now-and-next lines are read against, moved on each minute.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    var scheduleFor by remember { mutableStateOf<XtreamChannel?>(null) }
    Column(Modifier.fillMaxSize()) {
        TouchPageBar(category.name, onBack = viewModel::clearCategory)
        live.error?.let { TouchError(it, onDismiss = viewModel::dismissLiveError) }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + barSpace())) {
            if (live.busy && live.channels.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            }
            itemsIndexed(live.channels, key = { _, channel -> channel.streamId }) { index, channel ->
                val listing = listingFor(state, channel)
                ChannelRow(
                    channel = channel,
                    favorite = channel.streamId in live.favorites,
                    on = listing.firstOrNull { it.isOnAt(now) },
                    next = listing.firstOrNull { it.start >= now },
                    now = now,
                    onPlay = { viewModel.playChannel(index) },
                    onHold = {
                        viewModel.focusChannel(channel)
                        scheduleFor = channel
                    },
                )
            }
        }
    }
    scheduleFor?.let { channel ->
        Schedule(viewModel, state, channel, now, onDismiss = { scheduleFor = null })
    }
}

/** What's on a channel: the full guide where there is one, else the provider's short one. */
private fun listingFor(state: ReelyState, channel: XtreamChannel): List<EpgProgramme> {
    val fromGuide = channel.epgChannelId?.let { state.guide.programmes[it] }.orEmpty()
    if (fromGuide.isNotEmpty()) return fromGuide.sortedBy { it.start }
    return state.live.nowNext(channel.streamId).map {
        EpgProgramme(channel.epgChannelId.orEmpty(), it.startEpochSeconds, it.endEpochSeconds, it.title, it.description)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: XtreamChannel,
    favorite: Boolean,
    on: EpgProgramme?,
    next: EpgProgramme?,
    now: Long,
    onPlay: () -> Unit,
    onHold: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onHold)
            .padding(horizontal = TouchMargin, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceHigh), contentAlignment = Alignment.Center) {
            if (channel.icon != null) {
                AsyncImage(channel.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(channel.name.take(3), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(channel.number.takeIf { it > 0 }?.toString(), channel.name).joinToString("  ") + if (favorite) "  ♥" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = Chalk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (on != null) {
                Text(on.title, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val span = (on.stop - on.start).coerceAtLeast(1)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                    Box(Modifier.fillMaxWidth(((now - on.start).toFloat() / span).coerceIn(0f, 1f)).height(3.dp).background(Accent))
                }
            }
            if (next != null) {
                Text("Next: ${time(next.start)} ${next.title}", style = MaterialTheme.typography.labelSmall, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (channel.archiveDays > 0) Text("↺", color = Faint)
    }
}

/** A channel's schedule, from its hold: watch again, set reminders, favorite it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Schedule(viewModel: ReelyViewModel, state: ReelyState, channel: XtreamChannel, now: Long, onDismiss: () -> Unit) {
    val listing = listingFor(state, channel)
    val favorite = channel.streamId in state.live.favorites
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = SurfaceRaised,
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + barSpace())) {
            item {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, color = Chalk, modifier = Modifier.padding(horizontal = 20.dp))
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TouchPrimaryButton("Watch", {
                        onDismiss()
                        val index = state.live.channels.indexOfFirst { it.streamId == channel.streamId }
                        if (index >= 0) viewModel.playChannel(index)
                    })
                    TouchSecondaryButton(if (favorite) "Remove favorite" else "Add to Favorites", { viewModel.toggleFavoriteChannel(channel) })
                }
            }
            if (listing.isEmpty()) item { TouchNote("No guide for this channel.") }
            // Keyed by place as well as time: guides do list the same programme twice, and
            // a key used twice takes the whole list down.
            itemsIndexed(listing.filter { it.stop > now - channel.archiveDays * 86_400L }, key = { index, it -> "$index:${it.start}" }) { _, programme ->
                val past = programme.stop <= now
                val onNow = programme.isOnAt(now)
                val again = catchUpProgramme(channel, listing, programme.start, now) != null
                val reminded = state.reminders.any { it.streamId == channel.streamId && it.start == programme.start }
                TouchListRow(
                    title = programme.title,
                    subtitle = listOfNotNull(
                        "${day(programme.start)} ${time(programme.start)}–${time(programme.stop)}",
                        when {
                            onNow -> "On now"
                            past && again -> "Watch again"
                            !past && reminded -> "Reminder set"
                            else -> null
                        },
                    ).joinToString(" · "),
                    onClick = when {
                        onNow -> ({
                            onDismiss()
                            val index = state.live.channels.indexOfFirst { it.streamId == channel.streamId }
                            if (index >= 0) viewModel.playChannel(index)
                        })
                        past && again -> ({ onDismiss(); viewModel.playCatchUp(channel, programme) })
                        !past -> ({ viewModel.toggleReminder(channel, programme) })
                        else -> null
                    },
                )
            }
        }
    }
}

private fun time(epochSeconds: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))

private fun day(epochSeconds: Long): String {
    val today = System.currentTimeMillis() / 86_400_000L
    val then = epochSeconds * 1000 / 86_400_000L
    return when (then - today) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))
    }
}

/** Signing in to live TV: an Xtream login, or an M3U playlist. The same two as the television. */
@Composable
private fun LiveSignIn(viewModel: ReelyViewModel, live: LiveState) {
    var playlist by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Not saved with the screen's state, which the system keeps outside the app.
    var password by remember { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var guide by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = barSpace()).padding(horizontal = TouchMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Watch live TV from your provider", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        Text(
            "Sign in with the details your IPTV provider gave you. Reely doesn't provide channels.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TouchChip("Server login", selected = !playlist, onClick = { playlist = false }) }
            item { TouchChip("M3U playlist", selected = playlist, onClick = { playlist = true }) }
        }
        live.error?.let { TouchError(it, onDismiss = viewModel::dismissLiveError, modifier = Modifier.padding(0.dp)) }
        if (!playlist) {
            Field("Server address", host, { host = it }, KeyboardType.Uri)
            Field("Username", username, { username = it })
            Field("Password", password, { password = it }, KeyboardType.Password, secret = true)
            TouchPrimaryButton(
                if (live.busy) "Connecting…" else "Sign in",
                { viewModel.signInXtream(host, username, password) },
                Modifier.fillMaxWidth(),
                enabled = !live.busy,
            )
        } else {
            Field("Playlist address", url, { url = it }, KeyboardType.Uri)
            Field("TV guide address (optional)", guide, { guide = it }, KeyboardType.Uri)
            TouchPrimaryButton(
                if (live.busy) "Loading…" else "Add playlist",
                { viewModel.signInPlaylist(url, guide) },
                Modifier.fillMaxWidth(),
                enabled = !live.busy,
            )
        }
    }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Chalk, unfocusedBorderColor = Line, cursorColor = Accent, focusedLabelColor = Chalk),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Over a channel on a phone, as the iPhone has it: the categories, and their channels to
 * change to, while the one playing plays on until another is chosen. On a television the
 * guide comes up over the channel with Down; this is the touch way to it. Opened from
 * Multiview, a channel chosen goes beside what's playing, or into the tile it was opened for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchChannelSheet(viewModel: ReelyViewModel, state: ReelyState, request: tv.reely.core.GuideRequest, onDismiss: () -> Unit) {
    val live = state.live
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val playing = state.playback?.takeIf { it.isLive }?.channelIndex?.let { live.channels.getOrNull(it)?.streamId }
    // Only adding is held to four: replacing a tile never adds one.
    val full = request.adds && request.replaces == null && state.multiview.size >= 3
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = SurfaceRaised,
    ) {
        if (request.adds) {
            Text(
                if (request.replaces != null) "Replace with a channel" else "Add a channel beside this one",
                style = MaterialTheme.typography.titleLarge,
                color = Chalk,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (full) TouchNote("You can watch up to four channels at once.")
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(live.shownCategories, key = { it.id }) { category ->
                TouchChip(category.name, selected = category.id == live.selectedCategory?.id, onClick = { viewModel.openCategory(category) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (live.busy && live.channels.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            }
            itemsIndexed(live.channels, key = { _, channel -> channel.streamId }) { index, channel ->
                val listing = listingFor(state, channel)
                Box(Modifier.background(if (channel.streamId == playing) SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent)) {
                    ChannelRow(
                        channel = channel,
                        favorite = channel.streamId in live.favorites,
                        on = listing.firstOrNull { it.isOnAt(now) },
                        next = listing.firstOrNull { it.start >= now },
                        now = now,
                        onPlay = {
                            when {
                                request.replaces != null -> { onDismiss(); viewModel.replaceInMultiview(request.replaces, channel) }
                                request.adds -> if (!full) { onDismiss(); viewModel.addToMultiview(channel) }
                                else -> { onDismiss(); viewModel.playChannel(index) }
                            }
                        },
                        // Held, from the plain list: beside what's playing, as the TV guide's menu offers.
                        onHold = {
                            if (!request.adds && state.multiview.size < 3) { onDismiss(); viewModel.addToMultiview(channel) }
                        },
                    )
                }
            }
        }
    }
}
