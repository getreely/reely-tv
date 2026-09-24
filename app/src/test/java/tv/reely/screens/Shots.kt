package tv.reely.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import tv.reely.plex.PlexItem
import java.io.File
import kotlin.math.sin

/**
 * The shared half of the screenshot tests: sample artwork, sample titles, and a way to
 * save what a screen looks like. These are for looking at, not for passing or failing;
 * they run only with -Pscreenshots and write to app/build/screenshots.
 */
object Shots {
    const val QUALIFIERS = "w960dp-h540dp-land-television-xhdpi"

    fun onlyWhenAsked() = assumeTrue(System.getProperty("reely.screenshots") == "true")

    /**
     * Put the screen in remote mode, as the first press of a button does on a television.
     * The test machine starts in touch mode, where nothing clickable takes focus — so
     * without this no screenshot could show a focused card, which is half of what they
     * are for.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun RemoteInput() {
        val input = LocalInputModeManager.current
        LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
    }

    /** Pictures load straight away, so a capture never catches a half-drawn screen. */
    fun syncImages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).build())
    }

    fun save(compose: ComposeContentTestRule, name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val dir = System.getProperty("roborazzi.output.dir") ?: "build/screenshots"
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    // ---------------------------------------------------------------- artwork

    class Look(val sky0: Int, val sky1: Int, val sun: Int, val land: Int)

    private val dir by lazy { File(System.getProperty("java.io.tmpdir"), "reely-art").apply { mkdirs() } }

    /** A scene in the title's colours: sky, sun, hills. Posters also carry the title. */
    fun art(key: String, look: Look, w: Int, h: Int, title: String?): String {
        val file = File(dir, "$key-${w}x$h.png")
        if (file.exists()) return file.toURI().toString()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val horizon = h * .64f
        p.shader = LinearGradient(0f, 0f, 0f, horizon, look.sky0, look.sky1, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val sx = w * .68f; val sy = horizon * .72f; val sr = minOf(w, h) * .09f
        p.shader = RadialGradient(sx, sy, sr * 6, look.sun and 0x88FFFFFF.toInt(), look.sun and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = look.sun
        c.drawCircle(sx, sy, sr, p)
        for (layer in 0..2) {
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                val base = horizon + layer * h * .09f
                for (i in 0..60) {
                    val x = i / 60f * w
                    lineTo(x, base - (sin(i / 60f * 6.3f * (1.3f + layer) + key.length) * h * .05f).toFloat())
                }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.color = blend(look.sky1, look.land, .5f + layer * .25f)
            c.drawPath(path, p)
        }
        if (title != null) {
            p.color = 0xFFFFFFFF.toInt(); p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
            var size = w * .16f
            p.textSize = size
            while (p.measureText(title) > w * .84f && size > 8) { size -= 1f; p.textSize = size }
            p.setShadowLayer(size * .4f, 0f, 0f, 0x99000000.toInt())
            c.drawText(title, w / 2f, h * .88f, p)
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toURI().toString()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // ---------------------------------------------------------------- titles

    class Title(
        val key: String,
        val look: Look,
        val show: String? = null,
        val title: String,
        val s: Int? = null,
        val e: Int? = null,
        val year: Int? = null,
        val minutes: Int = 44,
        val progress: Float = 0f,
        val summary: String,
        /** How its logo is lettered, or null for a title with no logo, which shows as text. */
        val logo: LogoStyle? = null,
    )

    enum class LogoStyle { WORDMARK, SERIF }

    val titles = listOf(
        Title("north", Look(0xFF0E2233.toInt(), 0xFFC98A52.toInt(), 0xFFFFD89C.toInt(), 0xFF0A0F13.toInt()),
            show = "Northbound", title = "The Weigh Station", s = 2, e = 5, minutes = 42, progress = .57f,
            summary = "Rae takes the overnight haul through the pass. A stop at a lonely weigh station turns up a trailer nobody signed for.", logo = LogoStyle.WORDMARK),
        Title("harbor", Look(0xFF0A1330.toInt(), 0xFF35498A.toInt(), 0xFFFFCF73.toInt(), 0xFF060A16.toInt()),
            show = "Harbor Lights", title = "Low Water", s = 1, e = 8, minutes = 51, progress = .39f,
            summary = "The tide goes out further than anyone has seen it, and what it leaves on the mudflats puts the whole harbour under suspicion.", logo = LogoStyle.SERIF),
        Title("shift", Look(0xFF041A1A.toInt(), 0xFF1B5F5A.toInt(), 0xFFBFF7EE.toInt(), 0xFF020D0D.toInt()),
            show = "The Long Shift", title = "Code Grey", s = 4, e = 2, progress = .84f,
            summary = "A power cut takes the fourth floor dark in the middle of a double shift."),
        Title("quiet", Look(0xFF1A0E2C.toInt(), 0xFFA4507F.toInt(), 0xFFFFD3EA.toInt(), 0xFF0E0817.toInt()),
            show = "Quiet Hours", title = "Static", s = 1, e = 3, minutes = 38, progress = .42f,
            summary = "Jules runs the late phone-in show from an empty studio."),
        Title("salt", Look(0xFF15294A.toInt(), 0xFFEE7A47.toInt(), 0xFFFFE2A8.toInt(), 0xFF071120.toInt()),
            title = "Saltwater", year = 2025, minutes = 112, progress = .43f,
            summary = "Two estranged sisters sail their late father's boat down the coast.", logo = LogoStyle.WORDMARK),
        Title("ember", Look(0xFF1A0404.toInt(), 0xFFB8341F.toInt(), 0xFFFFB36B.toInt(), 0xFF0D0202.toInt()),
            title = "Ember Street", year = 2026, minutes = 118, summary = "A warehouse fire lights up the east side.", logo = LogoStyle.WORDMARK),
        Title("field", Look(0xFF2A2010.toInt(), 0xFFD6B04A.toInt(), 0xFFFFF0B8.toInt(), 0xFF130E05.toInt()),
            title = "Fieldwork", year = 2024, minutes = 101, summary = "One summer on a failing farm.", logo = LogoStyle.SERIF),
        Title("orbit", Look(0xFF020207.toInt(), 0xFF1A1240.toInt(), 0xFFC9B8FF.toInt(), 0xFF010104.toInt()),
            title = "Low Orbit", year = 2025, minutes = 131, summary = "Nine days to get home, and one seat too few.", logo = LogoStyle.WORDMARK),
        Title("glass", Look(0xFF06130E.toInt(), 0xFF2E6C57.toInt(), 0xFFD4FFE9.toInt(), 0xFF030A07.toInt()),
            title = "Glasshouse", year = 2024, minutes = 96, summary = "A greenhouse locked for thirty years."),
        Title("ferry", Look(0xFF02050E.toInt(), 0xFF18305A.toInt(), 0xFFDFEAFF.toInt(), 0xFF01030A.toInt()),
            title = "Midnight Ferry", year = 2025, minutes = 109, summary = "Eight passengers board. Seven get off."),
        Title("cardinal", Look(0xFF1B2230.toInt(), 0xFF95A8BD.toInt(), 0xFFFFFFFF.toInt(), 0xFF0D1119.toInt()),
            title = "Cardinal", year = 2026, minutes = 115, summary = "One last climb, and the weather turns."),
    ).associateBy { it.key }

    /** Image paths are the title's key, which [imageUrl] turns into a painted file. */
    fun item(key: String): PlexItem {
        val t = titles.getValue(key)
        val episode = t.show != null
        return PlexItem(
            ratingKey = key,
            title = t.title,
            type = if (episode) "episode" else "movie",
            thumb = "poster/$key",
            art = "backdrop/$key",
            logo = t.logo?.let { "logo/$key" },
            summary = t.summary,
            year = t.year,
            index = t.e,
            parentIndex = t.s,
            parentRatingKey = null,
            parentTitle = null,
            grandparentRatingKey = if (episode) "show-$key" else null,
            grandparentTitle = t.show,
            grandparentThumb = if (episode) "poster/$key" else null,
            durationMs = t.minutes * 60_000L,
            viewOffsetMs = (t.minutes * 60_000L * t.progress).toLong(),
            leafCount = 0,
            viewedLeafCount = 0,
            viewCount = 0,
            addedAt = 0,
            librarySectionId = "1",
            serverBase = "http://server",
        )
    }

    fun imageUrl(path: String?, w: Int, h: Int): String? {
        val (kind, key) = path?.split('/')?.takeIf { it.size == 2 } ?: return null
        val t = titles[key] ?: return null
        return when (kind) {
            "poster" -> art("$key-poster", t.look, w, h, t.show ?: t.title)
            "logo" -> logo(key, t)
            else -> art("$key-backdrop", t.look, w, h, null)
        }
    }

    /** A title logo: the name lettered on a transparent ground, cropped to the lettering. */
    private fun logo(key: String, t: Title): String? {
        val style = t.logo ?: return null
        val file = File(dir, "$key-logo.png")
        if (file.exists()) return file.toURI().toString()
        val name = t.show ?: t.title
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 120f
            typeface = when (style) {
                LogoStyle.WORDMARK -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                LogoStyle.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            }
            if (style == LogoStyle.WORDMARK) letterSpacing = 0.08f
        }
        val text = if (style == LogoStyle.WORDMARK) name.uppercase() else name
        val bounds = android.graphics.Rect().also { p.getTextBounds(text, 0, text.length, it) }
        val pad = 8
        val bmp = Bitmap.createBitmap(bounds.width() + pad * 2, bounds.height() + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, (pad - bounds.left).toFloat(), (pad - bounds.top).toFloat(), p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toURI().toString()
    }
}
