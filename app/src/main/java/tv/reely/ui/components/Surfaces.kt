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
import androidx.compose.foundation.layout.widthIn
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import coil.request.ImageRequest
import coil.imageLoader
import androidx.palette.graphics.Palette
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceRaised

/**
 * The artwork behind a browse screen, darkened until text sits comfortably on it.
 *
 * This used to be the same image fetched at 48 pixels wide and stretched, standing in
 * for a blur the hardware cannot do — Compose's blur is RenderEffect, API 31, and Fire
 * OS stops at 30. At a fortyfold upscale there is not enough left of the picture to read
 * as a blur: it comes out as mottled blobs and banding. A real backdrop under a heavy
 * scrim is both better looking and what every television app of this kind actually does.
 *
 * Moving between titles fades from one picture to the next. The next one is loaded out
 * of sight first and only then faded in, so the fade is picture to picture — never to
 * black and back while it downloads — and it used to be a hard cut.
 *
 * The same picture gives the screen its colour: [onTint] is told the artwork's main
 * colour, and [glow], when given, lights the bottom of the screen with it.
 */
@Composable
fun HeroBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    scrimFromLeft: Boolean = true,
    onTint: ((Color) -> Unit)? = null,
    glow: Color? = null,
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val latestOnTint by rememberUpdatedState(onTint)
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            // A software bitmap, because the colour is read out of its pixels.
            .allowHardware(false)
            .build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        shown = bitmap.asImageBitmap()
        val tint = withContext(Dispatchers.Default) { artworkTint(bitmap) }
        if (tint != null) latestOnTint?.invoke(tint)
    }

    Box(modifier) {
        Crossfade(targetState = shown, animationSpec = tween(BACKDROP_FADE_MS), label = "backdrop") { picture ->
            if (picture != null) {
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // A flat dim first, so a bright poster cannot overpower the text on top of it.
        Box(modifier = Modifier.fillMaxSize().background(Ink.copy(alpha = 0.45f)))

        if (scrimFromLeft) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Ink.copy(alpha = 0.92f),
                            0.42f to Ink.copy(alpha = 0.62f),
                            0.78f to Ink.copy(alpha = 0.12f),
                            1f to Color.Transparent,
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.copy(alpha = 0.6f),
                        0.3f to Ink.copy(alpha = 0.25f),
                        0.72f to Ink.copy(alpha = 0.88f),
                        1f to Ink,
                    )
                )
        )
        // The artwork's colour, rising from the bottom left where the rows are, and a
        // little in the far corner. Over the scrims, so it tints the dark, not the picture.
        if (glow != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(glow.copy(alpha = 0.20f), Color.Transparent),
                                center = Offset(size.width * 0.08f, size.height * 1.04f),
                                radius = size.width * 0.75f,
                            )
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(glow.copy(alpha = 0.09f), Color.Transparent),
                                center = Offset(size.width, 0f),
                                radius = size.width * 0.55f,
                            )
                        )
                    }
            )
        }
    }
}

private const val BACKDROP_FADE_MS = 450

/**
 * The colour a piece of artwork gives off, for a glow behind it: its most vivid colour,
 * or failing that its most common, with the hue kept but the light evened out. A glow
 * from a near-black poster would be invisible, and one from a neon poster would shout.
 */
