package tv.reely.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Follows reely's own palette so the family looks like one thing.
val Ink = Color(0xFF110D0E)
val SurfaceRaised = Color(0xFF1B1618)
val SurfaceHigh = Color(0xFF241D20)
val Line = Color(0xFF372C30)
val Parchment = Color(0xFFE8DCCE)
val Muted = Color(0xFFA99BA0)
val Faint = Color(0xFF6E6166)
val Accent = Color(0xFFD85A64)
val Good = Color(0xFF8CBE6E)
val Warn = Color(0xFFE9A343)

private val ReelyColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Parchment,
    onSecondary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = SurfaceRaised,
    onSurface = Parchment,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    error = Accent,
    onError = Ink,
)

@Composable
fun ReelyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ReelyColors, content = content)
}
