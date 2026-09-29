package tv.reely.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.reely.ui.RequestDetailState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.ExpandableSummary
import tv.reely.ui.components.HeroBackdrop
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/**
 * One title in Requests: what it is, and asking for it, with the choices Reely's own
 * request button offers. Which library it goes to, when there's more than one it could
 * (this account's default to begin with). Who it's for once it's in, when there are
 * groups to share it with. And for a show, which seasons: any number, or all, with every
 * season picked to begin with, since that is what asking for a show usually means.
 */
@Composable
fun RequestTitleScreen(
    page: RequestDetailState,
    /** This account's request for it already, if any: pending, approved or denied. */
    status: String?,
    onToggleSeason: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onRequest: () -> Unit,
    onChooseLibrary: (Long) -> Unit = {},
    onToggleGroup: (Long) -> Unit = {},
    onToggleJustMe: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val detail = page.detail
    Box(modifier = modifier.fillMaxSize()) {
        HeroBackdrop(url = detail?.backdrop ?: page.title.poster, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = page.title.title, color = Chalk, style = ReelyType.Display)
            val facts = listOfNotNull(
                page.title.year?.toString(),
                if (page.title.isShow) "Show" else "Movie",
                detail?.runtime?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                detail?.status,
                detail?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
            )
            Text(text = facts.joinToString("  ·  "), color = Muted, style = ReelyType.Body)
            (detail?.title?.overview ?: page.title.overview)?.let { ExpandableSummary(text = it, maxWidth = 700.dp) }

            val held = detail?.inLibraries?.size ?: 0
            when {
                page.busy -> EmptyNote("Loading…")
                page.error != null -> ErrorNote(page.error)
                detail == null -> Unit
                !page.canAsk -> EmptyNote(
                    if (held > 1) "Already in $held libraries: there's nowhere left for it to go."
                    else "Already in your library."
                )
                held == 1 -> EmptyNote("Already in a library. It can go in another as well.")
                held > 1 -> EmptyNote("Already in $held libraries. It can go in another as well.")
                detail.inLibrary -> EmptyNote("Already in your library.")
            }
            requestLabel(status)?.let { EmptyNote("You asked for this. ${requestWord(status)}") }

            val addable = page.addable.orEmpty()
            if (detail != null && page.canAsk && addable.size > 1) {
                SectionHeading("Library", modifier = Modifier.padding(top = 6.dp))
                ChipRow {
                    items(addable, key = { it.id }) { library ->
                        TvChip(
                            label = library.name,
                            selected = library.id == page.libraryId,
                            onClick = { onChooseLibrary(library.id) },
                        )
                    }
                }
            }

            val groups = page.places?.groups.orEmpty()
            if (detail != null && page.canAsk && groups.isNotEmpty()) {
                SectionHeading(
                    if (page.places?.adds == true) "Who it's for" else "Share it with",
                    modifier = Modifier.padding(top = 6.dp),
                )
                ChipRow {
                    item(key = "me") {
                        TvChip(label = "Just for me", selected = page.justMe, onClick = onToggleJustMe)
                    }
                    items(groups, key = { it.id }) { group ->
                        TvChip(
                            label = group.name,
                            selected = !page.justMe && group.id in page.audience,
                            onClick = { onToggleGroup(group.id) },
                        )
                    }
                }
            }

            if (detail != null && page.canAsk && page.title.isShow && detail.seasons.isNotEmpty()) {
                SectionHeading("Seasons", modifier = Modifier.padding(top = 6.dp))
                val all = detail.seasons.map { it.number }.toSet()
                ChipRow {
                    item(key = "all") {
                        TvChip(label = "All seasons", selected = page.chosen == all, onClick = onToggleAll)
                    }
                    items(detail.seasons, key = { it.number }) { season ->
                        TvChip(
                            label = season.name,
                            selected = season.number in page.chosen,
                            onClick = { onToggleSeason(season.number) },
                        )
                    }
                }
            }

            if (detail != null && page.canAsk) {
                val verb = if (page.places?.adds == true) "Add" else "Request"
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvActionButton(
                        label = when {
                            page.sending -> if (verb == "Add") "Adding…" else "Requesting…"
                            !page.title.isShow || detail.seasons.isEmpty() -> verb
                            page.chosen.size == detail.seasons.size -> "$verb all seasons"
                            page.chosen.size == 1 -> "$verb 1 season"
                            else -> "$verb ${page.chosen.size} seasons"
                        },
                        onClick = onRequest,
                        emphasised = true,
                    )
                }
            }
            page.outcome?.let {
                Text(text = it, color = Chalk, style = ReelyType.Body, modifier = Modifier.widthIn(max = 700.dp))
            }
        }
    }
}

/** A row of choices, walked left and right. */
@Composable
private fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        modifier = Modifier.focusGroup(),
        contentPadding = PaddingValues(end = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun requestWord(status: String?) = when (status) {
    "pending" -> "It's waiting to be approved."
    "approved" -> "It's been approved and is on its way."
    "denied" -> "It was declined."
    else -> ""
}
