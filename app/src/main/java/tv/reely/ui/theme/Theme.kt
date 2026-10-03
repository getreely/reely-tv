package tv.reely.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import tv.reely.R

/*
 * Reely's palette: black, cool greys, and an electric royal blue, used sparingly — the
 * mark, progress, and anything that needs to be found. Errors have a red of their own,
 * so a problem never looks like a highlight.
 */
val Ink = Color(0xFF08090B)
val SurfaceRaised = Color(0xFF15171B)
val SurfaceHigh = Color(0xFF1F2329)
val Line = Color(0xFF2B3038)
val Chalk = Color(0xFFF2F4F7)
val Muted = Color(0xFFB3BAC4)
val Faint = Color(0xFF7F8792)
val Accent = Color(0xFF2E6BFF)
/** What's written on the blue: white reads on it, black doesn't quite. */
val OnAccent = Color(0xFFFFFFFF)
val Danger = Color(0xFFFF6B6B)
val Good = Color(0xFF8CBE6E)
val Warn = Color(0xFFE9A343)

// Frosted surfaces. Real blur is impossible on this hardware, so "glass" here means a
// translucent fill with a hairline edge, sitting over a stretched low-resolution backdrop.
val Glass = Color(0x8C1A1D22)
val GlassEdge = Color(0x24F2F4F7)

/**
 * Geist, bundled as four static weights. Static files rather than one variable font,
 * because Fire OS 6 is Android 7.1 and cannot pick a weight out of a variable one.
 */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/**
 * The type scale. Sizes are in sp on Android TV's 960 × 540 dp canvas, which every set
 * reports whatever its size, so each one is the same share of the screen on a 50-inch
 * and an 80-inch television alike.
 *
 * Nothing goes below [Label]. Text smaller than 14 sp stops being readable from a sofa.
 */
object ReelyType {
    /** A title with no title art to show instead. */
    val Display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.6).sp)
    /** A screen's own title. */
    val Headline = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp)
    /** Above a row of cards. */
    val RowTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp)
    /** A summary: the one place a viewer reads more than a line. */
    val Body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp)
    /** Details beside a title: season and episode, year, running time. */
    val Meta = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    /** Badges, captions, small print. The floor. */
    val Label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
}

private val ReelyColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Chalk,
    onSecondary = Ink,
    background = Ink,
    onBackground = Chalk,
    surface = SurfaceRaised,
    onSurface = Chalk,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    error = Danger,
    onError = Ink,
)

/** Every Material style, in Geist. Sizes set at a call site still win. */
private val ReelyTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Geist),
        displayMedium = base.displayMedium.copy(fontFamily = Geist),
        displaySmall = base.displaySmall.copy(fontFamily = Geist),
        headlineLarge = base.headlineLarge.copy(fontFamily = Geist),
        headlineMedium = base.headlineMedium.copy(fontFamily = Geist),
        headlineSmall = base.headlineSmall.copy(fontFamily = Geist),
        titleLarge = base.titleLarge.copy(fontFamily = Geist),
        titleMedium = base.titleMedium.copy(fontFamily = Geist),
        titleSmall = base.titleSmall.copy(fontFamily = Geist),
        bodyLarge = base.bodyLarge.copy(fontFamily = Geist),
        bodyMedium = base.bodyMedium.copy(fontFamily = Geist),
        bodySmall = base.bodySmall.copy(fontFamily = Geist),
        labelLarge = base.labelLarge.copy(fontFamily = Geist),
        labelMedium = base.labelMedium.copy(fontFamily = Geist),
        labelSmall = base.labelSmall.copy(fontFamily = Geist),
    )
}

@Composable
fun ReelyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ReelyColors, typography = ReelyTypography) {
        // Text with no colour or style of its own gets these. Left to the library it came
        // out black, and in the system font.
        CompositionLocalProvider(
            LocalContentColor provides Chalk,
            LocalTextStyle provides ReelyType.Meta,
            content = content,
        )
    }
}
