package tv.reely

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.badgeText
import tv.reely.ui.theme.ReelyTheme

/**
 * The new-episodes count on a poster: one circle the same size for every count, and the
 * number inside it on one line. 333 episodes of South Park wrapped its last 3 underneath.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
class BadgeTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `every count sits on one line inside the circle`() {
        val counts = listOf(9, 42, 333, 1500)
        compose.setContent {
            ReelyTheme {
                Row {
                    counts.forEach { count ->
                        PosterCard(title = "Show $count", subtitle = null, imageUrl = null, onClick = {}, badge = count)
                    }
                }
            }
        }
        for (text in listOf("9", "42", "333", "1K")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            assertEquals("\"$text\" on one line", 1, layout.lineCount)
            assertEquals("\"$text\" not cut short", false, layout.hasVisualOverflow)
        }
    }

    @Test fun `big counts are shortened, not squeezed`() {
        assertEquals("999", badgeText(999))
        assertEquals("1K", badgeText(1000))
        assertEquals("12K", badgeText(12_345))
    }
}
