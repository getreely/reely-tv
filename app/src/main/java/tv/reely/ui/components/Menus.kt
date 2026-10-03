package tv.reely.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.GlassEdge
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/*
 * The one way this app draws a menu, from a poster's held-OK options to the player's
 * subtitles and a setting's choices.
 *
 * It's the shape the big streaming apps settled on for a television: a panel that slides
 * in from the right edge and runs the full height of the screen, over a shade that darkens
 * towards it, with a plain list of full-width rows. The row the cursor is on turns white
 * with dark text; the rest are bare. There's no Cancel at the bottom: Back closes it, as
 * it closes everything else.
 *
 * Menus used to be boxes in the middle of the screen with a stack of pill buttons in
 * them, each sized to its own words, which read as a form rather than a menu.
 */

/** The panel's fill: the app's own near-black, just short of opaque. */
private val PanelFill = Color(0xF7101216)

/** How wide a menu panel is unless it says otherwise. */
val MENU_WIDTH = 460.dp

/**
 * The shade behind a menu: light on the left, where the screen can still be seen, and
 * dark by the panel so its edge reads. [strength] darkens it all, for a busy screen.
 */
@Composable
fun MenuScrim(modifier: Modifier = Modifier, strength: Float = 1f) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Ink.copy(alpha = 0.25f * strength),
                    0.55f to Ink.copy(alpha = 0.55f * strength),
                    1f to Ink.copy(alpha = 0.9f * strength),
                )
            ),
    )
}

/**
 * The panel itself: the full height of whatever it's placed in, [width] wide, sliding in
 * from the right as it arrives. The caller positions it (usually CenterEnd) and adds its
 * own focus handling; this is how it looks.
 */
@Composable
fun MenuPanel(
    modifier: Modifier = Modifier,
    width: Dp = MENU_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    val arrival = remember { Animatable(1f) }
    LaunchedEffect(Unit) { arrival.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
    /*
     * Most menus here are opened by holding OK, so they arrive with OK still down: the
     * repeats of that hold, and its release, land on the first row and press it. Every
     * panel throws the rest of that hold away, and takes OK only once it's pressed
     * afresh. A menu opened by a plain press never sees any of this.
     */
    val stray = remember { StraySelect() }
    Column(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (!event.isSelect()) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> stray.down(event.nativeKeyEvent.repeatCount)
                    KeyEventType.KeyUp -> stray.up()
                    else -> false
                }
            }
            .fillMaxHeight()
            .width(width)
            .graphicsLayer {
                translationX = arrival.value * 64.dp.toPx()
                alpha = 1f - arrival.value
            }
            .background(PanelFill)
            .drawBehind {
                // A hairline down the inner edge, so the panel ends rather than fades.
                drawLine(GlassEdge, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 28.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A menu's title and, under it, a line saying what it's about. */
@Composable
fun MenuHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = Chalk,
            style = ReelyType.Headline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(text = subtitle, color = Muted, style = ReelyType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A title at the top of its menu: a small picture of it on the left, with how far along
 * it is under the picture, and beside it its logo (or its name) and what it is.
 *
 * The picture used to run the width of the panel. On a television that took a third of
 * the panel's height and pushed the last options off the bottom, where they couldn't be
 * seen; the options are what the menu is for.
 */
@Composable
fun MenuArtHeader(
    title: String,
    meta: String?,
    imageUrl: String?,
    logoUrl: String? = null,
    progress: Float? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(ART_WIDTH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Chalk.copy(alpha = 0.06f)),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Ink.copy(alpha = 0.6f)),
                ) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(Accent))
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (logoUrl != null) {
                // The title in its own lettering.
                AsyncImage(
                    model = logoUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(36.dp).fillMaxWidth(),
                )
            } else {
                Text(
                    text = title,
                    color = Chalk,
                    style = ReelyType.Headline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!meta.isNullOrBlank()) {
                Text(text = meta, color = Muted, style = ReelyType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** How wide the picture at the top of a title's menu is. */
private val ART_WIDTH = 150.dp

/** A small heading inside a menu, over the rows that belong to it. */
@Composable
fun MenuSection(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label.uppercase(),
        color = Faint,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * One row of a menu: an optional glyph, the words, and on the right either a value
 * ("On", "125%") or a check when it's the one chosen. White with dark text under the
 * cursor, bare otherwise.
 */
@Composable
fun MenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn in the colour it's handed, which changes with focus. */
    icon: (@Composable (Color) -> Unit)? = null,
    detail: String? = null,
    value: String? = null,
    checked: Boolean = false,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1.02f else 1f, tween(120), label = "lift")
    val text = if (focused) Ink else Chalk.copy(alpha = 0.92f)
    val quiet = if (focused) Ink.copy(alpha = 0.62f) else Muted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Chalk else Color.Transparent)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            // At least a glyph's width; a picture (a chapter's, say) takes what it needs.
            Box(modifier = Modifier.widthIn(min = 24.dp).heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                icon(if (focused) Ink else Chalk.copy(alpha = 0.8f))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                color = text,
                fontSize = 18.sp,
                lineHeight = 23.sp,
                fontWeight = if (checked || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(text = detail, color = quiet, style = ReelyType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(text = value, color = quiet, style = ReelyType.Meta, maxLines = 1)
        }
        if (checked) {
            CheckGlyph(color = if (focused) Ink else Accent, size = 20.dp)
        }
    }
}

/**
 * The card a hold of OK was on, so a menu that opens from it can put the cursor back when
 * it closes. Every card records itself here as its hold fires; see cardPress.
 */
class HeldCard {
    var requester: FocusRequester? = null
}

val LocalHeldCard = staticCompositionLocalOf { HeldCard() }
