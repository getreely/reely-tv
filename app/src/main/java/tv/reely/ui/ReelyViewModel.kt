package tv.reely.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.reely.core.SecureStore
import tv.reely.core.Settings
import tv.reely.core.wrapIndex
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexExtra
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexMarker
import tv.reely.plex.PlexSection
import tv.reely.plex.PlexSubtitle
import tv.reely.xtream.StreamFormat
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.EpgStore
import tv.reely.xtream.XmltvImporter
import tv.reely.xtream.XtreamProgramme
import java.util.UUID

/**
 * Movie libraries and show libraries are separate destinations, because that is how the
 * Plex client presents them and how people look for things.
 */
enum class LibraryKind(val plexType: String, val title: String, val filter: Int) {
    MOVIES("movie", "Movies", PlexApi.TYPE_MOVIE),
    SHOWS("show", "TV Shows", PlexApi.TYPE_SHOW),
}

/** Where the app is. A back stack rather than a tab index, so details can be left. */
sealed interface Route {
    data object Home : Route
    data class Library(val kind: LibraryKind) : Route
    data object Live : Route
    data object Search : Route
    data object Status : Route
    data class Detail(val ratingKey: String) : Route
}

/** Episodes added for the same show collapse into one tile carrying a count. */
data class EpisodeGroup(
    val showTitle: String,
    val showRatingKey: String?,
    val thumb: String?,
    val newest: PlexItem,
    val count: Int,
    val addedAt: Long,
)

data class HomeState(
    val continueWatching: List<PlexItem> = emptyList(),
    val recentEpisodes: List<EpisodeGroup> = emptyList(),
    val recentMovies: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = continueWatching.isEmpty() && recentEpisodes.isEmpty() && recentMovies.isEmpty()
}

