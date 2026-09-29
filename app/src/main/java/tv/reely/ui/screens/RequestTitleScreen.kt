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
 * One title in Requests: what it is, and asking for it. A show asks which seasons —
 * any number of them, or all — with every season picked to begin with, since that is
 * what asking for a show usually means.
 */
@Composable
fun RequestTitleScreen(
    page: RequestDetailState,
    /** This account's request for it already, if any: pending, approved or denied. */
    status: String?,
    onToggleSeason: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onRequest: () -> Unit,
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
                if (page.title.isShow) "Show" else "Film",
                detail?.runtime?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                detail?.status,
                detail?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
            )
            Text(text = facts.joinToString("  ·  "), color = Muted, style = ReelyType.Body)
            (detail?.title?.overview ?: page.title.overview)?.let { ExpandableSummary(text = it, maxWidth = 700.dp) }

            when {
                page.busy -> EmptyNote("Loading…")
                page.error != null -> ErrorNote(page.error)
                detail == null -> Unit
                detail.inLibrary -> EmptyNote(
                    if (page.title.isShow) "This show is already in your library. Ask again for seasons it doesn't have."
                    else "This is already in your library."
                )
            }
            requestLabel(status)?.let { EmptyNote("You asked for this. ${requestWord(status)}") }

            if (detail != null && page.title.isShow && detail.seasons.isNotEmpty()) {
                SectionHeading("Seasons", modifier = Modifier.padding(top = 6.dp))
                val all = detail.seasons.map { it.number }.toSet()
                LazyRow(
                    modifier = Modifier.focusGroup(),
                    contentPadding = PaddingValues(end = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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

            if (detail != null && !(detail.inLibrary && !page.title.isShow)) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvActionButton(
                        label = when {
                            page.sending -> "Requesting…"
                            !page.title.isShow -> "Request"
                            page.chosen.size == detail.seasons.size -> "Request all seasons"
                            page.chosen.size == 1 -> "Request 1 season"
                            else -> "Request ${page.chosen.size} seasons"
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

private fun requestWord(status: String?) = when (status) {
    "pending" -> "It's waiting to be approved."
    "approved" -> "It's been approved and is on its way."
    "denied" -> "It was declined."
    else -> ""
}
