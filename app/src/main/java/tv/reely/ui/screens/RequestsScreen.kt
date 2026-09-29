package tv.reely.ui.screens

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.requests.RequestTitle
import tv.reely.ui.RequestsState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.PosterCard
import tv.reely.ui.components.ROWS_BOTTOM
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvTextField
import tv.reely.ui.components.glass
import tv.reely.ui.components.rememberRowFocus
import tv.reely.ui.components.rowItem
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/**
 * Asking for what the server doesn't have. Reely — the owner's requesting app — has the
 * catalogue and takes the requests; this is the television's way in to it: search, rows
 * of what's trending and popular, and what this account has already asked for.
 */
@Composable
fun RequestsScreen(
    requests: RequestsState,
    onConnect: (String) -> Unit,
    onDismissError: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpen: (RequestTitle) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (requests.server == null) {
        ConnectReely(requests, onConnect, onDismissError, modifier.fillMaxSize())
        return
    }

    val sideways = LocalBringIntoViewSpec.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = ROWS_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvTextField(
                    value = requests.query,
                    onValueChange = onQueryChange,
                    label = "Request",
                    placeholder = "Find a film or a show to ask for",
                    imeAction = ImeAction.Search,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
                when {
                    requests.error != null -> ErrorNote(requests.error, onDismiss = onDismissError)
                    requests.searching -> EmptyNote("Searching…")
                    requests.query.isNotBlank() && requests.results.isEmpty() ->
                        EmptyNote("Nothing matched \"${requests.query}\".")
                    requests.loading -> EmptyNote("Loading…")
                }
            }
        }

        if (requests.query.isNotBlank()) {
            if (requests.results.isNotEmpty()) {
                item { TitleRow("Results", requests.results, requests, sideways, onOpen) }
            }
            return@LazyColumn
        }

        if (requests.mine.isNotEmpty()) {
            item { TitleRow("Your requests", requests.mine.map { it.title }, requests, sideways, onOpen) }
        }
        items(requests.rows, key = { it.id }) { row ->
            TitleRow(row.title, row.titles, requests, sideways, onOpen)
        }
    }
}

@Composable
private fun TitleRow(
    heading: String,
    titles: List<RequestTitle>,
    requests: RequestsState,
    sideways: androidx.compose.foundation.gestures.BringIntoViewSpec,
    onOpen: (RequestTitle) -> Unit,
) {
    val focus = rememberRowFocus()
    PosterRow(title = heading, rowFocus = focus, sideways = sideways) {
        items(titles, key = { it.key }) { title ->
            PosterCard(
                title = title.title,
                subtitle = requestLabel(requests.statusOf(title)) ?: title.year?.toString(),
                imageUrl = title.poster,
                onFocus = { focus.onFocused(title.key) },
                onClick = { onOpen(title) },
                modifier = rowItem(focus, title.key),
            )
        }
    }
}

/** What a request's status reads as under a poster. */
internal fun requestLabel(status: String?): String? = when (status) {
    "pending" -> "Requested"
    "approved" -> "Approved"
    "denied" -> "Declined"
    else -> null
}

/** Before Reely's address is known: where it is, and a word on what it's for. */
@Composable
private fun ConnectReely(
    requests: RequestsState,
    onConnect: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var address by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeading("Requests")
            Text(
                text = "Ask for films and shows",
                color = Chalk,
                style = ReelyType.Display.copy(fontSize = 30.sp, lineHeight = 36.sp),
            )
            Text(
                text = "Requests go to Reely, the server owner's app for adding films and shows. " +
                    "Enter its address; you're signed in with your Plex account.",
                color = Muted,
                style = ReelyType.Body,
            )
            if (requests.error != null) ErrorNote(requests.error, onDismiss = onDismissError)
        }
        Column(
            modifier = Modifier.width(440.dp).glass().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvTextField(
                value = address,
                onValueChange = { address = it },
                label = "Reely address",
                placeholder = "reely.example.com",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            )
            TvActionButton(
                label = if (requests.connecting) "Connecting…" else "Connect",
                onClick = { onConnect(address) },
                emphasised = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
