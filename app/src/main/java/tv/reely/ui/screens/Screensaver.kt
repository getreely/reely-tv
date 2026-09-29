package tv.reely.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import tv.reely.ui.components.clockTime
import tv.reely.ui.components.rememberNow
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/** One picture in the screensaver: the artwork, and what it's from. */
data class SaverSlide(val url: String, val title: String, val caption: String?)

/** How long each picture stays up, and how long one takes to fade into the next. */
internal const val SLIDE_MS = 12_000L
private const val FADE_MS = 2_000

/**
 * What comes up when nobody has touched the remote for a while and nothing is playing:
 * the library's artwork, one picture at a time, drifting slowly so nothing sits still on
 * the screen, with what each is from and the time. Any button puts it away.
 *
 * A picture is loaded before it's shown, so a slow server means the last one stays up a
 * little longer rather than a black gap.
 */
@Composable
fun Screensaver(slides: List<SaverSlide>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var index by remember(slides) { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Pair<SaverSlide, ImageBitmap>?>(null) }

    LaunchedEffect(slides) {
        if (slides.isEmpty()) return@LaunchedEffect
        while (true) {
            val slide = slides[index % slides.size]
            val request = ImageRequest.Builder(context).data(slide.url).allowHardware(false).build()
            val bitmap = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                shown = slide to bitmap.asImageBitmap()
                delay(SLIDE_MS)
            } else {
                // One that won't load is passed over, after a moment so a server that's
                // down isn't asked for every picture at once.
                delay(1_000)
            }
            index++
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Ink)) {
        Crossfade(targetState = shown, animationSpec = tween(FADE_MS), label = "slide") { current ->
            if (current != null) {
                // A slow push in, across the whole time it's up.
                val zoom = remember(current) { Animatable(1f) }
                LaunchedEffect(current) {
                    zoom.animateTo(1.08f, tween((SLIDE_MS + FADE_MS).toInt(), easing = LinearEasing))
                }
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = current.second,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom.value
                                scaleY = zoom.value
                            },
                    )
                    // Dark enough at the bottom for the words to read on any picture.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.55f to Ink.copy(alpha = 0f),
                                    1f to Ink.copy(alpha = 0.85f),
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 56.dp, bottom = 40.dp)
                            .widthIn(max = 640.dp),
                    ) {
                        Text(
                            text = current.first.title,
                            color = Chalk,
                            style = ReelyType.Headline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        current.first.caption?.let {
                            Text(text = it, color = Muted, style = ReelyType.Body, maxLines = 1)
                        }
                    }
                }
            }
        }

        val now = rememberNow()
        Text(
            text = clockTime(context, now),
            color = Chalk,
            fontSize = 44.sp,
            lineHeight = 48.sp,
            style = ReelyType.Display,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 36.dp),
        )
    }
}