/** One library grid. No drill-down: opening something goes to its own detail route. */
data class BrowseState(
    val section: PlexSection? = null,
    val items: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

data class DetailState(
    val ratingKey: String,
    val detail: PlexDetail? = null,
    val seasons: List<PlexItem> = emptyList(),
    val selectedSeason: PlexItem? = null,
    val episodes: List<PlexItem> = emptyList(),
    /** What the text block at the top is describing: the show, or an episode under it. */
    val focusedEpisode: PlexItem? = null,
    val trailers: List<PlexExtra> = emptyList(),
    val busy: Boolean = true,
    val error: String? = null,
)

data class PlexState(
    val token: String? = null,
    val serverName: String? = null,
    val baseUrl: String? = null,
    val serverToken: String? = null,
    val sections: List<PlexSection> = emptyList(),
    val browse: Map<LibraryKind, BrowseState> = LibraryKind.entries.associateWith { BrowseState() },
    val linkCode: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = baseUrl != null && serverToken != null

    fun sectionsFor(kind: LibraryKind): List<PlexSection> = sections.filter { it.type == kind.plexType }

    fun browseFor(kind: LibraryKind): BrowseState = browse[kind] ?: BrowseState()
}

data class LiveState(
    val credentials: XtreamCredentials? = null,
    val account: XtreamAccount? = null,
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategory: XtreamCategory? = null,
    val channels: List<XtreamChannel> = emptyList(),
    val guide: Map<Int, List<XtreamProgramme>> = emptyMap(),
    val focusedChannel: XtreamChannel? = null,
    val format: StreamFormat = StreamFormat.TS,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = credentials != null && account != null

    fun nowNext(streamId: Int): List<XtreamProgramme> = guide[streamId].orEmpty()
}

data class SearchState(
    val query: String = "",
    val results: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
)

sealed interface GuideStatus {
    data object Idle : GuideStatus
    data class Importing(val written: Int, val scanned: Int) : GuideStatus
    data class Ready(val count: Int) : GuideStatus
    data class Failed(val message: String) : GuideStatus
}

/**
 * The grid guide. Programmes for the window on screen are held here; everything else
 * stays in SQLite, which is the only reason a full XMLTV guide fits on this hardware.
 */
data class GuideState(
    val status: GuideStatus = GuideStatus.Idle,
    val programmes: Map<String, List<EpgProgramme>> = emptyMap(),
    val windowStart: Long = 0,
    val windowEnd: Long = 0,
    val focusTime: Long = 0,
    val channelIndex: Int = 0,
    val importedAt: Long = 0,
)

data class Playback(
    val title: String,
    val subtitle: String?,
    val url: String,
    val isLive: Boolean,
    val ratingKey: String? = null,
    val startPositionMs: Long = 0,
    val durationMs: Long = 0,
    val channelIndex: Int = -1,
    val format: StreamFormat = StreamFormat.TS,
    val subtitles: List<PlexSubtitle> = emptyList(),
    val queue: List<PlexItem> = emptyList(),
    val queueIndex: Int = -1,
    val markers: List<PlexMarker> = emptyList(),
)

data class PlayerPrefs(
    val subtitleScale: Float = Settings.DEFAULT_SCALE,
    val subtitleBackground: Boolean = false,
    val upNextSeconds: Int = Settings.DEFAULT_UP_NEXT,
    val guidePreview: Boolean = true,
)

data class ReelyState(
    val restoring: Boolean = true,
    val stack: List<Route> = listOf(Route.Home),
    val plex: PlexState = PlexState(),
    val home: HomeState = HomeState(),
    val detail: DetailState? = null,
    val live: LiveState = LiveState(),
    val guide: GuideState = GuideState(),
    val search: SearchState = SearchState(),
    /** Whatever the cursor is on. The hero at the top of a browse screen describes it. */
    val focused: PlexItem? = null,
    val playback: Playback? = null,
    val upNext: PlexItem? = null,
    val prefs: PlayerPrefs = PlayerPrefs(),
) {
    val route: Route get() = stack.last()
}

class ReelyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SecureStore(application)
    private val settings = Settings(application)
    private val epgStore = EpgStore(application)

    private val _state = MutableStateFlow(
        ReelyState(
            prefs = PlayerPrefs(
                subtitleScale = settings.subtitleScale,
                subtitleBackground = settings.subtitleBackground,
                upNextSeconds = settings.upNextSeconds,
                guidePreview = settings.guidePreview,
            )
        )
    )
    val state: StateFlow<ReelyState> = _state.asStateFlow()

    private val clientId: String = store.get(SecureStore.PLEX_CLIENT_ID)
        ?: UUID.randomUUID().toString().also { store.put(SecureStore.PLEX_CLIENT_ID, it) }

    private var linkJob: Job? = null
    private var guideJob: Job? = null
    private var timelineJob: Job? = null
    private var importJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            restorePlex()
            restoreLive()
            _state.update { it.copy(restoring = false) }
        }
    }

    // ---------------------------------------------------------------- Navigation

    fun navigate(route: Route) {
        _state.update { current ->
            // Switching top-level destination replaces the stack rather than growing it.
            val stack = if (route is Route.Detail) current.stack + route else listOf(route)
            current.copy(stack = stack, focused = null)
        }
        if (route is Route.Detail) loadDetail(route.ratingKey)
        if (route is Route.Home) refreshHome()
        if (route is Route.Live) openGuide()
    }

    /** True when there is somewhere to go back to. */
    fun canGoBack(): Boolean = _state.value.stack.size > 1

    fun goBack() {
        _state.update { current ->
            if (current.stack.size <= 1) current
            else current.copy(stack = current.stack.dropLast(1), detail = null)
        }
        val route = _state.value.route
        if (route is Route.Detail) loadDetail(route.ratingKey)
    }

    // ---------------------------------------------------------------- Plex connection

    private suspend fun restorePlex() {
        val token = store.get(SecureStore.PLEX_TOKEN) ?: return
        val base = store.get(SecureStore.PLEX_SERVER_URI)
        val serverToken = store.get(SecureStore.PLEX_SERVER_TOKEN)
        updatePlex {
            it.copy(
                token = token,
                baseUrl = base,
                serverToken = serverToken,
                serverName = store.get(SecureStore.PLEX_SERVER_NAME),
            )
        }
        if (base != null && serverToken != null) loadSections() else connectServer(token)
    }

    fun startPlexLink() {
        if (linkJob?.isActive == true) return
        linkJob = viewModelScope.launch {
            updatePlex { it.copy(busy = true, error = null, linkCode = null) }
            val pin = runCatching { PlexApi.createPin(clientId) }.getOrElse { failure ->
                updatePlex { it.copy(busy = false, error = failure.readable()) }
                return@launch
            }
            updatePlex { it.copy(busy = false, linkCode = pin.code) }

            // plex.tv expires a PIN after 15 minutes; stop looking well before that.
            repeat(150) {
                delay(2_000)
                val token = runCatching { PlexApi.claimPin(clientId, pin.id) }.getOrNull()
                if (token != null) {
                    store.put(SecureStore.PLEX_TOKEN, token)
                    updatePlex { it.copy(token = token, linkCode = null) }
                    connectServer(token)
                    return@launch
                }
            }
            updatePlex { it.copy(linkCode = null, error = "That link code expired. Start again.") }
        }
    }

    fun cancelPlexLink() {
        linkJob?.cancel()
        linkJob = null
        updatePlex { it.copy(linkCode = null, busy = false) }
    }

    private suspend fun connectServer(token: String) {
        updatePlex { it.copy(busy = true, error = null) }
        val servers = runCatching { PlexApi.servers(clientId, token) }.getOrElse { failure ->
            updatePlex { it.copy(busy = false, error = failure.readable()) }
            return
        }
        if (servers.isEmpty()) {
            updatePlex {
                it.copy(
                    busy = false,
                    error = "That account can't see any Plex server. Ask the owner to share the library with it.",
                )
            }
            return
        }
        for (server in servers) {
            val base = PlexApi.firstReachable(server) ?: continue
            store.put(SecureStore.PLEX_SERVER_URI, base)
            store.put(SecureStore.PLEX_SERVER_TOKEN, server.accessToken)
            store.put(SecureStore.PLEX_SERVER_NAME, server.name)
            updatePlex {
                it.copy(
                    baseUrl = base,
                    serverToken = server.accessToken,
                    serverName = server.name,
                    busy = false,
                )
            }
            loadSections()
            return
        }
        updatePlex {
            it.copy(
                busy = false,
                error = "Found ${servers.size} server(s) but none answered. Is it awake and reachable?",
            )
        }
    }

    private suspend fun loadSections() {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        updatePlex { it.copy(busy = true, error = null) }
        val sections = runCatching { PlexApi.sections(base, token) }.getOrElse { failure ->
            updatePlex { it.copy(busy = false, error = failure.readable()) }
            return
        }
        updatePlex { it.copy(busy = false, sections = sections) }

        for (kind in LibraryKind.entries) {
            _state.value.plex.sectionsFor(kind).firstOrNull()?.let { openSection(kind, it) }
        }
        refreshHome()
    }

    fun signOutPlex() {
        cancelPlexLink()
        store.remove(
            SecureStore.PLEX_TOKEN,
            SecureStore.PLEX_SERVER_URI,
            SecureStore.PLEX_SERVER_TOKEN,
            SecureStore.PLEX_SERVER_NAME,
        )
        _state.update { it.copy(plex = PlexState(), home = HomeState(), detail = null) }
    }

    /**
     * Full-screen artwork for a hero. Asked for at 720 wide rather than 1920: it is drawn
     * behind a heavy scrim, so the extra detail would cost megabytes of bitmap on a stick
     * and never be seen.
     */
    fun plexBackdropUrl(path: String?): String? {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return null
        val token = plex.serverToken ?: return null
        return PlexApi.imageUrl(base, token, path, width = 720, height = 405)
    }

    fun toggleWatchedDetail() {
        val detail = _state.value.detail?.detail ?: return
        toggleWatched(detail.asItem())
    }

    fun plexImageUrl(path: String?, width: Int, height: Int): String? {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return null
        val token = plex.serverToken ?: return null
        return PlexApi.imageUrl(base, token, path, width, height)
    }

    // ---------------------------------------------------------------- Home

    fun refreshHome() {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return

        viewModelScope.launch {
            _state.update { it.copy(home = it.home.copy(busy = true, error = null)) }

            // On Deck is rendered exactly as the server composes it: no re-sorting here.
            val onDeck = runCatching { PlexApi.onDeck(base, token) }.getOrElse { emptyList() }

            val movieSections = plex.sectionsFor(LibraryKind.MOVIES)
            val showSections = plex.sectionsFor(LibraryKind.SHOWS)

            val recentMovies = movieSections.flatMap { section ->
                runCatching {
                    PlexApi.recentlyAdded(base, token, section.key, PlexApi.TYPE_MOVIE, limit = 40)
                }.getOrElse { emptyList() }
            }.sortedByDescending { it.addedAt }.take(40)

            val recentEpisodes = recentEpisodeGroups(base, token, showSections)

            _state.update {
                it.copy(
                    home = HomeState(
                        continueWatching = onDeck,
                        recentEpisodes = recentEpisodes,
                        recentMovies = recentMovies,
                        busy = false,
                    )
                )
            }
        }
    }

    /**
     * Six episodes of one show dropping at once is one thing that happened, not six — so
     * they collapse onto the show, with the tile carrying the count.
     *
     * The row is therefore measured in shows, not episodes, and paged until it has enough
     * of them. Asking for a fixed number of episodes does not work: importing one series
     * with eighty episodes would fill the whole row with a single show.
     */
    private suspend fun recentEpisodeGroups(
        base: String,
        token: String,
        sections: List<PlexSection>,
    ): List<EpisodeGroup> {
        val groups = LinkedHashMap<String, EpisodeGroup>()

        for (section in sections) {
            var offset = 0
            var scanned = 0
            while (groups.size < TARGET_SHOW_COUNT && scanned < MAX_EPISODE_SCAN) {
                val page = runCatching {
                    PlexApi.recentlyAdded(
                        base = base,
                        token = token,
                        sectionKey = section.key,
                        type = PlexApi.TYPE_EPISODE,
                        limit = EPISODE_PAGE,
                        offset = offset,
                    )
                }.getOrElse { emptyList() }
                if (page.isEmpty()) break

                for (episode in page) {
                    val key = episode.grandparentRatingKey ?: episode.ratingKey
                    val existing = groups[key]
                    if (existing == null) {
                        groups[key] = EpisodeGroup(
                            showTitle = episode.grandparentTitle ?: episode.title,
                            showRatingKey = episode.grandparentRatingKey ?: key,
                            thumb = episode.grandparentThumb ?: episode.thumb,
                            newest = episode,
                            count = 1,
                            addedAt = episode.addedAt,
                        )
                    } else {
                        // Only the newest episode is kept; the rest are just a tally.
                        groups[key] = existing.copy(count = existing.count + 1)
                    }
                }

                scanned += page.size
                offset += page.size
                if (page.size < EPISODE_PAGE) break
            }
        }

        return groups.values.sortedByDescending { it.addedAt }.take(TARGET_SHOW_COUNT)
    }

    // ---------------------------------------------------------------- Library grids

    fun openSection(kind: LibraryKind, section: PlexSection) {
        viewModelScope.launch {
            val plex = _state.value.plex
            val base = plex.baseUrl ?: return@launch
            val token = plex.serverToken ?: return@launch
            updateBrowse(kind) {
                it.copy(busy = true, error = null, section = section, items = emptyList())
            }
            val path = "/library/sections/${section.key}/all?type=${kind.filter}"
            val items = runCatching { PlexApi.items(base, token, path, limit = 400) }
                .getOrElse { failure ->
                    updateBrowse(kind) { it.copy(busy = false, error = failure.readable()) }
                    return@launch
                }
            updateBrowse(kind) { it.copy(busy = false, items = items) }
        }
    }

    fun dismissBrowseError(kind: LibraryKind) = updateBrowse(kind) { it.copy(error = null) }

    // ---------------------------------------------------------------- Detail

    private fun loadDetail(ratingKey: String) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return

        _state.update { it.copy(detail = DetailState(ratingKey = ratingKey, busy = true)) }

        viewModelScope.launch {
            val detail = runCatching { PlexApi.detail(base, token, ratingKey) }.getOrElse { failure ->
                _state.update {
                    it.copy(detail = it.detail?.copy(busy = false, error = failure.readable()))
                }
                return@launch
            }
            if (detail == null) {
                _state.update {
                    it.copy(detail = it.detail?.copy(busy = false, error = "Plex has nothing for that item."))
                }
                return@launch
            }

            _state.update { it.copy(detail = it.detail?.copy(detail = detail, busy = detail.isShow)) }

            launch {
                val trailers = runCatching { PlexApi.trailers(base, token, ratingKey) }
                    .getOrElse { emptyList() }
                _state.update { current ->
                    if (current.detail?.ratingKey != ratingKey) current
                    else current.copy(detail = current.detail.copy(trailers = trailers))
                }
            }

            if (!detail.isShow) return@launch

            val seasons = runCatching { PlexApi.children(base, token, ratingKey) }
                .getOrElse { emptyList() }
                .filter { it.type == "season" }
            _state.update { it.copy(detail = it.detail?.copy(seasons = seasons, busy = seasons.isNotEmpty())) }
            seasons.firstOrNull()?.let { selectSeason(it) } ?: run {
                _state.update { it.copy(detail = it.detail?.copy(busy = false)) }
            }
        }
    }

    fun selectSeason(season: PlexItem) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    detail = it.detail?.copy(
                        selectedSeason = season,
                        busy = true,
                        episodes = emptyList(),
                        focusedEpisode = null,
                    )
                )
            }
            val episodes = runCatching { PlexApi.children(base, token, season.ratingKey) }
                .getOrElse { emptyList() }
            _state.update { it.copy(detail = it.detail?.copy(episodes = episodes, busy = false)) }
        }
    }

    // ---------------------------------------------------------------- Playback

    /**
     * The Play button on a detail page. For a show that means the episode you are part-way
     * through, or the first one; for a film it means the film.
     */
    fun playFromDetail() {
        val detailState = _state.value.detail ?: return
        val detail = detailState.detail ?: return
        if (detail.isShow) {
            val episode = detailState.episodes.firstOrNull { it.resumeFraction != null }
                ?: detailState.episodes.firstOrNull()
                ?: return
            play(episode, queue = detailState.episodes)
        } else {
            play(detail.asItem())
        }
    }

    /** Play a library item, resuming where Plex says it was left. */
    fun play(item: PlexItem, queue: List<PlexItem> = emptyList(), resume: Boolean = true) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return

        viewModelScope.launch {
            val resolved = runCatching { PlexApi.playback(base, token, item.ratingKey) }
                .getOrElse { failure ->
                    reportPlaybackProblem(failure.readable())
                    return@launch
                }
            if (resolved == null) {
                reportPlaybackProblem("Plex returned no playable file for \"${item.title}\".")
                return@launch
            }

            val effectiveQueue = queue.ifEmpty { siblingQueue(item) }
            _state.update {
                it.copy(
                    upNext = null,
                    playback = Playback(
                        title = if (item.type == "episode") item.title else item.title,
                        subtitle = subtitleLineFor(item),
                        url = resolved.url,
                        isLive = false,
                        ratingKey = item.ratingKey,
                        startPositionMs = if (resume) item.viewOffsetMs else 0,
                        durationMs = item.durationMs,
                        subtitles = resolved.subtitles,
                        markers = resolved.markers,
                        queue = effectiveQueue,
                        queueIndex = effectiveQueue.indexOfFirst { entry -> entry.ratingKey == item.ratingKey },
                    ),
                )
            }
            if (effectiveQueue.isEmpty() && item.type == "episode") primeQueue(item)
        }
    }

    /** Surface it wherever the person actually is — a detail page, or the home rows. */
    private fun reportPlaybackProblem(message: String) {
        _state.update { current ->
            if (current.detail != null) current.copy(detail = current.detail.copy(error = message))
            else current.copy(home = current.home.copy(error = message))
        }
    }

    private fun subtitleLineFor(item: PlexItem): String? = when (item.type) {
        "episode" -> listOfNotNull(item.grandparentTitle, item.caption).joinToString("  ·  ")
            .takeIf { it.isNotBlank() }

        else -> item.caption
    }

    /** Episodes already loaded on the detail page make the natural up-next queue. */
    private fun siblingQueue(item: PlexItem): List<PlexItem> {
        if (item.type != "episode") return emptyList()
        val episodes = _state.value.detail?.episodes.orEmpty()
        return if (episodes.any { it.ratingKey == item.ratingKey }) episodes else emptyList()
    }

    /** Started from Continue Watching, so the season's other episodes are not loaded yet. */
    private fun primeQueue(item: PlexItem) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val seasonKey = item.parentRatingKey ?: return
        viewModelScope.launch {
            val episodes = runCatching { PlexApi.children(base, token, seasonKey) }.getOrElse { return@launch }
            val index = episodes.indexOfFirst { it.ratingKey == item.ratingKey }
            if (index < 0) return@launch
            _state.update { current ->
                val playback = current.playback ?: return@update current
                if (playback.ratingKey != item.ratingKey) return@update current
                current.copy(playback = playback.copy(queue = episodes, queueIndex = index))
            }
        }
    }

    /** The player reached the end of a file. Work out what follows, if anything. */
    fun onPlaybackEnded() {
        val playback = _state.value.playback ?: return
        if (playback.isLive) return
        val next = playback.queue.getOrNull(playback.queueIndex + 1)
        if (next != null) {
            _state.update { it.copy(upNext = next) }
            return
        }
        // End of a season: carry on into the next one, the way a binge actually goes.
        viewModelScope.launch { _state.update { it.copy(upNext = firstOfNextSeason()) } }
    }

    private suspend fun firstOfNextSeason(): PlexItem? {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return null
        val token = plex.serverToken ?: return null
        val playback = _state.value.playback ?: return null
        val current = playback.queue.getOrNull(playback.queueIndex)
            ?: playback.queue.lastOrNull()
            ?: return null
        val showKey = current.grandparentRatingKey ?: return null

        val seasons = runCatching { PlexApi.children(base, token, showKey) }
            .getOrElse { return null }
            .filter { it.type == "season" }
        val position = seasons.indexOfFirst { it.ratingKey == current.parentRatingKey }
        val next = seasons.getOrNull(position + 1) ?: return null
        return runCatching { PlexApi.children(base, token, next.ratingKey) }
            .getOrElse { return null }
            .firstOrNull()
    }

    fun playUpNext() {
        val next = _state.value.upNext ?: return
        val queue = _state.value.playback?.queue.orEmpty()
        _state.update { it.copy(upNext = null) }
        play(next, queue = queue.takeIf { list -> list.any { it.ratingKey == next.ratingKey } } ?: emptyList())
    }

    fun dismissUpNext() = _state.update { it.copy(upNext = null) }

    /**
     * Tell Plex where we are. Without this the server never learns anything was watched,
     * and Continue Watching on every device stays wrong.
     */
    fun reportProgress(positionMs: Long, playing: Boolean) {
        val playback = _state.value.playback ?: return
        val ratingKey = playback.ratingKey ?: return
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        timelineJob?.cancel()
        timelineJob = viewModelScope.launch {
            runCatching {
                PlexApi.reportTimeline(
                    base = base,
                    token = token,
                    ratingKey = ratingKey,
                    positionMs = positionMs,
                    durationMs = playback.durationMs,
                    state = if (playing) "playing" else "paused",
                )
            }
        }
    }

    fun stopPlayback(positionMs: Long = 0) {
        val playback = _state.value.playback
        val ratingKey = playback?.ratingKey
        val plex = _state.value.plex
        val base = plex.baseUrl
        val token = plex.serverToken
        if (ratingKey != null && base != null && token != null && positionMs > 0) {
            viewModelScope.launch {
                runCatching {
                    PlexApi.reportTimeline(base, token, ratingKey, positionMs, playback.durationMs, "stopped")
                }
                refreshHome()
            }
        }
        _state.update { it.copy(playback = null, upNext = null) }
    }

    // ---------------------------------------------------------------- Subtitle preferences

    fun nudgeSubtitleScale(delta: Float) {
        val next = (settings.subtitleScale + delta)
            .coerceIn(Settings.MIN_SCALE, Settings.MAX_SCALE)
        settings.subtitleScale = next
        _state.update { it.copy(prefs = it.prefs.copy(subtitleScale = next)) }
    }

    fun toggleGuidePreview() {
        val next = !settings.guidePreview
        settings.guidePreview = next
        _state.update { it.copy(prefs = it.prefs.copy(guidePreview = next)) }
    }

    fun nudgeUpNextSeconds(delta: Int) {
        val next = (settings.upNextSeconds + delta).coerceIn(0, Settings.MAX_UP_NEXT)
        settings.upNextSeconds = next
        _state.update { it.copy(prefs = it.prefs.copy(upNextSeconds = next)) }
    }

    fun toggleSubtitleBackground() {
        val next = !settings.subtitleBackground
        settings.subtitleBackground = next
        _state.update { it.copy(prefs = it.prefs.copy(subtitleBackground = next)) }
    }

    // ---------------------------------------------------------------- Search

    fun setQuery(query: String) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(search = it.search.copy(results = emptyList(), busy = false)) }
            return
        }
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        searchJob = viewModelScope.launch {
            _state.update { it.copy(search = it.search.copy(busy = true)) }
            // Typing on a remote is slow; wait for a pause rather than asking per letter.
            delay(400)
            val results = runCatching { PlexApi.search(base, token, query) }.getOrElse { emptyList() }
            _state.update { current ->
                if (current.search.query != query) current
                else current.copy(search = current.search.copy(results = results, busy = false))
            }
        }
    }

    // ---------------------------------------------------------------- Focus and watched

    /** The cursor moved onto something; the hero above follows it. */
    fun focusItem(item: PlexItem?) {
        if (_state.value.focused?.ratingKey == item?.ratingKey) return
        _state.update { it.copy(focused = item) }
    }

    /** Null means the text block goes back to describing the show itself. */
    fun focusEpisode(episode: PlexItem?) {
        _state.update { it.copy(detail = it.detail?.copy(focusedEpisode = episode)) }
    }

    /**
     * Marks watched on the server so every Plex client agrees. The tick flips here first
     * and is put back if the server disagrees, because waiting on a round trip to redraw
     * a checkbox feels broken.
     */
    fun toggleWatched(item: PlexItem) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val watched = !item.isWatched

        applyWatched(item.ratingKey, watched)
        viewModelScope.launch {
            val ok = runCatching { PlexApi.setWatched(base, token, item.ratingKey, watched) }.isSuccess
            if (!ok) {
                applyWatched(item.ratingKey, !watched)
                reportPlaybackProblem("Couldn't mark that as ${if (watched) "watched" else "unwatched"}.")
            } else {
                refreshHome()
            }
        }
    }

    /** Patches the tick everywhere the same item is on screen. */
    private fun applyWatched(ratingKey: String, watched: Boolean) {
        fun patch(item: PlexItem): PlexItem =
            if (item.ratingKey != ratingKey) item
            else item.copy(
                viewCount = if (watched) maxOf(1, item.viewCount) else 0,
                viewOffsetMs = if (watched) 0 else item.viewOffsetMs,
            )

        _state.update { current ->
            current.copy(
                focused = current.focused?.let(::patch),
                home = current.home.copy(
                    continueWatching = current.home.continueWatching.map(::patch),
                    recentMovies = current.home.recentMovies.map(::patch),
                ),
                plex = current.plex.copy(
                    browse = current.plex.browse.mapValues { (_, browse) ->
                        browse.copy(items = browse.items.map(::patch))
                    }
                ),
                detail = current.detail?.let { detail ->
                    detail.copy(
                        episodes = detail.episodes.map(::patch),
                        focusedEpisode = detail.focusedEpisode?.let(::patch),
                    )
                },
            )
        }
    }

    /** Plays the item's trailer, when the server has one to give. */
    fun playTrailer() {
        val detail = _state.value.detail ?: return
        val trailer = detail.trailers.firstOrNull() ?: return
        val title = detail.detail?.title ?: "Trailer"
        _state.update {
            it.copy(
                upNext = null,
                playback = Playback(
                    title = title,
                    subtitle = trailer.title,
                    url = trailer.url,
                    isLive = false,
                    durationMs = trailer.durationMs,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- Live TV

    private suspend fun restoreLive() {
        val host = store.get(SecureStore.XTREAM_HOST) ?: return
        val username = store.get(SecureStore.XTREAM_USERNAME) ?: return
        val password = store.get(SecureStore.XTREAM_PASSWORD) ?: return
        connectXtream(XtreamCredentials(host, username, password), persist = false)
    }

    fun signInXtream(host: String, username: String, password: String) {
        val base = XtreamApi.normalizeBase(host)
        if (base.isEmpty() || username.isBlank() || password.isBlank()) {
            updateLive { it.copy(error = "Host, username and password are all required.") }
            return
        }
        viewModelScope.launch {
            connectXtream(XtreamCredentials(base, username.trim(), password), persist = true)
        }
    }

    private suspend fun connectXtream(credentials: XtreamCredentials, persist: Boolean) {
        updateLive { it.copy(busy = true, error = null) }
        val account = runCatching { XtreamApi.login(credentials) }.getOrElse { failure ->
            updateLive { it.copy(busy = false, error = failure.readable()) }
            return
        }
        if (persist) {
            store.put(SecureStore.XTREAM_HOST, credentials.base)
            store.put(SecureStore.XTREAM_USERNAME, credentials.username)
            store.put(SecureStore.XTREAM_PASSWORD, credentials.password)
        }
        val categories = runCatching { XtreamApi.liveCategories(credentials) }.getOrElse { failure ->
            updateLive {
                it.copy(busy = false, credentials = credentials, account = account, error = failure.readable())
            }
            return
        }
        updateLive {
            it.copy(
                busy = false,
                credentials = credentials,
                account = account,
                categories = categories,
                error = null,
            )
        }
    }

    /** Backing out of the grid returns to the category picker. */
    fun clearCategory() {
        guideJob?.cancel()
        updateLive { it.copy(selectedCategory = null, channels = emptyList(), focusedChannel = null) }
    }

    fun openCategory(category: XtreamCategory) {
        viewModelScope.launch {
            val credentials = _state.value.live.credentials ?: return@launch
            updateLive {
                it.copy(
                    busy = true,
                    error = null,
                    selectedCategory = category,
                    channels = emptyList(),
                    focusedChannel = null,
                )
            }
            val channels = runCatching { XtreamApi.liveChannels(credentials, category.id) }
                .getOrElse { failure ->
                    updateLive { it.copy(busy = false, error = failure.readable()) }
                    return@launch
                }
            updateLive { it.copy(busy = false, channels = channels) }
            _state.update { it.copy(guide = it.guide.copy(channelIndex = 0)) }
            loadGuideWindow()
            channels.firstOrNull()?.let { focusChannel(it) }
        }
    }

    /**
     * The guide is fetched one channel at a time, for the channel being looked at. The
     * full XMLTV dump is the alternative and it does not fit in a stick's memory.
     */
    fun focusChannel(channel: XtreamChannel) {
        updateLive { it.copy(focusedChannel = channel) }
        if (_state.value.live.guide.containsKey(channel.streamId)) return

        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            // A beat of debounce so scrolling the list doesn't fire a request per row.
            delay(250)
            val credentials = _state.value.live.credentials ?: return@launch
            val programmes = runCatching { XtreamApi.shortEpg(credentials, channel.streamId) }
                .getOrElse { emptyList() }
            updateLive { it.copy(guide = it.guide + (channel.streamId to programmes)) }
        }
    }

    fun playChannel(index: Int) {
        val live = _state.value.live
        val credentials = live.credentials ?: return
        val channel = live.channels.getOrNull(index) ?: return
        val now = System.currentTimeMillis() / 1000
        val programme = live.nowNext(channel.streamId).firstOrNull { it.progressAt(now) != null }
        _state.update {
            it.copy(
                upNext = null,
                playback = Playback(
                    title = channel.name,
                    subtitle = programme?.title ?: live.selectedCategory?.name,
                    url = XtreamApi.streamUrl(credentials, channel, live.format),
                    isLive = true,
                    channelIndex = index,
                    format = live.format,
                ),
            )
        }
    }

    /**
     * Previous or next episode, from the queue the detail page or Continue Watching
     * already supplied for Up Next. Does nothing at either end of a season.
     */
    fun stepEpisode(delta: Int) {
        val playback = _state.value.playback ?: return
        if (playback.isLive) return

        val next = playback.queue.getOrNull(playback.queueIndex + delta)
        if (next != null) {
            play(next, queue = playback.queue)
            return
        }
        // Off the end of a season. Up Next already carries on into the next one, so the
        // button that means the same thing should too.
        if (delta > 0) {
            viewModelScope.launch {
                firstOfNextSeason()?.let { play(it) }
            }
        }
    }

    /** Channel surfing from the player — the thing that decides whether this feels like a TV app. */
    fun stepChannel(delta: Int) {
        val playback = _state.value.playback ?: return
        if (!playback.isLive) return
        val channels = _state.value.live.channels
        if (channels.isEmpty()) return
        playChannel(wrapIndex(playback.channelIndex + delta, channels.size))
    }

    /** TS and HLS behave differently on recovery; worth being able to switch on the spot. */
    fun toggleFormat() {
        val next = if (_state.value.live.format == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS
        updateLive { it.copy(format = next) }
        val playback = _state.value.playback ?: return
        if (playback.isLive) playChannel(playback.channelIndex)
    }

    // ---------------------------------------------------------------- Grid guide

    /** Entering the guide: show what is stored, then top it up if it has gone stale. */
    fun openGuide() {
        val now = System.currentTimeMillis() / 1000
        val windowStart = (now / 1_800) * 1_800 - 3_600
        _state.update {
            it.copy(
                guide = it.guide.copy(
                    focusTime = now,
                    windowStart = windowStart,
                    windowEnd = windowStart + WINDOW_SECONDS,
                )
            )
        }
        viewModelScope.launch {
            val importedAt = withContext(Dispatchers.IO) { epgStore.lastImportedAt() }
            val stored = withContext(Dispatchers.IO) { epgStore.programmeCount() }
            _state.update {
                it.copy(
                    guide = it.guide.copy(
                        importedAt = importedAt,
                        status = if (stored > 0) GuideStatus.Ready(stored) else it.guide.status,
                    )
                )
            }
            loadGuideWindow()
            val stale = importedAt == 0L || now - importedAt > REFRESH_AFTER_SECONDS
            if (stale) refreshGuide(force = false)
        }
    }

    /**
     * Pulls the provider's whole XMLTV guide and streams it into SQLite. This is the one
     * genuinely heavy thing the app does, so it reports progress and can be cancelled.
     */
    fun refreshGuide(force: Boolean) {
        if (importJob?.isActive == true) {
            if (!force) return
            importJob?.cancel()
        }
        val credentials = _state.value.live.credentials ?: run {
            _state.update {
                it.copy(guide = it.guide.copy(status = GuideStatus.Failed("Sign in to a provider first.")))
            }
            return
        }
        importJob = viewModelScope.launch {
            _state.update { it.copy(guide = it.guide.copy(status = GuideStatus.Importing(0, 0))) }
            val now = System.currentTimeMillis() / 1_000
            runCatching {
                XmltvImporter.import(credentials, epgStore, now) { written, scanned ->
                    _state.update {
                        it.copy(guide = it.guide.copy(status = GuideStatus.Importing(written, scanned)))
                    }
                }
            }.onSuccess { written ->
                _state.update {
                    it.copy(guide = it.guide.copy(status = GuideStatus.Ready(written), importedAt = now))
                }
                loadGuideWindow()
            }.onFailure { failure ->
                _state.update {
                    it.copy(guide = it.guide.copy(status = GuideStatus.Failed(failure.readable())))
                }
            }
        }
    }

    /** Reads only the window the grid can draw, for the channels currently listed. */
    private fun loadGuideWindow() {
        val guide = _state.value.guide
        val channelIds = _state.value.live.channels.mapNotNull { it.epgChannelId }
        if (channelIds.isEmpty() || guide.windowEnd <= guide.windowStart) return
        viewModelScope.launch {
            val programmes = withContext(Dispatchers.IO) {
                epgStore.programmes(channelIds, guide.windowStart, guide.windowEnd)
            }
            _state.update { it.copy(guide = it.guide.copy(programmes = programmes)) }
        }
    }

    fun guideMoveChannel(delta: Int) {
        val channels = _state.value.live.channels
        if (channels.isEmpty()) return
        val next = (_state.value.guide.channelIndex + delta).coerceIn(0, channels.lastIndex)
        _state.update { it.copy(guide = it.guide.copy(channelIndex = next)) }
    }

    /**
     * Left and right step programme by programme, the way a guide should. Where a channel
     * has no listing to step onto, the window slides by half an hour instead.
     */
    fun guideMoveTime(delta: Int) {
        val state = _state.value
        val guide = state.guide
        val channel = state.live.channels.getOrNull(guide.channelIndex)
        val listing = channel?.epgChannelId?.let { guide.programmes[it] }.orEmpty()

        val current = listing.indexOfFirst { it.isOnAt(guide.focusTime) }
        val target = if (current >= 0) listing.getOrNull(current + delta) else null
        val focusTime = target?.start ?: (guide.focusTime + delta * 1_800L)

        val clamped = focusTime.coerceAtLeast(guide.windowStart)
        _state.update { it.copy(guide = it.guide.copy(focusTime = clamped)) }

        // Sliding near either edge pulls the next stretch out of the database.
        if (clamped > guide.windowEnd - 3 * 3_600 || clamped < guide.windowStart + 3_600) {
            val windowStart = (clamped / 1_800) * 1_800 - 3_600
            _state.update {
                it.copy(
                    guide = it.guide.copy(
                        windowStart = windowStart,
                        windowEnd = windowStart + WINDOW_SECONDS,
                    )
                )
            }
            loadGuideWindow()
        }
    }

    fun guideJumpToNow() {
        val now = System.currentTimeMillis() / 1_000
        val windowStart = (now / 1_800) * 1_800 - 3_600
        _state.update {
            it.copy(
                guide = it.guide.copy(
                    focusTime = now,
                    windowStart = windowStart,
                    windowEnd = windowStart + WINDOW_SECONDS,
                )
            )
        }
        loadGuideWindow()
    }

    fun guidePlaySelected() {
        playChannel(_state.value.guide.channelIndex)
    }

    fun signOutXtream() {
        store.remove(SecureStore.XTREAM_HOST, SecureStore.XTREAM_USERNAME, SecureStore.XTREAM_PASSWORD)
        _state.update { it.copy(live = LiveState()) }
    }

    fun dismissPlexError() = updatePlex { it.copy(error = null) }

    fun dismissLiveError() = updateLive { it.copy(error = null) }

    private fun updatePlex(block: (PlexState) -> PlexState) {
        _state.update { it.copy(plex = block(it.plex)) }
    }

    private fun updateBrowse(kind: LibraryKind, block: (BrowseState) -> BrowseState) {
        updatePlex { plex -> plex.copy(browse = plex.browse + (kind to block(plex.browseFor(kind)))) }
    }

    private fun updateLive(block: (LiveState) -> LiveState) {
        _state.update { it.copy(live = block(it.live)) }
    }

    override fun onCleared() {
        super.onCleared()
        epgStore.close()
    }

    private companion object {
        /** How much of the guide is held in memory at once. */
        const val WINDOW_SECONDS = 24L * 3_600

        /** Guide data older than this is worth fetching again. */
        const val REFRESH_AFTER_SECONDS = 6L * 3_600

        /** How many shows the Recently Added row aims to carry. */
        const val TARGET_SHOW_COUNT = 30
        const val EPISODE_PAGE = 200

        /** A ceiling, so a library of nothing but one huge series still finishes. */
        const val MAX_EPISODE_SCAN = 2_000
    }
}

/** A detail page already holds everything the player needs from an item. */
private fun PlexDetail.asItem(): PlexItem = PlexItem(
    ratingKey = ratingKey,
    title = title,
    type = type,
    thumb = thumb,
    art = art,
    summary = summary,
    year = year,
    index = index,
    parentIndex = parentIndex,
    parentRatingKey = null,
    parentTitle = null,
    grandparentRatingKey = null,
    grandparentTitle = grandparentTitle,
    grandparentThumb = null,
    durationMs = durationMs,
    viewOffsetMs = viewOffsetMs,
    leafCount = leafCount,
    viewedLeafCount = 0,
    viewCount = viewCount,
    addedAt = 0,
)

private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: (this::class.java.simpleName + " while talking to the server")
