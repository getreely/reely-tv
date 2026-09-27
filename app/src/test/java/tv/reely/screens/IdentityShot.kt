package tv.reely.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.R

/** The launcher banner and icon, as the TV's home screen shows them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class IdentityShot {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = Shots.onlyWhenAsked()

    @Test fun launcher() {
        compose.setContent {
            Row(
                modifier = Modifier.fillMaxSize().background(Color(0xFF2B2F36)).padding(60.dp),
                horizontalArrangement = Arrangement.spacedBy(60.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_banner), null, Modifier.size(width = 480.dp, height = 270.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(216.dp).background(Color(0xFF110D0E)))
            }
        }
        Shots.save(compose, "launcher")
    }
}
