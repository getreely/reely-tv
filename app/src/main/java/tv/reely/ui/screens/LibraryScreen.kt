package tv.reely.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexSection
import tv.reely.ui.BrowseState
import tv.reely.ui.LibraryKind
import tv.reely.ui.PlexState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HintBar
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvChip
import tv.reely.ui.components.TvPosterTile
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Parchment

@Composable
fun LibraryScreen(
    kind: LibraryKind,
    plex: PlexState,
    imageUrl: (String?) -> String?,
    onStartLink: () -> Unit,
    onCancelLink: () -> Unit,
    onDismissPlexError: () -> Unit,
    onSelectSection: (PlexSection) -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    onGoUp: () -> Unit,
    onDismissBrowseError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!plex.isConnected) {
        PlexSignInPanel(
            plex = plex,
            onStartLink = onStartLink,
            onCancelLink = onCancelLink,
            onDismissError = onDismissPlexError,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val sections = plex.sectionsFor(kind)
    val browse: BrowseState = plex.browseFor(kind)
    val canGoUp = browse.trail.size > 1

    // Inside a show, Back walks the trail rather than leaving the app.
    BackHandler(enabled = canGoUp, onBack = onGoUp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = browse.trail.lastOrNull()?.title ?: kind.title,
                    color = Parchment,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (browse.trail.size > 1) {
                    Text(
                        text = browse.trail.joinToString("  ›  ") { it.title },
                        color = Faint,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
            if (canGoUp) {
                TvActionButton(label = "Back", onClick = onGoUp)
            }
        }

        if (sections.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sections, key = { it.key }) { section ->
                    TvChip(
                        label = section.title,
                        selected = browse.section?.key == section.key,
                        onClick = { onSelectSection(section) },
                    )
                }
            }
        }

        if (browse.error != null) {
            ErrorNote(message = browse.error, onDismiss = onDismissBrowseError)
        }

        when {
            sections.isEmpty() -> EmptyNote(
                "No ${kind.title.lowercase()} library on ${plex.serverName ?: "this server"}."
            )

            browse.busy && browse.items.isEmpty() -> EmptyNote("Loading ${kind.title.lowercase()}…")

            browse.items.isEmpty() -> EmptyNote("Nothing here.")

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 158.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(browse.items, key = { it.ratingKey }) { item ->
                        TvPosterTile(
                            title = item.title,
                            subtitle = item.subtitle,
                            imageUrl = imageUrl(item.thumb),
                            onClick = { onOpenItem(item) },
                        )
                    }
                }
            }
        }

        HintBar(
            text = if (kind == LibraryKind.SHOWS)
                "D-pad to move · OK to open a show, then a season, then an episode · Back to go up"
            else
                "D-pad to move · OK to play",
        )
    }
}