fun artworkTint(bitmap: Bitmap): Color? {
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** A translucent surface with a hairline edge — the app's stand-in for frosted glass. */
fun Modifier.glass(radius: Int = 14) = this
    .clip(RoundedCornerShape(radius.dp))
    .background(Glass)
    .border(1.dp, GlassEdge, RoundedCornerShape(radius.dp))

/**
 * A panel laid over the picture. Nearly opaque, unlike [glass]: whatever is playing
 * behind it can be any brightness, and the text on it still has to read.
 */
fun Modifier.sheet(radius: Int = 20) = this
    .clip(RoundedCornerShape(radius.dp))
    .background(SurfaceRaised.copy(alpha = 0.95f))
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
                .graphicsLayer { val lift = if (focused) 1.08f else 1f; scaleX = lift; scaleY = lift }
                .clip(RoundedCornerShape(50))
                // White when focused, as every control is. Play keeps its coral otherwise:
                // it is the one action the page is built around.
                .background(
                    when {
                        focused -> Chalk
                        filled -> Accent
                        else -> Glass
                    }
                )
                .border(width = 1.dp, color = if (focused || filled) Color.Transparent else GlassEdge, shape = RoundedCornerShape(50))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            glyph(if (focused || filled) Ink else Chalk)
        }
        Text(
            text = label,
            color = if (focused) Chalk else Muted,
            fontSize = 14.sp,
            lineHeight = 18.sp,
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
    /** What the file is — 4K, Dolby Vision, 5.1 — after the details. */
    qualities: List<String> = emptyList(),
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
            Text(text = fact, color = Muted, style = ReelyType.Meta, maxLines = 1)
        }
        // Filled rather than outlined, so they read as facts about the file and not as
        // ratings; a little apart from the details they follow.
        qualities.forEachIndexed { i, quality ->
            Text(
                text = quality,
                color = Chalk,
                style = ReelyType.Label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier
                    .padding(start = if (i == 0) 6.dp else 0.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Chalk.copy(alpha = 0.13f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
            color = Chalk,
            fontSize = 14.sp,
            lineHeight = 18.sp,
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
    summaryMaxLines: Int = 3,
    /** The title's own logo, shown in place of [title] when it loads. */
    logoUrl: String? = null,
    /** A second line under the title, for an episode shown beneath its show's logo. */
    subtitle: String? = null,
    /** What the file is: 4K, Dolby Vision, 5.1. See [tv.reely.core.qualityBadges]. */
    qualities: List<String> = emptyList(),
) {
    // Room between the lines: at 6 dp the logo, the facts and the summary read as one
    // block jammed together on a real set. 8 dp since the summary grew to three lines.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                color = Faint,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TitleArt(url = logoUrl, title = title)
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = Chalk,
                style = ReelyType.Headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (criticRating != null || audienceRating != null || !contentRating.isNullOrBlank() || facts.isNotEmpty() || qualities.isNotEmpty()) {
            RatingBadges(
                criticRating = criticRating,
                audienceRating = audienceRating,
                contentRating = contentRating,
                trailing = facts,
                qualities = qualities,
            )
        }
        if (!summary.isNullOrBlank()) {
            // The one place anybody reads more than a line, so it gets a reading width: it
            // used to run on for 700 dp and was hard to follow back. A size down from body,
            // so it sits under the title rather than competing with it.
            Text(
                text = summary,
                color = Muted,
                style = HeroSummary,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
    }
}

/** Title logos are fitted into this box: a tenth of the screen's height at most. */
private val LOGO_HEIGHT = 50.dp
private val LOGO_WIDTH = 320.dp

/** A title with no logo, sized to sit where one would. */
private val HeroTitle = ReelyType.Display.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp)

/** Three lines of it on the browse screens, so a size under the detail page's. */
private val HeroSummary = ReelyType.Body.copy(fontSize = 15.sp, lineHeight = 21.sp)

/**
 * A title's own logo in place of its name — or the name, at display size, when there is
 * no logo or it will not load.
 *
 * Logos come in every shape, long wordmarks and tall stacked ones alike, so they are
 * fitted into the box and sat on its bottom-left corner rather than cropped to fill it.
 */
@Composable
fun TitleArt(url: String?, title: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url == null || failed) {
        Text(
            text = title,
            color = Chalk,
            style = HeroTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = modifier.size(width = LOGO_WIDTH, height = LOGO_HEIGHT),
        )
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

/**
 * A transport control. No caption: the shapes are the universal ones, and a row of
 * labelled buttons is not what a player looks like.
 */
@Composable
fun TransportButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    diameter: androidx.compose.ui.unit.Dp = 44.dp,
    glyph: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // White when focused, like every other control; play keeps its coral otherwise.
    val tint = when {
        !enabled -> Chalk.copy(alpha = 0.25f)
        focused || filled -> Ink
        else -> Chalk
    }
    Box(
        modifier = modifier
            .size(diameter)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val lift = if (focused) 1.08f else 1f; scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    !enabled -> Color.Transparent
                    focused -> Chalk
                    filled -> Accent
                    else -> Glass
                }
            )
            .border(
                width = 1.dp,
                color = if (focused || filled || !enabled) Color.Transparent else GlassEdge,
                shape = RoundedCornerShape(50),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        glyph(tint)
    }
}
