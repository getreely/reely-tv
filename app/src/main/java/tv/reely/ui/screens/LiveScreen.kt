package tv.reely.ui.screens

import tv.reely.ui.theme.SurfaceRaised
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.components.cardRing
import tv.reely.ui.components.cardLift
import tv.reely.ui.components.WideCorner
import tv.reely.ui.components.LocalTint
import tv.reely.ui.components.FocusRow
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.CompositionLocalProvider
import tv.reely.ui.components.placeholder
import tv.reely.ui.components.Shimmer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.ui.LiveState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HintBar
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvTextField
import tv.reely.ui.components.channelTint
import tv.reely.ui.components.glass
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk
import tv.reely.xtream.XtreamCategory

/**
 * Picking a category comes first; the grid guide is what a category opens into, and Back
 * from the grid comes back here.
 */
@Composable
fun LiveCategoriesScreen(
    live: LiveState,
    onSignIn: (host: String, username: String, password: String) -> Unit,
    onSelectCategory: (XtreamCategory) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!live.isConnected) {
        XtreamSignInPanel(
            live = live,
            onSignIn = onSignIn,
            onDismissError = onDismissError,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    // The tiles step back while one of them has focus, as a row of posters does.
    FocusRow { gridFocused ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            modifier = modifier.fillMaxSize().then(gridFocused),
            // Inside the safe area, with room at the edges and between tiles for a focused
            // one's lift and glow.
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 27.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(text = "Live TV", color = Chalk, style = ReelyType.Display)
                    Text(
                        text = buildString {
                            append("${live.categories.size} categories")
                            live.account?.let { append("  ·  ${it.maxConnections} connection(s) allowed") }
                            append("  ·  ${live.format.label}")
                        },
                        color = Muted,
                        style = ReelyType.Meta,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (live.error != null) {
                        ErrorNote(
                            message = live.error,
                            onDismiss = onDismissError,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (live.categories.isEmpty() && !live.busy) {
                        EmptyNote("The panel returned no categories.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }

            // Category tiles to be, while the panel answers.
            if (live.categories.isEmpty() && live.busy) {
                items(12) {
                    Shimmer { Box(modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT).placeholder(WideCorner)) }
                }
            }

            items(live.categories, key = { it.id }) { category ->
                CategoryCard(category = category, onClick = { onSelectCategory(category) })
            }
        }
    }
}

private val TILE_HEIGHT = 92.dp

/**
 * A category, in its own colour. Focus is the same as a poster's: a white ring, a lift,
 * and a glow — here in the tile's colour rather than the artwork's, since it has none.
 */
@Composable
private fun CategoryCard(category: XtreamCategory, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tint = channelTint(category.id)
    CompositionLocalProvider(LocalTint provides tint) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_HEIGHT)
                .cardLift(focused)
                .onFocusChanged { focused = it.isFocused }
                .cardRing(focused, WideCorner)
                .clip(RoundedCornerShape(WideCorner))
                .background(SurfaceRaised)
                .background(
                    Brush.linearGradient(
                        listOf(
                            tint.copy(alpha = if (focused) 0.5f else 0.34f),
                            tint.copy(alpha = if (focused) 0.2f else 0.1f),
                        )
                    )
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = category.name,
                color = if (focused) Chalk else Chalk.copy(alpha = 0.86f),
                style = ReelyType.Body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun XtreamSignInPanel(
    live: LiveState,
    onSignIn: (String, String, String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeading("Live TV")
        Text(
            text = "Sign in to your IPTV provider",
            color = Chalk,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This is your own subscription, and it stays on this device — nothing is sent " +
                "anywhere else. The library half works without it.",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.widthIn(max = 620.dp),
        )

        if (live.error != null) {
            ErrorNote(
                message = live.error,
                onDismiss = onDismissError,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }

        Column(
            modifier = Modifier.widthIn(max = 560.dp).glass(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TvTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "Server",
                    placeholder = "panel.example.com:8080",
                    keyboardType = KeyboardType.Uri,
                )
                TvTextField(value = username, onValueChange = { username = it }, label = "Username")
                TvTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    password = true,
                    imeAction = ImeAction.Done,
                )
                TvActionButton(
                    label = if (live.busy) "Connecting…" else "Connect",
                    onClick = { onSignIn(host, username, password) },
                    emphasised = true,
                )
            }
        }

        HintBar("If it fails, the message says which part was wrong — the host, the credentials, or nothing answering.")
    }
}
