package tv.reely.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.reely.plex.PlexHomeUser
import tv.reely.ui.components.LoadingRing
import tv.reely.ui.components.pillColors
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.components.digitOf
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceHigh

private const val PIN_LENGTH = 4

/**
 * "Who's watching?" — the people in a Plex Home, to switch between, as Plex's own apps
 * offer it. A profile with a PIN asks for it on a keypad the remote can reach; the
 * number keys work too on a remote that has them.
 */
@Composable
fun ProfilePicker(
    users: List<PlexHomeUser>,
    current: PlexHomeUser?,
    switchingTo: PlexHomeUser?,
    error: String?,
    onPick: (PlexHomeUser, String?) -> Unit,
    onDismissError: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Whose PIN is being asked for, if anybody's.
    var asking by remember { mutableStateOf<PlexHomeUser?>(null) }

    BackHandler {
        if (asking != null) {
            asking = null
            onDismissError()
        } else {
            onClose()
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        val target = asking
        when {
            switchingTo != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Avatar(switchingTo, size = 112.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingRing(diameter = 20.dp)
                    Text(text = "Switching to ${switchingTo.title}…", color = Muted, style = ReelyType.Body)
                }
            }

            target != null -> PinPad(
                user = target,
                error = error,
                onEntered = { pin -> onPick(target, pin) },
                onTyping = onDismissError,
                onCancel = {
                    asking = null
                    onDismissError()
                },
            )

            else -> Chooser(
                users = users,
                current = current,
                error = error,
                onPick = { user ->
                    onDismissError()
                    when {
                        user.uuid == current?.uuid -> onClose()
                        user.protected -> asking = user
                        else -> onPick(user, null)
                    }
                },
            )
        }
    }
}

@Composable
private fun Chooser(
    users: List<PlexHomeUser>,
    current: PlexHomeUser?,
    error: String?,
    onPick: (PlexHomeUser) -> Unit,
) {
    val first = remember { FocusRequester() }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { first.settle { holding } }
    Column(
        modifier = Modifier.onFocusChanged { holding = it.hasFocus },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Text(
            text = "Who's watching?",
            color = Chalk,
            style = ReelyType.Display.copy(fontSize = 34.sp, lineHeight = 40.sp),
        )
        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // The one in use first under the cursor, so OK straight away changes nothing.
            val startOn = users.indexOfFirst { it.uuid == current?.uuid }.coerceAtLeast(0)
            users.forEachIndexed { index, user ->
                ProfileTile(
                    user = user,
                    current = user.uuid == current?.uuid,
                    onClick = { onPick(user) },
                    modifier = if (index == startOn) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        Text(
            text = error ?: "Each profile has its own libraries, watch history and Continue Watching.",
            color = if (error != null) Accent else Faint,
            style = ReelyType.Label,
        )
    }
}

@Composable
private fun ProfileTile(
    user: PlexHomeUser,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1.08f else 1f, label = "profile-lift")
    Column(
        modifier = modifier
            .width(132.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) {
            Avatar(
                user = user,
                size = 112.dp,
                modifier = Modifier.border(
                    width = if (focused) 3.dp else 0.dp,
                    color = if (focused) Chalk else Color.Transparent,
                    shape = CircleShape,
                ),
            )
            if (user.protected) {
                LockBadge(modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
        Text(
            text = user.title,
            color = if (focused) Chalk else Muted,
            style = ReelyType.Meta,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                current -> "Watching now"
                user.admin -> "Owner"
                user.restricted -> "Managed"
                else -> " "
            },
            color = if (current) Accent else Faint,
            style = ReelyType.Label,
            maxLines = 1,
        )
    }
}

/**
 * Asks for focus until it has arrived. A screen that takes over the whole window has to
 * hold focus from its first frame, or the remote does nothing; a request made before the
 * window is in remote mode is dropped without a word, so one request is not enough.
 */
private suspend fun FocusRequester.settle(arrived: () -> Boolean) {
    repeat(40) {
        if (arrived()) return
        requestWhenReady()
        delay(50)
    }
}

/** A profile's picture, or its initial on a plain disc when it has none. */
@Composable
internal fun Avatar(user: PlexHomeUser, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = user.title.take(1).uppercase(),
            color = Muted,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (user.thumb != null) {
            AsyncImage(
                model = user.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A small padlock on a disc: this profile has a PIN. */
@Composable
internal fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Ink)
            .padding(3.dp)
            .clip(CircleShape)
            .background(SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        // Body and shackle, drawn from boxes so it needs no icon font.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = 6.dp)
                    .border(2.dp, Chalk, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
            )
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Chalk),
            )
        }
    }
}

@Composable
private fun PinPad(
    user: PlexHomeUser,
    error: String?,
    onEntered: (String) -> Unit,
    onTyping: () -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember(user.uuid) { mutableStateOf("") }
    // A wrong PIN clears, ready for another go, rather than leaving four dots to delete.
    LaunchedEffect(error) { if (error != null) pin = "" }

    fun type(digit: Char) {
        if (pin.length >= PIN_LENGTH) return
        if (pin.isEmpty()) onTyping()
        pin += digit
        if (pin.length == PIN_LENGTH) onEntered(pin)
    }
    fun delete() {
        pin = pin.dropLast(1)
    }

    val five = remember { FocusRequester() }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { five.settle { holding } }

    Row(
        modifier = Modifier
            .onFocusChanged { holding = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = digitOf(event.key)
                when {
                    digit != null -> {
                        type(digit)
                        true
                    }
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        delete()
                        true
                    }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.width(300.dp),
        ) {
            Avatar(user, size = 96.dp)
            Text(text = user.title, color = Chalk, style = ReelyType.Headline)
            Text(text = "Enter the PIN for this profile", color = Muted, style = ReelyType.Meta)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(PIN_LENGTH) { index ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (index < pin.length) Chalk else Chalk.copy(alpha = 0.18f)),
                    )
                }
            }
            Text(
                text = error ?: " ",
                color = Accent,
                style = ReelyType.Label,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { digit ->
                        PadKey(
                            label = digit.toString(),
                            onClick = { type(digit) },
                            modifier = if (digit == '5') Modifier.focusRequester(five) else Modifier,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PadKey(label = "Delete", onClick = ::delete, small = true)
                PadKey(label = "0", onClick = { type('0') })
                PadKey(label = "Cancel", onClick = onCancel, small = true)
            }
            Box(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PadKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = pillColors(focused)
    Box(
        modifier = modifier
            .size(width = 76.dp, height = 56.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) colors.fill else SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.text,
            style = if (small) ReelyType.Label else ReelyType.Headline,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** For a screen that only wants to say who is watching, with nowhere to go. */
@Composable
internal fun ProfileBadge(user: PlexHomeUser, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(user, size = 26.dp)
        Text(text = user.title, color = Faint, style = ReelyType.Label, maxLines = 1)
    }
}
