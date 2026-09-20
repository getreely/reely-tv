package tv.reely.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
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
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceHigh

/** The resume bar Plex draws across the bottom of a half-watched thing. */
@Composable
fun ProgressStrip(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(Accent),
        )
    }
}

/**
 * The landscape card used for Continue Watching, where knowing which episode you are on
 * matters more than the poster art.
 */
@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "wide-scale")

    Column(
        modifier = modifier
            .width(268.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(
                    width = 2.dp,
                    color = if (focused) Accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (progress != null) {
                ProgressStrip(progress, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            text = title,
            color = if (focused) Parchment else Muted,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = Faint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A poster. The badge carries the "six new episodes" count, so a show that dropped a
 * whole season reads as one arrival rather than filling the row.
 */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int? = null,
    progress: Float? = null,
    watched: Boolean = false,
    onFocus: () -> Unit = {},
    width: androidx.compose.ui.unit.Dp = 132.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster-scale")

    Column(
        modifier = modifier
            .width(width)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(
                    width = 2.dp,
                    color = if (focused) Accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = title.take(1).uppercase(),
                    color = Faint,
                    fontSize = 34.sp,
                    lineHeight = 42.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Watched is marked in green: "finished" must never compete with "focused".
            if (watched && (badge == null || badge <= 1)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(tv.reely.ui.theme.Good),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckGlyph(color = Ink, size = 15.dp)
                }
            }

            if (badge != null && badge > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badge.toString(),
                        color = Ink,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (progress != null) {
                ProgressStrip(progress, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
        // Every card is exactly the same height. Ragged bottoms make Compose's downward
        // focus search pick a sibling further along the row instead of the row below.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(POSTER_CAPTION_HEIGHT)
                .padding(top = 7.dp),
        ) {
            Text(
                text = title,
                color = if (focused) Parchment else Muted,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle.orEmpty(),
                color = Faint,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val POSTER_CAPTION_HEIGHT = 40.dp
private val EPISODE_CAPTION_HEIGHT = 44.dp

/** A face from the cast list. Not focusable: this is information, not a destination. */
@Composable
fun CastCircle(
    name: String,
    role: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(108.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = name.take(1).uppercase(), color = Faint, fontSize = 26.sp, lineHeight = 32.sp)
            }
        }
        Text(
            text = name,
            color = Parchment,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (role != null) {
            Text(
                text = role,
                color = Faint,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An episode line on a show page: still, title, runtime and the description. */
@Composable
fun EpisodeRow(
    number: String,
    title: String,
    description: String?,
    duration: String?,
    imageUrl: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
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
            if (progress != null) {
                ProgressStrip(progress, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(number.takeIf { it.isNotBlank() }, title).joinToString(". "),
                color = Parchment,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (duration != null) {
                Text(
                    text = duration,
                    color = Faint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (description != null) {
                Text(
                    text = description,
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * An episode in a rail. Deliberately lean — still, number, title, runtime — because the
 * description belongs in the text block above, where it has room to be read.
 */
@Composable
fun EpisodeTile(
    number: String,
    title: String,
    duration: String?,
    imageUrl: String?,
    progress: Float?,
    watched: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * This is the episode the page's buttons act on. It keeps a marker even when focus
     * has gone up to those buttons, so it is never a mystery which episode is about to
     * be restarted or marked watched.
     */
    selected: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "episode-scale")
    val marked = focused || selected

    Column(
        modifier = modifier
            .width(186.dp)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(
                    width = if (focused) 2.dp else 3.dp,
                    color = when {
                        focused -> Accent
                        // Dimmer and thicker, so a held selection never reads as focus.
                        selected -> Accent.copy(alpha = 0.55f)
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(tv.reely.ui.theme.Good),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckGlyph(color = Ink, size = 15.dp)
                }
            }
            if (progress != null) {
                ProgressStrip(progress, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(EPISODE_CAPTION_HEIGHT)
                .padding(top = 7.dp),
        ) {
            Text(
                text = listOfNotNull(number.takeIf { it.isNotBlank() }, title).joinToString(". "),
                color = if (marked) Parchment else Muted,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = duration.orEmpty(),
                color = Faint,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * A live channel as a search result. Providers are erratic about logos, so the tile falls
 * back to the channel's initials on a colour derived from its id — which at least stays
 * the same channel-to-channel.
 */
@Composable
fun ChannelCard(
    name: String,
    number: Int,
    logoUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "channel-scale")

    Column(
        modifier = modifier
            .width(132.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(channelTint(name))
                .border(
                    width = 2.dp,
                    color = if (focused) Accent else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Text(
                    text = name.take(3).uppercase(),
                    color = Parchment,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // Fixed height, so a row of these lines up and down-arrow lands where it should.
        Column(modifier = Modifier.height(40.dp).padding(top = 6.dp)) {
            Text(
                text = name,
                color = if (focused) Parchment else Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (number > 0) "Channel $number" else "Live",
                color = Faint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
            )
        }
    }
}
