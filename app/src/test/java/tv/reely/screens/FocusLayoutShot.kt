package tv.reely.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.Text
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.screens.FocusLayout
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** Multiview's focus layout: the channel with the cursor large, in its own place in the line. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class FocusLayoutShot {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked() }

    private val colours = listOf(Color(0xFF2B4C7E), Color(0xFF7E2B3F), Color(0xFF2B7E5A), Color(0xFF7E6A2B))

    private fun shot(slots: Int, focused: Int) {
        compose.setContent {
            ReelyTheme {
                FocusLayout(slots = slots, focused = focused, modifier = Modifier.fillMaxSize().background(Ink)) { index ->
                    Box(
                        Modifier.fillMaxSize().background(colours[index])
                            .border(if (index == focused) 3.dp else 0.dp, Color.White),
                        contentAlignment = Alignment.Center,
                    ) { Text("${index + 1}", color = Color.White, fontSize = 40.sp) }
                }
            }
        }
        Shots.save(compose, "focus-layout-$slots-$focused", settleMs = 100)
    }

    @Test fun twoFirst() = shot(2, 0)
    @Test fun twoSecond() = shot(2, 1)
    @Test fun fourSecond() = shot(4, 1)
}
