package tv.reely.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Glass
import tv.reely.ui.theme.GlassEdge
import tv.reely.ui.theme.Good
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment

/**
 * The soft wash behind a browse screen.
 *
 * A Firestick cannot blur anything — Compose's blur is RenderEffect, which is API 31,
 * and Fire OS stops at 30 — so the artwork is fetched at about 48 pixels wide and
 * stretched instead. The server does the scaling, the bitmap costs a couple of
 * kilobytes, and the result is indistinguishable from a Gaussian blur at this size.
 */
@Composable
fun BlurredBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    scrimFromLeft: Boolean = true,
) {
    Box(modifier) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (scrimFromLeft) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Ink.copy(alpha = 0.95f),
                                Ink.copy(alpha = 0.72f),
                                Ink.copy(alpha = 0.18f),
                            )
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.copy(alpha = 0.55f),
                        0.55f to Ink.copy(alpha = 0.62f),
                        1f to Ink,
                    )
                )
        )
    }
}

/** A translucent surface with a hairline edge — the app's stand-in for frosted glass. */
fun Modifier.glass(radius: Int = 14) = this
    .clip(RoundedCornerShape(radius.dp))
    .background(Glass)
    .border(1.dp, GlassEdge, RoundedCornerShape(radius.dp))

/** A small circular action. The filled one is the only thing carrying colour. */
@Composable
fun IconAction(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.width(66.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(50))
                .background(if (filled) Accent else Glass)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Parchment else GlassEdge,
                    shape = RoundedCornerShape(50),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            glyph(if (filled) Ink else Parchment)
        }
        Text(
            text = label,
            color = if (focused) Parchment else Faint,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Critic score, audience score and certificate as separate marks rather than a sentence. */
@Composable
fun RatingBadges(
    criticRating: Double?,
    audienceRating: Double?,
    contentRating: String?,
    trailing: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (criticRating != null) {
            Badge(text = "★ %.1f".format(criticRating), tint = Accent, emphasised = true)
        }
        if (audienceRating != null) {
            Badge(text = "${(audienceRating * 10).toInt()}%", tint = Good)
        }
        if (!contentRating.isNullOrBlank()) {
            Badge(text = contentRating, tint = GlassEdge)
        }
        trailing.forEach { fact ->
            Text(text = fact, color = Muted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Badge(text: String, tint: Color, emphasised: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasised) tint.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = Parchment,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * One pill per season, above the title. Seasons past the edge arrive by holding right —
 * the rail scrolls the next set in rather than opening a panel over anything.
 */
@Composable
fun <T> PillRail(
    items: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = key) { item ->
            TvChip(
                label = label(item),
                selected = selected(item),
                onClick = { onSelect(item) },
            )
        }
    }
}

/** The block of text that describes whatever currently has focus. */
@Composable
fun HeroText(
    eyebrow: String?,
    title: String,
    criticRating: Double?,
    audienceRating: Double?,
    contentRating: String?,
    facts: List<String>,
    summary: String?,
    modifier: Modifier = Modifier,
    summaryMaxLines: Int = 2,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                color = Faint,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = title,
            color = Parchment,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (criticRating != null || audienceRating != null || !contentRating.isNullOrBlank() || facts.isNotEmpty()) {
            RatingBadges(
                criticRating = criticRating,
                audienceRating = audienceRating,
                contentRating = contentRating,
                trailing = facts,
            )
        }
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A stable colour for a channel or category tile.
 *
 * The reference design tints each tile with its broadcaster's own colour, which would
 * mean extracting a dominant colour from every logo. This derives one from the id
 * instead: the same channel always gets the same tile, the set is picked to sit with the
 * palette, and it costs nothing per image.
 */
fun channelTint(id: String): Color {
    val palette = listOf(
        Color(0xFF2C3563),
        Color(0xFF7A3B42),
        Color(0xFF2F5E52),
        Color(0xFF6A4E2A),
        Color(0xFF473469),
        Color(0xFF2B5066),
        Color(0xFF6B3559),
        Color(0xFF3F5A2E),
    )
    val hash = id.fold(7) { acc, char -> acc * 31 + char.code }
    return palette[((hash % palette.size) + palette.size) % palette.size]
}
