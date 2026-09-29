package tv.reely.screens

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.screens.SaverSlide
import tv.reely.ui.screens.Screensaver
import tv.reely.ui.theme.ReelyTheme

/** The screensaver: a picture from the library, what it's from, and the time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class ScreensaverShots {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked(); Shots.syncImages() }

    @Test fun saver() {
        val slides = listOf("north", "harbor").map { key ->
            val t = Shots.titles.getValue(key)
            SaverSlide(Shots.imageUrl("backdrop/$key", 1920, 1080)!!, t.show ?: t.title, "2024")
        }
        // The slideshow never stops, so the clock is moved by hand rather than left to
        // wait for a stillness that doesn't come.
        compose.mainClock.autoAdvance = false
        compose.setContent { ReelyTheme { Screensaver(slides) } }
        compose.mainClock.advanceTimeBy(3_000)
        Shots.save(compose, "screensaver", settleMs = 500)
    }
}
