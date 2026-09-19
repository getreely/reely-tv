package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceRaised

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
    Column(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionHeading("Plex library")
        Text(
            text = "Sign in to see a library",
            color = Parchment,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Signing in with your Plex account surfaces the servers shared with you. " +
                "Live TV works without this — you can leave it for later.",
            color = Muted,
            fontSize = 15.sp,
            modifier = Modifier.widthIn(max = 620.dp),
        )

        if (plex.error != null) {
            ErrorNote(
                message = plex.error,
                onDismiss = onDismissError,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }

        val code = plex.linkCode
        if (code == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    label = if (plex.busy) "Working…" else "Sign in to Plex",
                    onClick = onStartLink,
                    emphasised = true,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceRaised)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "On a phone or computer, go to", color = Muted, fontSize = 14.sp)
                Text(text = "plex.tv/link", color = Parchment, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "and enter this code", color = Muted, fontSize = 14.sp)
                Text(
                    text = code,
                    color = Accent,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                )
                Text(
                    text = "This screen updates by itself once the code is accepted.",
                    color = Faint,
                    fontSize = 12.sp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(label = "Cancel", onClick = onCancelLink)
                }
            }
        }
    }
}
