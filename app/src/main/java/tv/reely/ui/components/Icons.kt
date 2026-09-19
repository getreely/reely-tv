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
