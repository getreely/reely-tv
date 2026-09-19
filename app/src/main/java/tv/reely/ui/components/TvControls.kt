package tv.reely.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.ui.theme.SurfaceRaised

/**
 * Every focusable thing in this app is built from one of these, so focus order and the
 * focus ring behave the same everywhere. On a television that is the whole game.
 */

@Composable
fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused && emphasised -> Accent
        focused -> SurfaceHigh
        emphasised -> Accent.copy(alpha = 0.22f)
        else -> SurfaceRaised
    }
    val foreground = if (focused && emphasised) Color.Black else Parchment
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                width = 2.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) Accent.copy(alpha = 0.20f) else SurfaceRaised)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Accent
                    selected -> Accent.copy(alpha = 0.55f)
                    else -> Line
                },
                shape = RoundedCornerShape(99.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected || focused) Parchment else Muted,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** A poster tile. Focus scales it rather than moving it, which reads better at a distance. */
@Composable
fun TvPosterTile(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster-scale")

    Column(
        modifier = modifier
            .width(150.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(
                    width = 2.dp,
                    color = if (focused) Accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = title.take(1).uppercase(),
                    color = Faint,
                    fontSize = 34.sp,
                    lineHeight = 42.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Text(
            text = title,
            color = if (focused) Parchment else Muted,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(text = subtitle, color = Faint, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** A list row, used for categories and channels where a poster grid would waste the screen. */
@Composable
fun TvListRow(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Accent.copy(alpha = 0.20f)
                    selected -> SurfaceHigh
                    else -> Color.Transparent
                }
            )
            .border(
                width = 2.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(48.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (focused || selected) Parchment else Muted,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(text = subtitle, color = Faint, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(text = label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Parchment, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else
                androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceRaised)
                        .border(
                            width = 2.dp,
                            color = if (focused) Accent else Line,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(text = placeholder, color = Faint, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Faint,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

@Composable
fun ErrorNote(message: String, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.14f))
            .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = message,
            color = Parchment,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            TvActionButton(label = "Dismiss", onClick = onDismiss)
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = Faint, fontSize = 14.sp, modifier = modifier)
}

/** A "Label: value" line, used on the status screen. */
@Composable
fun FactLine(label: String, value: String, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Faint)) { append("$label  ") }
            withStyle(SpanStyle(color = Parchment)) { append(value) }
        },
        fontSize = 14.sp,
        modifier = modifier,
    )
}

@Composable
fun HintBar(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Faint,
        fontSize = 12.sp,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}
