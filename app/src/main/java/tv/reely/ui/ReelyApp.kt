package tv.reely.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.tv.material3.Text
import tv.reely.ui.components.EmptyNote
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

private enum class Destination(val label: String) {
    MOVIES("Movies"),
    SHOWS("TV Shows"),
    LIVE("Live TV"),
    STATUS("Status"),
}

@Composable
fun ReelyApp(viewModel: ReelyViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    val playback = state.playback
    if (playback != null) {
        PlayerScreen(
            playback = playback,
            onExit = viewModel::stopPlayback,
            onStepChannel = viewModel::stepChannel,
            onToggleFormat = viewModel::toggleFormat,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    var destination by rememberSaveable { mutableStateOf(Destination.MOVIES) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        TopBar(
            selected = destination,
            onSelect = { destination = it },
            serverName = state.plex.serverName,
        )

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))

        if (state.restoring) {
            EmptyNote("Starting up…", modifier = Modifier.padding(40.dp))
            return@Column
        }

        when (destination) {
            Destination.MOVIES, Destination.SHOWS -> {
                val kind = if (destination == Destination.MOVIES) LibraryKind.MOVIES else LibraryKind.SHOWS
                LibraryScreen(
                    kind = kind,
                    plex = state.plex,
                    imageUrl = viewModel::plexImageUrl,
                    onStartLink = viewModel::startPlexLink,
                    onCancelLink = viewModel::cancelPlexLink,
                    onDismissPlexError = viewModel::dismissPlexError,
                    onSelectSection = { viewModel.openSection(kind, it) },
                    onOpenItem = { viewModel.openItem(kind, it) },
                    onGoUp = { viewModel.goUp(kind) },
                    onDismissBrowseError = { viewModel.dismissBrowseError(kind) },
                )
            }

            Destination.LIVE -> LiveScreen(
                live = state.live,
                onSignIn = viewModel::signInXtream,
                onSelectCategory = viewModel::openCategory,
                onPlayChannel = viewModel::playChannel,
                onDismissError = viewModel::dismissLiveError,
            )

            Destination.STATUS -> StatusScreen(
                plex = state.plex,
                live = state.live,
                onSignOutPlex = viewModel::signOutPlex,
                onSignOutXtream = viewModel::signOutXtream,
                onToggleFormat = viewModel::toggleFormat,
            )
        }
    }
}

@Composable
private fun TopBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    serverName: String?,
) {
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstTab.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "reely",
            color = Accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 20.dp),
        )

        Destination.entries.forEachIndexed { index, item ->
            NavTab(
                label = item.label,
                selected = item == selected,
                onSelect = { onSelect(item) },
                modifier = if (index == 0) Modifier.focusRequester(firstTab) else Modifier,
            )
        }

        Box(modifier = Modifier.weight(1f))

        if (serverName != null) {
            Text(text = serverName, color = Faint, fontSize = 12.sp)
        }
    }
}

/**
 * Focus selects the tab. That is the convention on a television: you never "click" a tab,
 * you just arrive at it.
 */
@Composable
private fun NavTab(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .clip(RoundedCornerShape(99.dp))
            .background(if (focused) Accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = when {
                focused -> Parchment
                selected -> Accent
                else -> Muted
            },
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
