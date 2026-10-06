package tv.reely.ui.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tv.reely.requests.RequestTitle
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.RequestsState
import tv.reely.ui.Route
import tv.reely.ui.screens.requestLabel
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted

/** Requests: Reely's catalogue, search, and what this account has asked for. */
@Composable
internal fun TouchRequests(viewModel: ReelyViewModel, state: ReelyState, actions: TouchActions) {
    val requests = state.requests
    if (requests.server == null) {
        Column(Modifier.fillMaxSize()) {
            TouchHeader("Requests", actions)
            ConnectReely(viewModel, requests)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + barSpace()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TouchHeader("Requests", actions) }
        item {
            Column(Modifier.padding(horizontal = TouchMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Search movies and shows to request", requests.query, viewModel::setRequestQuery)
            }
        }
        requests.error?.let { item { TouchError(it, onDismiss = viewModel::dismissRequestsError) } }
        when {
            requests.searching -> item { Spinner() }
            requests.query.isNotBlank() && requests.results.isEmpty() -> item { TouchNote("Nothing matched \"${requests.query}\".") }
            requests.loading && requests.shownRows.isEmpty() -> item { Spinner() }
        }
        if (requests.query.isNotBlank()) {
            if (requests.results.isNotEmpty()) item { TitleRow("Results", requests.results, requests, viewModel) }
            return@LazyColumn
        }
        if (requests.mine.isNotEmpty()) item { TitleRow("Your requests", requests.mine.map { it.title }.distinctBy { it.key }, requests, viewModel) }
        items(requests.shownRows, key = { it.id }) { row -> TitleRow(row.title, row.titles, requests, viewModel) }
    }
}

@Composable
private fun Spinner() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = Accent)
}

@Composable
private fun TitleRow(heading: String, titles: List<RequestTitle>, requests: RequestsState, viewModel: ReelyViewModel) {
    TouchRow(heading) {
        items(titles, key = { it.key }) { title ->
            TouchPoster(
                title = title.title,
                subtitle = title.year?.toString(),
                imageUrl = title.poster,
                tag = requests.badgeFor(title),
                onClick = { viewModel.navigate(Route.RequestTitle(title)) },
            )
        }
    }
}

@Composable
private fun ConnectReely(viewModel: ReelyViewModel, requests: RequestsState) {
    var address by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = barSpace()).padding(horizontal = TouchMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ask for movies and shows", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        Text(
            "Requests go to Reely, the server owner's app for adding movies and shows. Enter its address; you're signed in with your Plex account.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        requests.error?.let { TouchError(it, onDismiss = viewModel::dismissRequestsError, modifier = Modifier.padding(0.dp)) }
        Field("Reely address", address, { address = it }, KeyboardType.Uri)
        TouchPrimaryButton(
            if (requests.connecting) "Connecting…" else "Connect",
            { viewModel.connectReely(address) },
            Modifier.fillMaxWidth(),
            enabled = !requests.connecting,
        )
    }
}

/** One title in Requests: what it is, and asking for it — or watching it, when it's here. */
@Composable
internal fun TouchRequestTitle(viewModel: ReelyViewModel, state: ReelyState, route: Route.RequestTitle) {
    val page = state.requestDetail?.takeIf { it.title.key == route.title.key }
    val status = state.requests.statusOf(route.title)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = barSpace())) {
        Box(Modifier.heroHeight()) {
            AsyncImage(page?.detail?.backdrop ?: route.title.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f), Color.Transparent, Ink))))
            Box(Modifier.padding(4.dp)) {
                HeaderButton(onClick = viewModel::goBack) { tv.reely.ui.components.ArrowGlyph(it, left = true, size = 22.dp) }
            }
        }
        Column(Modifier.padding(horizontal = TouchMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(route.title.title, style = MaterialTheme.typography.headlineMedium, color = Chalk)
            val detail = page?.detail
            Text(
                listOfNotNull(
                    route.title.year?.toString(),
                    if (route.title.isShow) "Show" else "Movie",
                    detail?.runtime?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                    detail?.status,
                    detail?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            (detail?.title?.overview ?: route.title.overview)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
            if (page == null || page.busy) {
                Spinner()
                return@Column
            }
            val held = detail?.inLibraries?.size ?: 0
            when {
                page.error != null -> TouchError(page.error, modifier = Modifier.padding(0.dp))
                detail == null -> Unit
                !page.canAsk -> Text(
                    if (held > 1) "Already in $held libraries: there's nowhere left for it to go." else "Already in your library.",
                    color = Chalk,
                )
                page.partNote != null -> Text(page.partNote!!, color = Chalk)
                held == 1 -> Text("Already in a library. It can go in another as well.", color = Chalk)
                held > 1 -> Text("Already in $held libraries. It can go in another as well.", color = Chalk)
                detail.inLibrary -> Text("Already in your library.", color = Chalk)
            }
            requestLabel(status)?.let { Text("You asked for this. ${requestWord(status)}", color = Chalk) }
            val addable = page.addable.orEmpty()
            if (detail != null && page.canAsk && addable.size > 1) {
                TouchSectionTitle("Library", Modifier.padding(0.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(addable, key = { it.id }) { library ->
                        TouchChip(library.name, selected = library.id == page.libraryId, onClick = { viewModel.chooseRequestLibrary(library.id) })
                    }
                }
            }
            val offered = page.seasonsOffered
            if (detail != null && page.canAsk && route.title.isShow && offered.isNotEmpty()) {
                TouchSectionTitle("Seasons", Modifier.padding(0.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (offered.size > 1) item(key = "all") {
                        TouchChip(
                            if (page.seasonsHere.isEmpty()) "All seasons" else "All the others",
                            selected = page.chosenOffered.size == offered.size,
                            onClick = viewModel::toggleAllRequestSeasons,
                        )
                    }
                    items(offered, key = { it.number }) { season ->
                        TouchChip(season.name, selected = season.number in page.chosenOffered, onClick = { viewModel.toggleRequestSeason(season.number) })
                    }
                }
            }
            if (detail != null && (detail.inLibrary || held > 0) && !page.canAsk) {
                TouchPrimaryButton("Watch", { viewModel.openInPlex(page.title) }, Modifier.fillMaxWidth())
            }
            if (detail != null && page.canAsk) {
                val verb = if (page.places?.adds == true) "Add" else "Request"
                TouchPrimaryButton(
                    page.askLabel(verb),
                    viewModel::submitRequest,
                    Modifier.fillMaxWidth(),
                    enabled = !page.sending,
                )
            }
            page.outcome?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Chalk) }
        }
    }
}

private fun requestWord(status: String?) = when (status) {
    "pending" -> "It's waiting to be approved."
    "approved" -> "It's been approved and is on its way."
    "denied" -> "It was declined."
    else -> ""
}
