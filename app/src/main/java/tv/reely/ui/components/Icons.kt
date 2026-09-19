package tv.reely.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs this app needs, drawn rather than imported. A whole icon
 * dependency for five shapes is not worth the download on a stick.
 */

@Composable
fun PlayGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val path = Path().apply {
            moveTo(size.toPx() * 0.26f, size.toPx() * 0.16f)
            lineTo(size.toPx() * 0.84f, size.toPx() * 0.5f)
            lineTo(size.toPx() * 0.26f, size.toPx() * 0.84f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun CheckGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        val path = Path().apply {
            moveTo(s * 0.2f, s * 0.52f)
            lineTo(s * 0.42f, s * 0.74f)
            lineTo(s * 0.8f, s * 0.26f)
        }
        drawPath(path, color, style = Stroke(width = s * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
fun InfoGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        drawCircle(color, radius = s * 0.42f, style = Stroke(width = s * 0.1f))
        drawCircle(color, radius = s * 0.055f, center = center.copy(y = center.y - s * 0.19f))
        drawLine(
            color,
            start = center.copy(y = center.y - s * 0.03f),
            end = center.copy(y = center.y + s * 0.22f),
            strokeWidth = s * 0.11f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** A play mark inside a frame: the film-trailer shape. */
@Composable
fun TrailerGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.2f),
            size = androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
            style = Stroke(width = s * 0.1f),
        )
        val path = Path().apply {
            moveTo(s * 0.4f, s * 0.36f)
            lineTo(s * 0.66f, s * 0.5f)
            lineTo(s * 0.4f, s * 0.64f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun SearchGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        drawCircle(
            color,
            radius = s * 0.3f,
            center = center.copy(x = s * 0.42f, y = s * 0.42f),
            style = Stroke(width = s * 0.12f),
        )
        drawLine(
            color,
            start = androidx.compose.ui.geometry.Offset(s * 0.63f, s * 0.63f),
            end = androidx.compose.ui.geometry.Offset(s * 0.86f, s * 0.86f),
            strokeWidth = s * 0.12f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
fun PauseGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        val bar = s * 0.17f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.27f, s * 0.18f),
            size = androidx.compose.ui.geometry.Size(bar, s * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.4f),
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.56f, s * 0.18f),
            size = androidx.compose.ui.geometry.Size(bar, s * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.4f),
        )
    }
}

/** A triangle against a bar: the jump to the next or previous thing. */
@Composable
fun SkipGlyph(color: Color, forward: Boolean, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        val barWidth = s * 0.11f
        val triangle = Path().apply {
            if (forward) {
                moveTo(s * 0.16f, s * 0.2f)
                lineTo(s * 0.62f, s * 0.5f)
                lineTo(s * 0.16f, s * 0.8f)
            } else {
                moveTo(s * 0.84f, s * 0.2f)
                lineTo(s * 0.38f, s * 0.5f)
                lineTo(s * 0.84f, s * 0.8f)
            }
            close()
        }
        drawPath(triangle, color)
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(
                if (forward) s * 0.68f else s * 0.21f,
                s * 0.2f,
            ),
            size = androidx.compose.ui.geometry.Size(barWidth, s * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f),
        )
    }
}

/** A speaker cone with two waves — the audio track picker. */
@Composable
fun SpeakerGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        val cone = Path().apply {
            moveTo(s * 0.1f, s * 0.36f)
            lineTo(s * 0.26f, s * 0.36f)
            lineTo(s * 0.46f, s * 0.18f)
            lineTo(s * 0.46f, s * 0.82f)
            lineTo(s * 0.26f, s * 0.64f)
            lineTo(s * 0.1f, s * 0.64f)
            close()
        }
        drawPath(cone, color)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.26f),
            size = androidx.compose.ui.geometry.Size(s * 0.34f, s * 0.48f),
            style = Stroke(width = s * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.4f, s * 0.14f),
            size = androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.72f),
            style = Stroke(width = s * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** A speech bubble carrying two lines: the subtitle picker. */
@Composable
fun SubtitleGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = size.toPx()
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.18f),
            size = androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.54f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.16f),
            style = Stroke(width = s * 0.09f),
        )
        // The bubble's tail.
        val tail = Path().apply {
            moveTo(s * 0.28f, s * 0.7f)
            lineTo(s * 0.28f, s * 0.9f)
            lineTo(s * 0.48f, s * 0.7f)
            close()
        }
        drawPath(tail, color)
        drawLine(
            color,
            start = androidx.compose.ui.geometry.Offset(s * 0.24f, s * 0.38f),
            end = androidx.compose.ui.geometry.Offset(s * 0.62f, s * 0.38f),
            strokeWidth = s * 0.085f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color,
            start = androidx.compose.ui.geometry.Offset(s * 0.24f, s * 0.53f),
            end = androidx.compose.ui.geometry.Offset(s * 0.76f, s * 0.53f),
            strokeWidth = s * 0.085f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
