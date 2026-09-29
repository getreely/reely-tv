package tv.reely.screens

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.ui.screens.FocusLayout
import tv.reely.ui.screens.MultiViewGrid
import tv.reely.ui.screens.focusRects
import tv.reely.ui.screens.gridRects

/**
 * A tile's picture is built once. Building it again each time the cursor moved gave each
 * channel a new video surface, green until the stream's next full frame, and with four up
 * it was enough to bring the app down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TileSurfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `moving the cursor in the focus layout keeps every tile's view`() {
        var built = 0
        var released = 0
        var focused by mutableIntStateOf(0)
        compose.setContent {
            FocusLayout(slots = 4, focused = focused, modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context -> built++; View(context) },
                    onRelease = { released++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        assertEquals(4, built)
        listOf(1, 2, 3, 2, 1, 0).forEach {
            focused = it
            compose.waitForIdle()
        }
        assertEquals(4, built)
        assertEquals(0, released)
    }

    @Test
    fun `adding a channel to the grid keeps the ones already up`() {
        var built = 0
        var slots by mutableIntStateOf(2)
        compose.setContent {
            MultiViewGrid(slots = slots, modifier = Modifier.fillMaxSize()) {
                AndroidView(factory = { context -> built++; View(context) }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        slots = 3
        compose.waitForIdle()
        slots = 4
        compose.waitForIdle()
        assertEquals(4, built)
    }

    @Test
    fun `tiles stay on screen and never overlap`() {
        val width = 1920
        val height = 1080
        val layouts = (1..4).map { gridRects(it, width, height, 3) } +
            (1..4).flatMap { slots -> (0 until slots).map { focusRects(slots, it, width, height, 3) } }
        layouts.forEach { rects ->
            rects.forEach { r ->
                assertTrue("$r", r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height)
                assertTrue("$r", r.width > 0 && r.height > 0)
            }
            rects.forEachIndexed { i, a ->
                rects.drop(i + 1).forEach { b -> assertTrue("$a $b", !a.overlaps(b)) }
            }
        }
        // The large one runs the full height, in its own place in the line.
        val rects = focusRects(4, 1, width, height, 3)
        assertEquals(height, rects[1].height)
        assertTrue(rects[0].right < rects[1].left && rects[1].right < rects[2].left)
        assertTrue(rects[2].bottom < rects[3].top)
    }

    @Test
    fun `moving a tile moves its picture instead of handing it to another channel`() {
        val built = mutableMapOf<Int, View>()
        var order by androidx.compose.runtime.mutableStateOf(listOf(0, 1, 2))
        val shown = mutableMapOf<Int, View>()
        compose.setContent {
            MultiViewGrid(slots = 3, order = order, modifier = Modifier.fillMaxSize()) { tile ->
                AndroidView(
                    factory = { context -> View(context).also { built[tile] = it } },
                    update = { shown[tile] = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        val before = built.toMap()
        fun onScreen(view: View) = IntArray(2).also { view.getLocationInWindow(it) }.let { it[0] to it[1] }
        val firstPlace = onScreen(before.getValue(0))
        order = listOf(1, 0, 2)
        compose.waitForIdle()
        // The same views, each still showing its own tile.
        assertEquals(before, shown.toMap())
        assertEquals(3, built.size)
        // Tile 0 is now where tile 1 was: to the right of the first place.
        val moved = onScreen(before.getValue(0))
        assertTrue("$firstPlace -> $moved", moved.first > firstPlace.first)
    }
}
