package tv.reely.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import tv.reely.plex.PlexItem
import tv.reely.plex.formatDuration
import tv.reely.ui.components.CardAction
import tv.reely.ui.components.CardMenu
import tv.reely.ui.components.CheckGlyph
import tv.reely.ui.components.CrossGlyph
import tv.reely.ui.components.InfoGlyph
import tv.reely.ui.components.MenuScrim
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.RestartGlyph
import androidx.compose.ui.unit.dp

/**
 * Opens the held-OK menu for a title, from any screen: provided by the app around every
 * screen, so a poster anywhere only has to hand over what it shows. Null outside it.
 */
val LocalItemMenu = staticCompositionLocalOf<((PlexItem) -> Unit)?> { null }

/** Whether a title has anything a menu could offer: films, episodes, shows and seasons. */
fun hasItemMenu(item: PlexItem): Boolean = item.type in setOf("movie", "episode", "show", "season")

/** What a poster hands its card as the hold: the app's menu for [item], when it has one. */
@Composable
fun holdFor(item: PlexItem): (() -> Unit)? {
    val open = LocalItemMenu.current ?: return null
    return if (hasItemMenu(item)) ({ open(item) }) else null
}

/** The line under a title in its menu: what it is, how long, and how far along. */
internal fun menuMeta(item: PlexItem): String? {
    val left = item.resumeFraction?.let { ((item.durationMs - item.viewOffsetMs) / 60_000).coerceAtLeast(1) }
    return listOfNotNull(
        // An episode's own title is the menu's heading; this says where it sits.
        if (item.type == "episode") item.caption else item.year?.toString(),
        when (item.type) {
            "show" -> item.leafCount.takeIf { it > 0 }?.let { if (it == 1) "1 episode" else "$it episodes" }
            "season" -> item.caption
            // Part watched, what's left says more than how long it is.
            else -> formatDuration(item.durationMs).takeIf { it.isNotBlank() && left == null }
        },
        when {
            // Kept on one line: "31" at the end of one and "min left" on the next read badly.
            left != null -> "$left\u00A0min\u00A0left"
            item.isWatched -> "Watched"
            else -> null
        },
    ).joinToString("  ·  ").takeIf { it.isNotBlank() }
}

/**
 * What holding OK on a title offers: play it (or carry on), start it over, mark it, and
 * open its page (for an episode, its show's page at that episode). Only what the title can actually do: a
 * show or season has no single file to play from here, so it's marked or opened.
 */
internal fun itemMenuActions(
    item: PlexItem,
    onPlay: (resume: Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onDetails: () -> Unit,
    onRemoveFromContinueWatching: (() -> Unit)? = null,
    /** For a show or season: its next episode; see PlexApi.nextEpisode. */
    onPlayNext: (() -> Unit)? = null,
): List<CardAction> = buildList {
    val resumable = (item.resumeFraction ?: 0f) > 0f
    if (onPlayNext != null && (item.type == "show" || item.type == "season") && item.leafCount > 0) {
        add(
            CardAction(
                label = when {
                    item.viewedLeafCount == 0 -> "Play first episode"
                    item.isWatched -> "Play from the start"
                    else -> "Play next episode"
                },
                emphasised = true,
                icon = { PlayGlyph(it, size = 20.dp) },
            ) { onPlayNext() }
        )
    }
    if (item.isPlayable) {
        add(
            CardAction(
                label = if (resumable) "Resume" else "Play",
                emphasised = true,
                icon = { PlayGlyph(it, size = 20.dp) },
            ) { onPlay(true) }
        )
        if (resumable) {
            add(CardAction("Play from the beginning", icon = { RestartGlyph(it, size = 20.dp) }) { onPlay(false) })
        }
    }
    add(
        CardAction(
            if (item.isWatched) "Mark as unwatched" else "Mark as watched",
            icon = { CheckGlyph(it, size = 20.dp) },
        ) { onToggleWatched() }
    )
    // An episode's page is a place in its show's page, so for one this is going to the show.
    val details = if (item.type == "episode") "Go to ${item.grandparentTitle ?: "the show"}" else "Details"
    add(CardAction(details, icon = { InfoGlyph(it, size = 20.dp) }) { onDetails() })
    if (onRemoveFromContinueWatching != null) {
        add(CardAction("Remove from Continue Watching", icon = { CrossGlyph(it, size = 20.dp) }) { onRemoveFromContinueWatching() })
    }
}

/**
 * The menu over the screen: a shade, and the panel on the right with the title's own
 * picture at the top. An episode shows its own still; anything else its backdrop.
 */
@Composable
fun ItemMenu(
    item: PlexItem,
    actions: List<CardAction>,
    focusRequester: FocusRequester,
    backdropUrl: (String?, String?) -> String?,
    logoUrl: (String?, String?) -> String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        MenuScrim()
        val picture = if (item.type == "episode") item.thumb ?: item.art else item.art ?: item.thumb
        CardMenu(
            title = if (item.type == "episode") item.title else item.rowTitle,
            subtitle = if (item.type == "episode") listOfNotNull(item.grandparentTitle, menuMeta(item)).joinToString("  ·  ") else menuMeta(item),
            actions = actions,
            focusRequester = focusRequester,
            onCancel = onCancel,
            imageUrl = backdropUrl(item.serverBase, picture),
            // The name in the app's own lettering, as on the page behind: a logo beside a
            // small picture of the same title looked like two headings.
            logoUrl = null,
            progress = item.resumeFraction,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}
