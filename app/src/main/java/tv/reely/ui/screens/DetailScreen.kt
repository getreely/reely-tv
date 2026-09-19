package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.reely.plex.PlexItem
import tv.reely.plex.formatDuration
import tv.reely.ui.DetailState
import tv.reely.ui.components.CastCircle
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.EpisodeRow
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment

@Composable
fun DetailScreen(
    state: DetailState,
    imageUrl: (String?, Int, Int) -> String?,
    onPlay: (PlexItem) -> Unit,
    onPlayDetail: () -> Unit,
    onSelectSeason: (PlexItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    if (detail == null) {
        Column(modifier = modifier.fillMaxSize().padding(40.dp)) {
            if (state.error != null) ErrorNote(state.error) else EmptyNote("Loading…")
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                val art = imageUrl(detail.art ?: detail.thumb, 1280, 720)
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Scrim, so the title stays readable over whatever the artwork is.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Ink.copy(alpha = 0.55f), Ink.copy(alpha = 0.92f), Ink)
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 40.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (detail.grandparentTitle != null) {
                        Text(
                            text = detail.grandparentTitle,
                            color = Faint,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    Text(
                        text = detail.title,
                        color = Parchment,
                        fontSize = 34.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val facts = listOfNotNull(
                        detail.facts.takeIf { it.isNotBlank() },
                        detail.rating?.let { "★ %.1f".format(it) },
                    ).joinToString("  ·  ")
                    if (facts.isNotBlank()) {
                        Text(text = facts, color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.error != null) ErrorNote(state.error)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        label = playLabel(state),
                        onClick = onPlayDetail,
                        emphasised = true,
                    )
                }

                if (detail.tagline != null) {
                    Text(
                        text = detail.tagline,
                        color = Faint,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.widthIn(max = 860.dp),
                    )
                }

                if (detail.summary != null) {
                    Text(
                        text = detail.summary,
                        color = Muted,
                        fontSize = 15.sp,
                        lineHeight = 23.sp,
                        modifier = Modifier.widthIn(max = 860.dp),
                    )
                }

                val credits = buildList {
                    if (detail.genres.isNotEmpty()) add("Genres" to detail.genres.joinToString(", "))
                    if (detail.directors.isNotEmpty()) {
                        add("Director" to detail.directors.joinToString(", "))
                    }
                }
                credits.forEach { (label, value) ->
                    Text(
                        text = "$label   $value",
                        color = Faint,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.widthIn(max = 860.dp),
                    )
                }
            }
        }

        if (detail.roles.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeading("Cast", modifier = Modifier.padding(horizontal = 40.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(detail.roles.take(24)) { role ->
                            CastCircle(
                                name = role.name,
                                role = role.role,
                                imageUrl = imageUrl(role.thumb, 160, 160),
                            )
                        }
                    }
                }
            }
        }

        if (detail.isShow) {
            if (state.seasons.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(top = 26.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionHeading("Seasons", modifier = Modifier.padding(horizontal = 40.dp))
                        LazyRow(
                            modifier = Modifier.focusGroup(),
                            contentPadding = PaddingValues(horizontal = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.seasons, key = { it.ratingKey }) { season ->
                                TvChip(
                                    label = season.title,
                                    selected = state.selectedSeason?.ratingKey == season.ratingKey,
                                    onClick = { onSelectSeason(season) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.busy && state.episodes.isEmpty()) {
                item {
                    EmptyNote(
                        "Loading episodes…",
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp),
                    )
                }
            }

            items(state.episodes, key = { it.ratingKey }) { episode ->
                EpisodeRow(
                    number = episode.index?.toString().orEmpty(),
                    title = episode.title,
                    description = episode.summary,
                    duration = formatDuration(episode.durationMs).takeIf { it.isNotEmpty() },
                    imageUrl = imageUrl(episode.thumb, 320, 180),
                    progress = episode.resumeFraction,
                    onClick = { onPlay(episode) },
                    modifier = Modifier.padding(horizontal = 36.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun playLabel(state: DetailState): String {
    val detail = state.detail ?: return "Play"
    if (detail.isShow) {
        val resume = state.episodes.firstOrNull { it.resumeFraction != null }
        return if (resume != null) {
            "Resume ${listOfNotNull(resume.caption).joinToString()}".trim()
        } else {
            "Play"
        }
    }
    val offset = detail.viewOffsetMs
    return if (offset > 0) "Resume from ${formatDuration(offset)}" else "Play"
}
