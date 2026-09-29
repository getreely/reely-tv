package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.screens.Shots
import tv.reely.ui.screens.PersonScreen
import tv.reely.ui.screens.countLine
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** Someone from a cast, and what else of theirs is on the server. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PersonScreenTest {
    @get:Rule val compose = createComposeRule()

    private val films = listOf("orbit", "ember", "field", "glass", "ferry", "cardinal").map(Shots::item)

    @Test fun `their titles, and OK opens one`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    PersonScreen(
                        state = PersonState(Route.Person("42", "Rae Collins"), items = films, busy = false),
                        imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                        onOpenItem = { opened += it.ratingKey },
                    )
                }
            }
        }
        compose.onNodeWithText("Rae Collins").assertExists()
        Shots.saveIfAsked(compose, "person")
        compose.onNode(hasText(films[1].title) and isFocusable()).performClick()
        assertEquals(listOf(films[1].ratingKey), opened)
    }

    @Test fun `nothing else of theirs says so`() {
        compose.setContent {
            ReelyTheme {
                PersonScreen(
                    state = PersonState(Route.Person("42", "Rae Collins"), busy = false),
                    imageUrl = { _, _, _, _ -> null },
                    onOpenItem = {},
                )
            }
        }
        compose.onNodeWithText("Nothing else with Rae Collins is on this server.").assertExists()
    }

    @Test fun `the count line`() {
        val show = films[0].copy(type = "show")
        assertEquals("1 film on your server", countLine(films.take(1)))
        assertEquals("2 films and 1 show on your server", countLine(films.take(2) + show))
        assertEquals("Nothing on your server", countLine(emptyList()))
    }
}
