package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.ui.LiveState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HintBar
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvListRow
import tv.reely.ui.components.TvTextField
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.xtream.XtreamCategory

@Composable
fun LiveScreen(
    live: LiveState,
    onSignIn: (host: String, username: String, password: String) -> Unit,
    onSelectCategory: (XtreamCategory) -> Unit,
    onPlayChannel: (Int) -> Unit,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Live TV",
                    color = Parchment,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append("${live.categories.size} categories")
                        live.account?.let { append(" · ${it.maxConnections} connection(s) allowed") }
                        append(" · streaming as ${live.format.label}")
                    },
                    color = Faint,
                    fontSize = 12.sp,
                )
            }
        }

        if (live.error != null) {
            ErrorNote(message = live.error, onDismiss = onDismissError)
        }

        // Category first, channels second — the whole reason this app exists.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                SectionHeading("Categories", modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(live.categories, key = { it.id }) { category ->
                        TvListRow(
                            title = category.name,
                            subtitle = null,
                            imageUrl = null,
                            selected = live.selectedCategory?.id == category.id,
                            onClick = { onSelectCategory(category) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .background(Line.copy(alpha = 0.35f))
                    .width(1.dp)
                    .fillMaxHeight(),
            ) {}

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                SectionHeading(
                    text = live.selectedCategory?.name ?: "Channels",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                when {
                    live.busy && live.channels.isEmpty() -> EmptyNote("Loading channels…")
                    live.channels.isEmpty() -> EmptyNote("No channels in this category.")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(
                            items = live.channels,
                            key = { _, channel -> channel.streamId },
                        ) { index, channel ->
                            TvListRow(
                                title = channel.name,
                                subtitle = channel.number.takeIf { it > 0 }?.let { "#$it" },
                                imageUrl = channel.icon,
                                selected = false,
                                onClick = { onPlayChannel(index) },
                            )
                        }
                    }
                }
            }
        }

        HintBar("OK to play · in the player, up/down changes channel and right switches TS ↔ HLS")
    }
}

@Composable
private fun XtreamSignInPanel(
    live: LiveState,
    onSignIn: (String, String, String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeading("Live TV")
        Text(
            text = "Sign in to your IPTV provider",
            color = Parchment,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This is your own subscription, and it stays on this device — nothing is sent " +
                "anywhere else. The library half works without it.",
            color = Muted,
            fontSize = 15.sp,
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
            modifier = Modifier.widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvTextField(
                value = host,
                onValueChange = { host = it },
                label = "Server",
                placeholder = "panel.example.com:8080",
                keyboardType = KeyboardType.Uri,
            )
            TvTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
            )
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

        HintBar("If it fails, the message says which part was wrong — the host, the credentials, or nothing answering.")
    }
}
