package tv.reely.ui.touch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import tv.reely.ui.components.CardAction
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Geist
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.ui.theme.SurfaceRaised

/*
 * The phone and tablet screens' own pieces: the same palette and lettering as the
 * television's, sized for a finger rather than for across the room, and pressed rather
 * than focused. The television's screens use none of this.
 */

private val TouchColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Chalk,
    onSecondary = Ink,
    background = Ink,
    onBackground = Chalk,
    surface = Ink,
    onSurface = Chalk,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = SurfaceRaised,
    surfaceContainerLow = SurfaceRaised,
    surfaceContainerHigh = SurfaceHigh,
    secondaryContainer = SurfaceHigh,
    onSecondaryContainer = Chalk,
    outline = Line,
    error = tv.reely.ui.theme.Danger,
    onError = Ink,
)

/** Material's touch components in Reely's colours and Geist. */
@Composable
fun TouchTheme(content: @Composable () -> Unit) {
    val base = androidx.compose.material3.Typography()
    MaterialTheme(
        colorScheme = TouchColors,
        typography = androidx.compose.material3.Typography(
            displaySmall = base.displaySmall.copy(fontFamily = Geist, fontWeight = FontWeight.Bold),
            headlineMedium = base.headlineMedium.copy(fontFamily = Geist, fontWeight = FontWeight.Bold),
            headlineSmall = base.headlineSmall.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.copy(fontFamily = Geist),
            bodyLarge = base.bodyLarge.copy(fontFamily = Geist),
            bodyMedium = base.bodyMedium.copy(fontFamily = Geist),
            bodySmall = base.bodySmall.copy(fontFamily = Geist),
            labelLarge = base.labelLarge.copy(fontFamily = Geist),
            labelMedium = base.labelMedium.copy(fontFamily = Geist),
            labelSmall = base.labelSmall.copy(fontFamily = Geist),
        ),
        content = content,
    )
}

/** Side margin on a phone; a tablet's screens keep the same, and fit more across. */
val TouchMargin = 16.dp

/**
 * How many posters a grid has across: three on a phone, as Plex and the like have them,
 * rather than two that fill the screen; as many as fit at a comfortable size on a tablet
 * or a phone turned sideways.
 */
@Composable
fun touchPosterColumns(): androidx.compose.foundation.lazy.grid.GridCells {
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return if (width < 600) androidx.compose.foundation.lazy.grid.GridCells.Fixed(3)
    else androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 128.dp)
}

/** A poster, pressed to open and held for its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TouchPoster(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    progress: Float? = null,
    watched: Boolean = false,
    tag: String? = null,
    badge: Int? = null,
    /** Null to fill the space it's given, as in a grid. */
    width: Dp? = 112.dp,
) {
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(imageUrl, ratio = 2f / 3f, progress = progress, watched = watched, tag = tag, badge = badge)
        CardText(title, subtitle)
    }
}

/** A wide picture: an episode's still, a channel, Continue Watching. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TouchWide(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    progress: Float? = null,
    watched: Boolean = false,
    tag: String? = null,
    width: Dp = 220.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(imageUrl, ratio = 16f / 9f, progress = progress, watched = watched, tag = tag)
        CardText(title, subtitle)
    }
}

@Composable
fun Artwork(
    imageUrl: String?,
    ratio: Float,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    watched: Boolean = false,
    tag: String? = null,
    badge: Int? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceHigh),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (tag != null) {
            Text(
                text = tag,
                color = Chalk,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (badge != null && badge > 1) {
            Text(
                text = tv.reely.ui.components.badgeText(badge),
                color = tv.reely.ui.theme.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (watched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                tv.reely.ui.components.CheckGlyph(tv.reely.ui.theme.OnAccent, size = 11.dp)
            }
        }
        if (progress != null && progress > 0f && !watched) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Ink.copy(alpha = 0.6f)),
            ) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Accent))
            }
        }
    }
}

@Composable
private fun CardText(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Chalk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A titled row that runs off to the right, edge to edge. */
@Composable
fun TouchRow(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TouchMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Chalk,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = TouchMargin),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A choice that's on or off, in a row of them. */
@Composable
fun TouchChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = SurfaceRaised,
            labelColor = Chalk,
            selectedContainerColor = Chalk,
            selectedLabelColor = Ink,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Line,
            selectedBorderColor = Chalk,
        ),
    )
}

