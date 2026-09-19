package tv.reely.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.reely.core.SecureStore
import tv.reely.core.Settings
import tv.reely.core.wrapIndex
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexSection
import tv.reely.plex.PlexSubtitle
import tv.reely.xtream.StreamFormat
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials
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
    data object Status : Route
    data class Detail(val ratingKey: String) : Route
}

/** Episodes added for the same show collapse into one tile carrying a count. */
data class EpisodeGroup(
    val showTitle: String,
    val showRatingKey: String?,
    val thumb: String?,
    val episodes: List<PlexItem>,
) {
    val count: Int get() = episodes.size
    val newest: PlexItem get() = episodes.first()
}

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
)

data class PlayerPrefs(
    val subtitleScale: Float = Settings.DEFAULT_SCALE,
    val subtitleBackground: Boolean = false,
    val upNextSeconds: Int = Settings.DEFAULT_UP_NEXT,
)

data class ReelyState(
    val restoring: Boolean = true,
    val stack: List<Route> = listOf(Route.Home),
    val plex: PlexState = PlexState(),
    val home: HomeState = HomeState(),
    val detail: DetailState? = null,
    val live: LiveState = LiveState(),
    val playback: Playback? = null,
    val upNext: PlexItem? = null,
    val prefs: PlayerPrefs = PlayerPrefs(),
) {
    val route: Route get() = stack.last()
}

class ReelyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SecureStore(application)
    private val settings = Settings(application)

    private val _state = MutableStateFlow(
        ReelyState(
            prefs = PlayerPrefs(
                subtitleScale = settings.subtitleScale,
                subtitleBackground = settings.subtitleBackground,
                upNextSeconds = settings.upNextSeconds,
            )
        )
    )
    val state: StateFlow<ReelyState> = _state.asStateFlow()

    private val clientId: String = store.get(SecureStore.PLEX_CLIENT_ID)
        ?: UUID.randomUUID().toString().also { store.put(SecureStore.PLEX_CLIENT_ID, it) }

    private var linkJob: Job? = null
    private var guideJob: Job? = null
    private var timelineJob: Job? = null

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
            current.copy(stack = stack)
        }
        if (route is Route.Detail) loadDetail(route.ratingKey)
        if (route is Route.Home) refreshHome()
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

            val recentEpisodes = showSections.flatMap { section ->
                runCatching {
                    PlexApi.recentlyAdded(base, token, section.key, PlexApi.TYPE_EPISODE, limit = 80)
                }.getOrElse { emptyList() }
            }.sortedByDescending { it.addedAt }

            _state.update {
                it.copy(
                    home = HomeState(
                        continueWatching = onDeck,
                        recentEpisodes = groupEpisodes(recentEpisodes).take(30),
                        recentMovies = recentMovies,
                        busy = false,
                    )
                )
            }
        }
    }

    /**
     * Six episodes of one show dropping at once is one thing that happened, not six.
     * Collapse them onto the show, newest first, and let the tile carry the count.
     */
    private fun groupEpisodes(episodes: List<PlexItem>): List<EpisodeGroup> {
        val order = LinkedHashMap<String, MutableList<PlexItem>>()
        for (episode in episodes) {
            val key = episode.grandparentRatingKey ?: episode.ratingKey
            order.getOrPut(key) { mutableListOf() }.add(episode)
        }
        return order.map { (key, group) ->
            val first = group.first()
            EpisodeGroup(
                showTitle = first.grandparentTitle ?: first.title,
                showRatingKey = first.grandparentRatingKey ?: key,
                thumb = first.grandparentThumb ?: first.thumb,
                episodes = group,
            )
        }
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
                it.copy(detail = it.detail?.copy(selectedSeason = season, busy = true, episodes = emptyList()))
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
        val current = _state.value.playback?.queue?.lastOrNull() ?: return null
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
        categories.firstOrNull()?.let { openCategory(it) }
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
    addedAt = 0,
)

private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: (this::class.java.simpleName + " while talking to the server")
