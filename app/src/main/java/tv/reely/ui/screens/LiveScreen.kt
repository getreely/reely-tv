package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.ui.LiveState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.HintBar
import tv.reely.ui.components.ProgressStrip
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvListRow
import tv.reely.ui.components.TvTextField
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceRaised
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveScreen(
    live: LiveState,
    onSignIn: (host: String, username: String, password: String) -> Unit,
    onSelectCategory: (XtreamCategory) -> Unit,
    onFocusChannel: (XtreamChannel) -> Unit,
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

    // One ticking clock for the whole guide, so the "on now" bars advance together.
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSeconds = System.currentTimeMillis() / 1000
            delay(20_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
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
                    live.account?.let { append("  ·  ${it.maxConnections} connection(s) allowed") }
                    append("  ·  ${live.format.label}")
                },
                color = Faint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }

        if (live.error != null) {
            ErrorNote(message = live.error, onDismiss = onDismissError)
        }

        // Category, then channel, then what is actually on. The reason this app exists.
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(modifier = Modifier.width(260.dp).fillMaxHeight()) {
                SectionHeading("Categories", modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(
                    modifier = Modifier.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
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

            Divider()

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                SectionHeading(
                    text = live.selectedCategory?.name ?: "Channels",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                when {
                    live.busy && live.channels.isEmpty() -> EmptyNote("Loading channels…")
                    live.channels.isEmpty() -> EmptyNote("No channels in this category.")
                    else -> LazyColumn(
                        modifier = Modifier.focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(
                            items = live.channels,
                            key = { _, channel -> channel.streamId },
                        ) { index, channel ->
                            val onNow = live.nowNext(channel.streamId)
                                .firstOrNull { it.progressAt(nowSeconds) != null }
                            TvListRow(
                                title = channel.name,
                                subtitle = onNow?.title
                                    ?: channel.number.takeIf { it > 0 }?.let { "#$it" },
                                imageUrl = channel.icon,
                                selected = live.focusedChannel?.streamId == channel.streamId,
                                onClick = { onPlayChannel(index) },
                                onFocus = { onFocusChannel(channel) },
                            )
                        }
                    }
                }
            }

            Divider()

            GuidePanel(
                live = live,
                nowSeconds = nowSeconds,
                modifier = Modifier.width(340.dp).fillMaxHeight(),
            )
        }

        HintBar("OK plays the channel · in the player, up/down changes channel and right switches TS ↔ HLS")
    }
}

/** Now and next for whichever channel is under the cursor. */
@Composable
private fun GuidePanel(
    live: LiveState,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val channel = live.focusedChannel
    Column(modifier = modifier) {
        SectionHeading("Guide", modifier = Modifier.padding(bottom = 8.dp))
        if (channel == null) {
            EmptyNote("Move to a channel to see what's on.")
            return@Column
        }

        val programmes = live.nowNext(channel.streamId)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceRaised)
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = channel.name,
                color = Parchment,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (programmes.isEmpty()) {
                Text(
                    text = "No guide data for this channel.",
                    color = Faint,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            programmes.take(4).forEachIndexed { index, programme ->
                ProgrammeBlock(programme, nowSeconds, isFirst = index == 0)
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: XtreamProgramme,
    nowSeconds: Long,
    isFirst: Boolean,
) {
    val progress = programme.progressAt(nowSeconds)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = if (progress != null) "ON NOW" else timeRange(programme),
            color = if (progress != null) tv.reely.ui.theme.Accent else Faint,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = programme.title,
            color = Parchment,
            fontSize = if (isFirst) 15.sp else 14.sp,
            lineHeight = if (isFirst) 20.sp else 19.sp,
            fontWeight = if (isFirst) FontWeight.Medium else FontWeight.Normal,
        )
        if (progress != null) {
            Text(text = timeRange(programme), color = Faint, fontSize = 11.sp, lineHeight = 14.sp)
            ProgressStrip(progress, modifier = Modifier.padding(top = 2.dp))
        }
        if (isFirst && programme.description != null) {
            Text(
                text = programme.description,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun timeRange(programme: XtreamProgramme): String {
    val start = clock.format(Date(programme.startEpochSeconds * 1000))
    val end = clock.format(Date(programme.endEpochSeconds * 1000))
    return "$start – $end"
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Line.copy(alpha = 0.4f)),
    )
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

        HintBar("If it fails, the message says which part was wrong — the host, the credentials, or nothing answering.")
    }
}
