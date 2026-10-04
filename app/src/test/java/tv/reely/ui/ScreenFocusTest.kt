package tv.reely.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.components.FollowRemovals
import tv.reely.ui.components.LocalScreenFocus
import tv.reely.ui.components.ScreenFocus
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.restoreFocusTo
import tv.reely.ui.components.rowItem

/**
 * Coming back to a screen finds the cursor where it was, and a card that goes from under
 * the cursor hands it to the one beside it. Both used to send it to the top of the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class ScreenFocusTest {
    @get:Rule val compose = createComposeRule()

    private val screens = mutableMapOf<String, ScreenFocus>()
    private var route by mutableStateOf("home")
    private var rows by mutableStateOf(List(6) { r -> "row$r" to List(6) { "r$r-c$it" } })

    private fun app() {
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            Column {
                // The tab row, which stays while the screen under it changes.
                Box(Modifier.size(10.dp).testTag("tab").focusable())
                saved.SaveableStateProvider(route) {
                    CompositionLocalProvider(LocalScreenFocus provides screens.getOrPut(route) { ScreenFocus() }) {
                        if (route == "home") Home() else Box(Modifier.size(10.dp).testTag("elsewhere").focusable())
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Home() {
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.first }) { (id, cards) ->
                val row = rememberRowFocus(id)
                FollowRemovals(row)
                LazyRow(Modifier.height(200.dp).restoreFocusTo(row)) {
                    itemsIndexed(cards, key = { _, it -> it }) { index, card ->
                        Box(
                            rowItem(row, card, index)
                                .size(150.dp)
                                .testTag(card)
                                .onFocusChanged { if (it.isFocused) row.onFocused(card) }
                                .focusable(),
                        )
                    }
                }
            }
        }
    }

    @Test fun `back to a screen, the cursor is on the card it was on`() {
        app()
        compose.onNodeWithTag("r2-c3").performScrollAndFocus()
        route = "detail"
        compose.waitForIdle()
        route = "home"
        compose.waitForIdle()
        val restored = compose.runOnIdle { runBlocking { screens.getValue("home").restore() } }
        compose.waitForIdle()
        assertTrue(restored)
        compose.onNodeWithTag("r2-c3").assertIsFocused()
    }

    @Test fun `a card that goes from under the cursor hands it to the next`() {
        app()
        compose.onNodeWithTag("r1-c2").performScrollAndFocus()
        rows = rows.map { (id, cards) -> id to if (id == "row1") cards - "r1-c2" else cards }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("r1-c3").assertIsFocused()
    }

    @Test fun `the last card going hands it to the one before`() {
        app()
        compose.onNodeWithTag("r0-c5").performScrollAndFocus()
        rows = rows.map { (id, cards) -> id to if (id == "row0") cards - "r0-c5" else cards }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("r0-c4").assertIsFocused()
    }

    /**
     * Up from a card to the tabs and straight along them, past Home and back: the cards
     * going with the screen are not cards taken from under the cursor, and Home coming
     * back must leave the cursor on the tabs. It went to the first card of Continue
     * Watching, and had to be brought back up to get anywhere.
     */
    @Test fun `along the tabs past Home, the cursor stays on the tabs`() {
        app()
        compose.onNodeWithTag("r0-c1").performScrollAndFocus()
        compose.onNodeWithTag("tab").performScrollAndFocus()
        route = "movies"
        compose.waitForIdle()
        route = "home"
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("tab").assertIsFocused()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollAndFocus() {
        requestFocus()
        compose.waitForIdle()
        assertIsFocused()
    }
}
