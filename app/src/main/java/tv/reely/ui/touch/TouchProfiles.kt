package tv.reely.ui.touch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.reely.plex.PlexHomeUser
import tv.reely.ui.screens.Avatar
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceHigh

private const val PIN_LENGTH = 4

/**
 * "Who's watching?" on a phone: the same rules as the television's — your own profile
 * closes it, one with a PIN asks for it, a wrong PIN clears for another go — laid out to
 * fit a phone's width, every profile within reach however many there are.
 */
@Composable
internal fun TouchProfilePicker(
    users: List<PlexHomeUser>,
    current: PlexHomeUser?,
    switchingTo: PlexHomeUser?,
    error: String?,
    onPick: (PlexHomeUser, String?) -> Unit,
    onDismissError: () -> Unit,
    onClose: () -> Unit,
) {
    var asking by remember { mutableStateOf<PlexHomeUser?>(null) }
    BackHandler {
        if (asking != null) {
            asking = null
            onDismissError()
        } else {
            onClose()
        }
    }
    Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding(), contentAlignment = Alignment.Center) {
        val target = asking
        when {
            switchingTo != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Avatar(switchingTo, size = 96.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Text("Switching to ${switchingTo.title}…", color = Muted, style = MaterialTheme.typography.bodyLarge)
                }
            }

            target != null -> PinEntry(
                user = target,
                error = error,
                onEntered = { pin -> onPick(target, pin) },
                onTyping = onDismissError,
                onCancel = {
                    asking = null
                    onDismissError()
                },
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(horizontal = TouchMargin, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Who's watching?",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Chalk,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                items(users, key = { it.uuid }) { user ->
                    val isCurrent = user.uuid == current?.uuid
                    Profile(user, isCurrent) {
                        onDismissError()
                        when {
                            isCurrent -> onClose()
                            user.protected -> asking = user
                            else -> onPick(user, null)
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        error ?: "Each profile has its own libraries, watch history and Continue Watching.",
                        color = if (error != null) Accent else Faint,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Profile(user: PlexHomeUser, current: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Avatar(user, size = 84.dp)
            if (user.protected) tv.reely.ui.screens.LockBadge(Modifier.align(Alignment.BottomEnd))
        }
        Text(user.title, color = Chalk, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                current -> "Watching now"
                user.admin -> "Owner"
                user.restricted -> "Managed"
                else -> " "
            },
            color = if (current) Accent else Faint,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun PinEntry(
    user: PlexHomeUser,
    error: String?,
    onEntered: (String) -> Unit,
    onTyping: () -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember(user.uuid) { mutableStateOf("") }
    // A wrong PIN clears, ready for another go.
    LaunchedEffect(error) { if (error != null) pin = "" }
    fun type(digit: Char) {
        if (pin.length >= PIN_LENGTH) return
        if (pin.isEmpty()) onTyping()
        pin += digit
        if (pin.length == PIN_LENGTH) onEntered(pin)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TouchMargin, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Avatar(user, size = 80.dp)
        Text(user.title, style = MaterialTheme.typography.headlineSmall, color = Chalk)
        Text("Enter the PIN for this profile", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(PIN_LENGTH) { index ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (index < pin.length) Chalk else Chalk.copy(alpha = 0.18f)))
            }
        }
        Text(error ?: " ", color = Accent, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { digit -> Key(digit.toString()) { type(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("Delete", small = true) { pin = pin.dropLast(1) }
                Key("0") { type('0') }
                Key("Cancel", small = true, onClick = onCancel)
            }
        }
        Box(Modifier.height(8.dp))
    }
}

@Composable
private fun Key(label: String, small: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(84.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Chalk,
            style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
