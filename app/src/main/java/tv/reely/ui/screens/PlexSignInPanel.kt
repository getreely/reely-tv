package tv.reely.ui.screens

import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.components.LoadingRing
import tv.reely.ui.components.glass
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.ui.PlexState
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk

/**
 * The Plex half of onboarding. It is deliberately framed as reaching somebody else's
 * library: signing in surfaces the servers shared with this account.
 */
@Composable
fun PlexSignInPanel(
    plex: PlexState,
    onStartLink: () -> Unit,
    onCancelLink: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * The same shape as the live TV sign-in: what this is on the left, the one thing to
     * do on the right. Stacked, an error message pushed the code off the bottom.
     */
    Row(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeading("Plex")
            Text(
                text = "Sign in to watch your library",
                color = Chalk,
                style = ReelyType.Display.copy(fontSize = 30.sp, lineHeight = 36.sp),
            )
            Text(
                text = "Your movies and shows from Plex, including libraries shared with you.",
                color = Muted,
                style = ReelyType.Body,
            )
            if (plex.error != null) {
                ErrorNote(message = plex.error, onDismiss = onDismissError)
            }
        }

        Column(
            modifier = Modifier.width(440.dp).glass().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val code = plex.linkCode
            if (code == null) {
                Text(
                    text = "You'll get a short code to enter on your phone or computer.",
                    color = Muted,
                    style = ReelyType.Meta,
                )
                TvActionButton(
                    label = if (plex.busy) "Getting a code…" else "Sign in with Plex",
                    onClick = onStartLink,
                    emphasised = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Step(number = 1) {
                    Text(text = "On your phone or computer, go to", color = Muted, style = ReelyType.Meta)
                    Text(text = "plex.tv/link", color = Chalk, style = ReelyType.Headline)
                }
                Step(number = 2) {
                    Text(text = "Enter this code", color = Muted, style = ReelyType.Meta)
                    // One tile a character: easier to read across a room than a word.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        code.forEach { char ->
                            Box(
                                modifier = Modifier
                                    .size(width = 52.dp, height = 64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = char.toString(),
                                    color = Chalk,
                                    fontSize = 34.sp,
                                    lineHeight = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingRing(diameter = 20.dp)
                    Text(
                        text = "Waiting for you to sign in…",
                        color = Muted,
                        style = ReelyType.Label,
                        modifier = Modifier.weight(1f),
                    )
                    TvActionButton(label = "Cancel", onClick = onCancelLink)
                }
            }
        }
    }
}

/** A numbered instruction: the number in a small disc, what to do beside it. */
@Composable
private fun Step(number: Int, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), color = Ink, style = ReelyType.Label, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