/** The one thing a screen is for: Play, Sign in, Connect. */
@Composable
fun TouchPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Chalk, contentColor = Ink),
    ) {
        if (icon != null) {
            icon(Ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** Everything beside it. */
@Composable
fun TouchSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Chalk),
    ) {
        if (icon != null) {
            icon(Chalk)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

/** A round button with a glyph and a word under it, for a title's page. */
@Composable
fun TouchIconAction(label: String, onClick: () -> Unit, glyph: @Composable (Color) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).border(1.dp, Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { glyph(Chalk) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
    }
}


/** Something to say when there's nothing to show, or something went wrong. */
@Composable
fun TouchNote(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = TouchMargin, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
        action?.let { (label, onClick) -> TouchSecondaryButton(label, onClick) }
    }
}

/** An error that can be put away. */
@Composable
fun TouchError(message: String, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TouchMargin, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tv.reely.ui.theme.Danger.copy(alpha = 0.14f))
            .border(1.dp, tv.reely.ui.theme.Danger.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Chalk, modifier = Modifier.weight(1f))
        if (onDismiss != null) {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Dismiss", color = Chalk) }
        }
    }
}

/** A title's actions, from holding its poster: the same list the television's menu offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchActionSheet(
    title: String,
    subtitle: String?,
    actions: List<CardAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceRaised,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Chalk,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(10.dp))
            actions.forEach { action ->
                TouchListRow(
                    title = action.label,
                    leading = action.icon?.let { icon -> { icon(if (action.emphasised) Accent else Chalk) } },
                    onClick = {
                        onDismiss()
                        action.onSelect()
                    },
                )
            }
        }
    }
}

/** A row in a list: a setting, a menu entry, a channel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TouchListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Chalk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1)
        trailing?.invoke()
    }
}

/** A heading over a group of rows. */
@Composable
fun TouchSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = Faint,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------- Tab glyphs

@Composable
fun HomeTabGlyph(color: Color, size: Dp = 22.dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val stroke = Stroke(width = w * 0.09f)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.12f, h * 0.48f)
        lineTo(w * 0.5f, h * 0.14f)
        lineTo(w * 0.88f, h * 0.48f)
        moveTo(w * 0.22f, h * 0.4f)
        lineTo(w * 0.22f, h * 0.86f)
        lineTo(w * 0.78f, h * 0.86f)
        lineTo(w * 0.78f, h * 0.4f)
    }
    drawPath(path, color, style = stroke)
}

@Composable
fun FilmTabGlyph(color: Color, size: Dp = 22.dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val stroke = Stroke(width = w * 0.09f)
    drawRoundRect(
        color,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.12f),
        size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.76f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
        style = stroke,
    )
    for (y in listOf(0.3f, 0.5f, 0.7f)) {
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.14f, h * y), androidx.compose.ui.geometry.Offset(w * 0.3f, h * y), w * 0.07f)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.7f, h * y), androidx.compose.ui.geometry.Offset(w * 0.86f, h * y), w * 0.07f)
    }
}

@Composable
fun ShowTabGlyph(color: Color, size: Dp = 22.dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val stroke = Stroke(width = w * 0.09f)
    drawRoundRect(
        color,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.18f),
        size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.56f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
        style = stroke,
    )
    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.88f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.88f), w * 0.09f)
}

@Composable
fun LiveTabGlyph(color: Color, size: Dp = 22.dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val stroke = Stroke(width = w * 0.09f)
    drawCircle(color, radius = w * 0.08f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f))
    drawArc(color, 135f, 90f, false, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.24f), androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.52f), style = stroke)
    drawArc(color, -45f, 90f, false, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.24f), androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.52f), style = stroke)
    drawArc(color, 135f, 90f, false, androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.88f), style = stroke)
    drawArc(color, -45f, 90f, false, androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.88f), style = stroke)
}

@Composable
fun RequestTabGlyph(color: Color, size: Dp = 22.dp) = tv.reely.ui.components.PlusGlyph(color, size)

/**
 * A page's picture across the top: 16:10 of the width in portrait, but no taller than
 * [HERO_MAX] — held sideways, or on a tablet, it would otherwise fill the screen.
 */
@Composable
fun Modifier.heroHeight(): Modifier {
    // Half the screen at most: sideways, a picture any taller leaves nothing else in view.
    val screenHalf = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
    return heroHeight(minOf(HERO_MAX, screenHalf))
}

private fun Modifier.heroHeight(max: Dp): Modifier = this
    .fillMaxWidth()
    .then(
        Modifier.layout { measurable, constraints ->
            val width = constraints.maxWidth
            val height = minOf(width * 10 / 16, max.roundToPx())
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }
    )

private val HERO_MAX = 320.dp
