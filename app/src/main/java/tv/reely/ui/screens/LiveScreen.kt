package tv.reely.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import tv.reely.ui.components.FocusReturn
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
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvChip
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
    onSignInPlaylist: (url: String, guideUrl: String) -> Unit,
    onSelectCategory: (XtreamCategory) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    // The category the guide was opened from, on the way back out of it: the cursor goes
    // back onto that tile. Left to itself it had nowhere to be once the guide went, and
    // fell to the first thing in the window, the search tab.
    returnTo: String? = null,
    onReturned: () -> Unit = {},
) {
    if (!live.isConnected) {
        XtreamSignInPanel(
            live = live,
            onSignIn = onSignIn,
            onSignInPlaylist = onSignInPlaylist,
            onDismissError = onDismissError,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val tiles = remember { CategoryTiles() }
    val grid = rememberLazyGridState()
    LaunchedEffect(returnTo, live.shownCategories.isEmpty()) {
        if (returnTo == null || live.shownCategories.isEmpty()) return@LaunchedEffect
        val index = live.shownCategories.indexOfFirst { it.id == returnTo }
        if (index >= 0) {
            // A frame to lay the grid out, then the tile brought on screen if it's not.
            withFrameNanos { }
            val item = index + 1 // after the heading
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == item }) grid.scrollToItem(item)
            FocusReturn.to(tiles.of(returnTo))
        }
        onReturned()
    }

    // The tiles step back while one of them has focus, as a row of posters does.
    FocusRow { gridFocused ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            state = grid,
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
                        text = "${live.categories.size} categories" +
                            (if (live.favorites.isNotEmpty()) "  ·  ${live.favorites.size} favorites" else ""),
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
                        EmptyNote("Your provider didn't list any channels.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }

            // Category tiles to be, while the panel answers.
            if (live.categories.isEmpty() && live.busy) {
                items(12) {
                    Shimmer { Box(modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT).placeholder(WideCorner)) }
                }
            }

            items(live.shownCategories, key = { it.id }) { category ->
                CategoryCard(
                    category = category,
                    onClick = { onSelectCategory(category) },
                    modifier = Modifier.focusRequester(tiles.of(category.id)),
                )
            }
        }
    }
}

private val TILE_HEIGHT = 92.dp

/** A requester per category tile, for putting the cursor back on one. */
private class CategoryTiles {
    private val requesters = mutableMapOf<String, FocusRequester>()
    fun of(id: String): FocusRequester = requesters.getOrPut(id) { FocusRequester() }
}

/**
 * A category, in its own colour. Focus is the same as a poster's: a white ring, a lift,
 * and a glow — here in the tile's colour rather than the artwork's, since it has none.
 */
@Composable
private fun CategoryCard(category: XtreamCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val tint = channelTint(category.id)
    CompositionLocalProvider(LocalTint provides tint) {
        Box(
            modifier = modifier
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
                            // Richer than they were: from the sofa the categories read as one grey.
                            tint.copy(alpha = if (focused) 0.62f else 0.46f),
                            tint.copy(alpha = if (focused) 0.26f else 0.16f),
                        )
                    )
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = category.name,
                color = Chalk,
                style = ReelyType.Body.copy(fontSize = 22.sp, lineHeight = 27.sp),
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
    onSignInPlaylist: (String, String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var playlist by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var playlistUrl by rememberSaveable { mutableStateOf("") }
    var guideUrl by rememberSaveable { mutableStateOf("") }

    /*
     * Words on the left, the form on the right. Stacked, the three fields and the button
     * ran past the bottom of the screen — Connect sat at the very edge, and an error
     * message pushed it and the password field off it altogether.
     */
    Row(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeading("Live TV")
            Text(text = "Sign in to your live TV provider", color = Chalk, style = ReelyType.Display.copy(fontSize = 30.sp, lineHeight = 36.sp))
            Text(
                text = if (playlist) {
                    "Enter the playlist address from your provider. Some providers give a TV guide " +
                        "address as well. Both are stored only on this device."
                } else {
                    "Enter the details from your provider. They're stored only on this device."
                },
                color = Muted,
                style = ReelyType.Body,
            )
            if (live.error != null) {
                ErrorNote(message = live.error, onDismiss = onDismissError)
            }
        }

        Column(
            modifier = Modifier.width(440.dp).glass().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Providers hand out one or the other: a login for their panel, or a playlist.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvChip(label = "Xtream Codes login", selected = !playlist, onClick = { playlist = false })
                TvChip(label = "M3U playlist", selected = playlist, onClick = { playlist = true })
            }
            if (playlist) {
                TvTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    label = "Playlist address",
                    placeholder = "http://provider.example.com/playlist.m3u",
                    keyboardType = KeyboardType.Uri,
                )
                TvTextField(
                    value = guideUrl,
                    onValueChange = { guideUrl = it },
                    label = "TV guide address (optional)",
                    placeholder = "http://provider.example.com/guide.xml",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                )
            } else {
                TvTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "Server",
                    placeholder = "provider.example.com:8080",
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
            }
            TvActionButton(
                label = if (live.busy) "Connecting…" else "Connect",
                onClick = {
                    if (playlist) onSignInPlaylist(playlistUrl, guideUrl) else onSignIn(host, username, password)
                },
                emphasised = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
