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
import tv.reely.core.LivePlayer
import tv.reely.core.SecureStore
import tv.reely.core.ThemePlayer
import tv.reely.core.UpdateInfo
import tv.reely.core.Updater
import tv.reely.core.Settings
import tv.reely.BuildConfig
import tv.reely.core.wrapIndex
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexGenre
import tv.reely.plex.PlexExtra
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexMarker
import tv.reely.plex.PlexSection
import tv.reely.plex.PlexServer
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

/**
 * A library tab shows one of two things: the rows that say what you were watching and
 * what has arrived, or the whole library as a grid. They used to be stacked on one
 * surface, which meant scrolling past the rows to reach the library.
 */
enum class LibraryView { HOME, GRID }

/** Where the app is. A back stack rather than a tab index, so details can be left. */
sealed interface Route {
    data object Home : Route
    data class Library(
        val kind: LibraryKind,
        val view: LibraryView = LibraryView.HOME,
    ) : Route
    data object Live : Route
    data object Search : Route
    data object Settings : Route
    /**
     * A page for one thing. An episode has no page of its own: it is a place inside its
     * show's, so the season to open and the episode to land on travel with the route.
     */
    data class Detail(
        val ratingKey: String,
        val seasonKey: String? = null,
        val episodeKey: String? = null,
        /** Which server holds it. Null means the one connected. */
        val serverBase: String? = null,
    ) : Route
}

/** Episodes added for the same show collapse into one tile carrying a count. */
data class EpisodeGroup(
    val showTitle: String,
    val showRatingKey: String?,
    val thumb: String?,
    val newest: PlexItem,
    val count: Int,
    val addedAt: Long,
    val librarySectionId: String?,
    val serverBase: String?,
) {
    /** As with an item: unique across servers, which a show's rating key is not. */
    val listKey: String get() = (serverBase ?: "") + "|" + (showRatingKey ?: showTitle)
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

/**
 * How a library grid is ordered. The value is Plex's own sort key, so the server does the
 * ordering over the whole library rather than this app re-sorting one page of it.
 */
enum class LibrarySort(val key: String, val label: String) {
    TITLE("titleSort:asc", "A–Z"),
    ADDED("addedAt:desc", "Recently Added"),
    RELEASED("year:desc", "Newest First"),
    RATED("rating:desc", "Top Rated"),
}

/** One library grid. No drill-down: opening something goes to its own detail route. */
data class BrowseState(
    val section: PlexSection? = null,
    val items: List<PlexItem> = emptyList(),
    /** Newest by release date, which is not the same as newest to the library. */
    val released: List<PlexItem> = emptyList(),
    val genres: List<PlexGenre> = emptyList(),
    val sort: LibrarySort = LibrarySort.TITLE,
    val genreId: String? = null,
    val unwatchedOnly: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** True when the grid is showing less than the whole library. */
    val isFiltered: Boolean get() = genreId != null || unwatchedOnly
}

data class DetailState(
    val ratingKey: String,
    val serverBase: String? = null,
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

/**
 * One library, on one server. An account with two servers has two sets of libraries and
 * no reason to care which server a library lives on beyond telling two "Movies" apart,
 * so they are offered as one list rather than making somebody pick a server first.
 */
data class LibraryChoice(
    val serverName: String,
    val baseUrl: String,
    val token: String,
    val section: PlexSection,
) {
    /** Section keys are only unique within a server, so the server has to be in this. */
    val id: String get() = "$serverName|${section.key}"
}

data class PlexState(
    val token: String? = null,
    val serverName: String? = null,
    val baseUrl: String? = null,
    val serverToken: String? = null,
    val sections: List<PlexSection> = emptyList(),
    /** Every server the account can see, so one of several can be chosen. */
    val servers: List<PlexServer> = emptyList(),
    /** Every library on every server the account can reach. */
    val libraryChoices: List<LibraryChoice> = emptyList(),
    val browse: Map<LibraryKind, BrowseState> = LibraryKind.entries.associateWith { BrowseState() },
    val linkCode: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val favouriteSections: Set<String> = emptySet(),
) {
    val isConnected: Boolean get() = baseUrl != null && serverToken != null

    fun sectionsFor(kind: LibraryKind): List<PlexSection> = sections.filter { it.type == kind.plexType }

    /** The token for a server, by its address. Null base means the one connected. */
    fun tokenFor(base: String?): String? = when {
        base == null || base == baseUrl -> serverToken
        else -> libraryChoices.firstOrNull { it.baseUrl == base }?.token
    }

    /**
     * The servers Home draws from: those holding a pinned library, or every one of them
     * when nothing is pinned. This is what makes Home the account's rather than one
     * machine's, which is how a Plex client behaves.
     */
    fun homeSources(): List<LibraryChoice> {
        val pinned = if (favouriteSections.isEmpty()) libraryChoices
        else libraryChoices.filter { it.id in favouriteSections }
        return pinned.ifEmpty { libraryChoices }
    }

    fun choicesFor(kind: LibraryKind): List<LibraryChoice> =
        libraryChoices.filter { it.section.type == kind.plexType }

    /** What the tab menu offers: the chosen few, or everything when none are chosen. */
    fun menuChoicesFor(kind: LibraryKind): List<LibraryChoice> {
        val all = choicesFor(kind)
        if (favouriteSections.isEmpty()) return all
        return all.filter { it.id in favouriteSections }.ifEmpty { all }
    }

    /** Only worth naming the server when there is more than one to confuse. */
    val namesNeedServer: Boolean get() = servers.size > 1

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
    /** Live channels whose name matches. Empty when no provider is configured. */
    val channels: List<XtreamChannel> = emptyList(),
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
    val serverBase: String? = null,
    val queue: List<PlexItem> = emptyList(),
    val queueIndex: Int = -1,
    val markers: List<PlexMarker> = emptyList(),
    /** True when the server is encoding this rather than handing over the file. */
    val transcoding: Boolean = false,
    val transcodeSession: String? = null,
)

data class PlayerPrefs(
    val subtitleScale: Float = Settings.DEFAULT_SCALE,
    val subtitleBackground: Boolean = false,
    val upNextSeconds: Int = Settings.DEFAULT_UP_NEXT,
    val guidePreview: Boolean = true,
    val playbackMode: String = Settings.MODE_AUTO,
    val maxBitrateKbps: Int = 0,
    val themeMusic: Boolean = false,
    val themeVolume: Float = Settings.DEFAULT_THEME_VOLUME,
)

/** Where a check for a newer build has got to. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus

    /** Published and newer than this build, by its own account. */
    data class Available(val info: UpdateInfo) : UpdateStatus

    /** Published, but with no manifest saying what it is. */
    data class Unlabelled(val info: UpdateInfo) : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Downloading(val read: Long, val total: Long) : UpdateStatus
    data object Handed : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

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
    /**
     * Extra live channels shown alongside the one playing. The channel in [playback] is
     * always the first tile; these are the rest, so an empty list is the ordinary
     * single-channel player and nothing about it changes.
     */
    val multiview: List<XtreamChannel> = emptyList(),
    val upNext: PlexItem? = null,
    val prefs: PlayerPrefs = PlayerPrefs(),
    val update: UpdateStatus = UpdateStatus.Idle,
) {
    val route: Route get() = stack.last()
}

class ReelyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SecureStore(application)
    private val settings = Settings(application)
    private val epgStore = EpgStore(application)

    /** Shared by the guide's preview and the full-screen player, so one becomes the other. */
    val livePlayer = LivePlayer(application)

    /** A show's title music, under its page. */
    private val themePlayer = ThemePlayer(application)

    private val _state = MutableStateFlow(
        ReelyState(
            prefs = PlayerPrefs(
                subtitleScale = settings.subtitleScale,
                subtitleBackground = settings.subtitleBackground,
                upNextSeconds = settings.upNextSeconds,
                guidePreview = settings.guidePreview,
                playbackMode = settings.playbackMode,
                maxBitrateKbps = settings.maxBitrateKbps,
                themeMusic = settings.themeMusic,
                themeVolume = settings.themeVolume,
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
    private var channelsJob: Job? = null
    private val browseJobs = mutableMapOf<LibraryKind, Job>()
    private var updateJob: Job? = null
    private var libraryScanJob: Job? = null
    private var themeJob: Job? = null

    /** Every live channel the account carries, fetched once and reused by search. */
    private var allChannels: List<XtreamChannel>? = null

    init {
        PlexApi.clientId = clientId
        updatePlex { it.copy(favouriteSections = settings.favouriteSections) }
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
        // The guide's preview is the shared player, and nothing else on screen would
        // account for the sound if it were left running.
        if (route !is Route.Live && _state.value.playback == null) livePlayer.stop()
        if (route !is Route.Detail) stopTheme()
        if (route is Route.Detail) loadDetail(route)
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
        if (route is Route.Detail) loadDetail(route) else stopTheme()
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
        // Only the chosen server was ever stored, so the rest have to be asked for again
        // before anything can offer to switch to them.
        loadServerList(token)
    }

    private fun loadServerList(token: String) {
        viewModelScope.launch {
            val servers = runCatching { PlexApi.servers(clientId, token) }.getOrElse { return@launch }
            updatePlex { it.copy(servers = servers) }
            scanLibraries()
        }
    }

    /**
     * Asks every server the account can reach what libraries it has, so the tab menu can
     * offer them all at once. Done in the background and once: it costs a reachability
     * probe and one read per server, which is a lot to do while somebody waits and
     * nothing at all to do while they are looking at the screen that is already loaded.
     */
    private fun scanLibraries() {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = viewModelScope.launch {
            val plex = _state.value.plex
            val found = mutableListOf<LibraryChoice>()
            for (server in plex.servers) {
                // The one in use has already been probed and answered.
                val base = if (server.name == plex.serverName && plex.baseUrl != null) plex.baseUrl
                else PlexApi.firstReachable(server) ?: continue
                val token = server.accessToken
                val sections = runCatching { PlexApi.sections(base, token) }.getOrNull() ?: continue
                sections.forEach { section ->
                    found += LibraryChoice(
                        serverName = server.name,
                        baseUrl = base,
                        token = token,
                        section = section,
                    )
                }
                // Published as each server answers, so a slow one does not hold up the rest.
                updatePlex { it.copy(libraryChoices = found.toList()) }
            }
        }
    }

    /**
     * Opens a library wherever it lives, moving to its server first when that is not the
     * one in use. Picking a library is the whole gesture; which server it happens to be
     * on is an implementation detail of somebody's setup.
     */
    fun openLibrary(kind: LibraryKind, choice: LibraryChoice) {
        if (choice.baseUrl == _state.value.plex.baseUrl) {
            openSection(kind, choice.section)
            return
        }
        viewModelScope.launch {
            useServer(choice.serverName, choice.baseUrl, choice.token)
            openSection(kind, choice.section)
        }
    }

    /** Moves everything over to one server and reloads what belongs to it. */
    private suspend fun useServer(name: String, base: String, token: String) {
        store.put(SecureStore.PLEX_SERVER_URI, base)
        store.put(SecureStore.PLEX_SERVER_TOKEN, token)
        store.put(SecureStore.PLEX_SERVER_NAME, name)
        _state.update {
            it.copy(
                home = HomeState(),
                detail = null,
                focused = null,
                plex = it.plex.copy(
                    baseUrl = base,
                    serverToken = token,
                    serverName = name,
                    sections = emptyList(),
                    browse = LibraryKind.entries.associateWith { _ -> BrowseState() },
                    busy = false,
                ),
            )
        }
        loadSections()
    }

    /**
     * Moves to another of the account's servers. Everything read from the old one goes:
     * its libraries, its rows and whatever was on screen all belong to it.
     */
    fun switchServer(server: PlexServer) {
        viewModelScope.launch {
            updatePlex { it.copy(busy = true, error = null) }
            val base = PlexApi.firstReachable(server)
            if (base == null) {
                updatePlex {
                    it.copy(busy = false, error = "${server.name} did not answer. Is it awake?")
                }
                return@launch
            }
            useServer(server.name, base, server.accessToken)
        }
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
        updatePlex { it.copy(servers = servers) }
        scanLibraries()
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
    fun plexBackdropUrl(serverBase: String?, path: String?): String? =
        plexImageUrl(serverBase, path, width = 720, height = 405)

    fun toggleWatchedDetail() {
        val detail = _state.value.detail?.detail ?: return
        toggleWatched(detail.asItem())
    }

    /**
     * Artwork has to be asked of the server that holds it, with that server's token. A row
     * merged from several servers would otherwise draw everything against whichever one
     * happens to be connected, and the rest would come back unauthorised.
     */
    fun plexImageUrl(serverBase: String?, path: String?, width: Int, height: Int): String? {
        val plex = _state.value.plex
        val base = serverBase ?: plex.baseUrl ?: return null
        val token = plex.tokenFor(serverBase) ?: return null
        return PlexApi.imageUrl(base, token, path, width, height)
    }

    // ---------------------------------------------------------------- Home

    /**
     * Home belongs to the account, not to whichever server happens to be connected. Every
     * server holding a pinned library is asked, and the answers are merged — which is what
     * a Plex client shows and what makes two servers feel like one library.
     *
     * Browsing a library is still that library's server's business. It is only the rows
     * that span them.
     */
    fun refreshHome() {
        val plex = _state.value.plex
        val sources = plex.homeSources().ifEmpty {
            val base = plex.baseUrl ?: return
            val token = plex.serverToken ?: return
            plex.sections.map { LibraryChoice(plex.serverName.orEmpty(), base, token, it) }
        }
        if (sources.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(home = it.home.copy(busy = true, error = null)) }

            val servers = sources.map { it.baseUrl to it.token }.distinct()

            // On Deck is rendered as each server composes it. The servers share no notion
            // of recency, so the merge falls back to the only thing they agree on: how
            // far through something is and when it was added.
            val onDeck = servers.flatMap { (base, token) ->
                runCatching { PlexApi.onDeck(base, token) }.getOrElse { emptyList() }
            }.sortedByDescending { it.addedAt }

            val movieSources = sources.filter { it.section.type == LibraryKind.MOVIES.plexType }
            val showSources = sources.filter { it.section.type == LibraryKind.SHOWS.plexType }

            val recentMovies = movieSources.flatMap { source ->
                runCatching {
                    PlexApi.recentlyAdded(
                        source.baseUrl,
                        source.token,
                        source.section.key,
                        PlexApi.TYPE_MOVIE,
                        limit = 40,
                    )
                }.getOrElse { emptyList() }
            }.sortedByDescending { it.addedAt }.take(40)

            val recentEpisodes = recentEpisodeGroups(showSources)

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
    private suspend fun recentEpisodeGroups(sources: List<LibraryChoice>): List<EpisodeGroup> {
        val groups = LinkedHashMap<String, EpisodeGroup>()

        for (source in sources) {
            val base = source.baseUrl
            val token = source.token
            val section = source.section
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
                    // A rating key is only unique within a server.
                    val key = base + "|" + (episode.grandparentRatingKey ?: episode.ratingKey)
                    val existing = groups[key]
                    if (existing == null) {
                        groups[key] = EpisodeGroup(
                            showTitle = episode.grandparentTitle ?: episode.title,
                            showRatingKey = episode.grandparentRatingKey ?: episode.ratingKey,
                            thumb = episode.grandparentThumb ?: episode.thumb,
                            newest = episode,
                            count = 1,
                            addedAt = episode.addedAt,
                            librarySectionId = episode.librarySectionId ?: section.key,
                            serverBase = episode.serverBase,
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
        if (_state.value.plex.browseFor(kind).section?.key == section.key) return
        // A different library is a clean slate: the old library's genres do not apply.
        updateBrowse(kind) {
            BrowseState(section = section, sort = it.sort, busy = true)
        }
        loadBrowse(kind)
        loadGenres(kind, section)
        loadReleased(kind, section)
    }

    /**
     * Newest by release date. Plex's own Recently Added answers a different question —
     * when a file arrived, which for a back catalogue import is today for a film from
     * 1974 — so this is asked for separately rather than reordered from that.
     */
    private fun loadReleased(kind: LibraryKind, section: PlexSection) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        viewModelScope.launch {
            val path = "/library/sections/${section.key}/all" +
                "?type=${kind.filter}&sort=originallyAvailableAt:desc"
            val items = runCatching { PlexApi.items(base, token, path, limit = 40) }
                .getOrElse { emptyList() }
            updateBrowse(kind) {
                if (it.section?.key != section.key) it else it.copy(released = items)
            }
        }
    }

    /** Which libraries the tab menu offers. */
    fun toggleFavouriteLibrary(choice: LibraryChoice) {
        val next = settings.favouriteSections.toMutableSet()
        if (!next.remove(choice.id)) next.add(choice.id)
        settings.favouriteSections = next
        updatePlex { it.copy(favouriteSections = next) }
    }

    /** Cycles the grid through the orderings a library client actually offers. */
    fun cycleSort(kind: LibraryKind) {
        val choices = LibrarySort.entries
        updateBrowse(kind) {
            it.copy(sort = choices[(choices.indexOf(it.sort) + 1) % choices.size])
        }
        loadBrowse(kind)
    }

    fun toggleUnwatchedOnly(kind: LibraryKind) {
        updateBrowse(kind) { it.copy(unwatchedOnly = !it.unwatchedOnly) }
        loadBrowse(kind)
    }

    /** Null is "all genres"; picking the genre already on also clears it. */
    fun selectGenre(kind: LibraryKind, genreId: String?) {
        updateBrowse(kind) { it.copy(genreId = if (it.genreId == genreId) null else genreId) }
        loadBrowse(kind)
    }

    /**
     * Fetches the grid for whatever sort and filters are currently set. The server does
     * the work: sorting and filtering here would only ever order the page in hand.
     */
    private fun loadBrowse(kind: LibraryKind) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val browse = plex.browseFor(kind)
        val section = browse.section ?: return

        browseJobs[kind]?.cancel()
        browseJobs[kind] = viewModelScope.launch {
            updateBrowse(kind) { it.copy(busy = true, error = null) }
            val path = buildString {
                append("/library/sections/${section.key}/all?type=${kind.filter}")
                append("&sort=${browse.sort.key}")
                browse.genreId?.let { append("&genre=$it") }
                if (browse.unwatchedOnly) append("&unwatched=1")
            }
            val items = runCatching { PlexApi.items(base, token, path, limit = 400) }
                .getOrElse { failure ->
                    updateBrowse(kind) { it.copy(busy = false, error = failure.readable()) }
                    return@launch
                }
            updateBrowse(kind) { it.copy(busy = false, items = items) }
        }
    }

    private fun loadGenres(kind: LibraryKind, section: PlexSection) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        viewModelScope.launch {
            val genres = runCatching { PlexApi.genres(base, token, section.key, kind.filter) }
                .getOrElse { emptyList() }
            updateBrowse(kind) {
                if (it.section?.key != section.key) it else it.copy(genres = genres)
            }
        }
    }

    fun dismissBrowseError(kind: LibraryKind) = updateBrowse(kind) { it.copy(error = null) }

    // ---------------------------------------------------------------- Detail

    private fun loadDetail(route: Route.Detail) {
        val ratingKey = route.ratingKey
        val plex = _state.value.plex
        val base = route.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(route.serverBase) ?: return

        _state.update {
            it.copy(
                detail = DetailState(
                    ratingKey = ratingKey,
                    serverBase = route.serverBase,
                    busy = true,
                )
            )
        }

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
            startTheme()

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

            // Arriving from a row means arriving at one episode, not at the top of the show.
            val season = seasons.firstOrNull { it.ratingKey == route.seasonKey }
                ?: seasons.firstOrNull()
            if (season == null) {
                _state.update { it.copy(detail = it.detail?.copy(busy = false)) }
            } else {
                selectSeason(season, focusEpisodeKey = route.episodeKey)
            }
        }
    }

    fun selectSeason(season: PlexItem, focusEpisodeKey: String? = null) {
        val plex = _state.value.plex
        val on = season.serverBase ?: _state.value.detail?.serverBase
        val base = on ?: plex.baseUrl ?: return
        val token = plex.tokenFor(on) ?: return
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
            val landOn = focusEpisodeKey?.let { key -> episodes.firstOrNull { it.ratingKey == key } }
            _state.update { current ->
                current.copy(
                    detail = current.detail?.copy(
                        episodes = episodes,
                        focusedEpisode = landOn,
                        busy = false,
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- Playback

    /**
     * The Play button on a detail page. For a show that means the episode you are part-way
     * through, or the first one; for a film it means the film. With [resume] off it is the
     * same choice of thing to watch, started again from zero.
     */
    fun playFromDetail(resume: Boolean = true) {
        val detailState = _state.value.detail ?: return
        val detail = detailState.detail ?: return
        if (detail.isShow) {
            val episode = detailState.episodes.firstOrNull { it.resumeFraction != null }
                ?: detailState.episodes.firstOrNull()
                ?: return
            play(episode, queue = detailState.episodes, resume = resume)
        } else {
            play(detail.asItem(), resume = resume)
        }
    }

    /** Play a library item, resuming where Plex says it was left. */
    fun play(item: PlexItem, queue: List<PlexItem> = emptyList(), resume: Boolean = true) {
        val plex = _state.value.plex
        val on = item.serverBase
        val base = on ?: plex.baseUrl ?: return
        val token = plex.tokenFor(on) ?: return

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
            // Anything from the library is a single picture; the live grid does not survive
            // it, and neither does the connection live television was holding.
            livePlayer.stop()
            silenceTheme()
            _state.update { it.copy(multiview = emptyList()) }
            val startAt = if (resume) item.viewOffsetMs else 0
            val transcode = _state.value.prefs.playbackMode == Settings.MODE_TRANSCODE
            val session = UUID.randomUUID().toString()

            releaseTranscode()
            _state.update {
                it.copy(
                    upNext = null,
                    playback = Playback(
                        title = item.title,
                        subtitle = subtitleLineFor(item),
                        url = if (transcode) {
                            PlexApi.transcodeUrl(
                                base = base,
                                token = token,
                                clientId = clientId,
                                ratingKey = item.ratingKey,
                                sessionId = session,
                                offsetMs = startAt,
                                maxBitrateKbps = it.prefs.maxBitrateKbps,
                                resolution = RESOLUTION,
                            )
                        } else {
                            resolved.url
                        },
                        isLive = false,
                        ratingKey = item.ratingKey,
                        // A transcode already starts at the offset, so the player must not
                        // seek there as well.
                        startPositionMs = if (transcode) 0 else startAt,
                        durationMs = item.durationMs,
                        subtitles = if (transcode) emptyList() else resolved.subtitles,
                        markers = resolved.markers,
                        serverBase = on,
                        queue = effectiveQueue,
                        queueIndex = effectiveQueue.indexOfFirst { entry -> entry.ratingKey == item.ratingKey },
                        transcoding = transcode,
                        transcodeSession = if (transcode) session else null,
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
        val base = item.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(item.serverBase) ?: return
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
        val playback = _state.value.playback ?: return null
        val base = playback.serverBase ?: plex.baseUrl ?: return null
        val token = plex.tokenFor(playback.serverBase) ?: return null
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
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
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

    /**
     * The device could not decode the file. Ask the server to do the work instead and
     * carry on from where it stopped — the point of having a transcoder at all.
     */
    fun retryWithTranscode(positionMs: Long) {
        val playback = _state.value.playback ?: return
        if (playback.transcoding || playback.isLive) return
        if (_state.value.prefs.playbackMode == Settings.MODE_DIRECT) return
        val ratingKey = playback.ratingKey ?: return
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return

        val session = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                playback = playback.copy(
                    url = PlexApi.transcodeUrl(
                        base = base,
                        token = token,
                        clientId = clientId,
                        ratingKey = ratingKey,
                        sessionId = session,
                        offsetMs = positionMs,
                        maxBitrateKbps = it.prefs.maxBitrateKbps,
                        resolution = RESOLUTION,
                    ),
                    startPositionMs = 0,
                    subtitles = emptyList(),
                    transcoding = true,
                    transcodeSession = session,
                )
            )
        }
    }

    /** Hands the server's encoder back. A session left running keeps encoding. */
    private fun releaseTranscode() {
        val playback = _state.value.playback ?: return
        val session = playback.transcodeSession ?: return
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
        viewModelScope.launch { PlexApi.stopTranscode(base, token, session) }
    }

    fun setPlaybackMode(mode: String) {
        settings.playbackMode = mode
        _state.update { it.copy(prefs = it.prefs.copy(playbackMode = mode)) }
    }

    /** Direct → Auto → Always transcode, and round again. */
    fun cyclePlaybackMode() {
        val choices = listOf(Settings.MODE_DIRECT, Settings.MODE_AUTO, Settings.MODE_TRANSCODE)
        val next = choices[(choices.indexOf(settings.playbackMode).coerceAtLeast(0) + 1) % choices.size]
        setPlaybackMode(next)
    }

    fun cycleMaxBitrate() {
        val choices = Settings.BITRATE_CHOICES
        val next = choices[(choices.indexOf(settings.maxBitrateKbps).coerceAtLeast(0) + 1) % choices.size]
        settings.maxBitrateKbps = next
        _state.update { it.copy(prefs = it.prefs.copy(maxBitrateKbps = next)) }
    }

    fun stopPlayback(positionMs: Long = 0) {
        releaseTranscode()
        if (_state.value.playback?.isLive == true) livePlayer.stop()
        _state.update { it.copy(multiview = emptyList()) }
        val playback = _state.value.playback
        val ratingKey = playback?.ratingKey
        val plex = _state.value.plex
        val base = playback?.serverBase ?: plex.baseUrl
        val token = plex.tokenFor(playback?.serverBase)
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
            _state.update {
                it.copy(search = it.search.copy(results = emptyList(), channels = emptyList(), busy = false))
            }
            return
        }
        primeChannelIndex()
        val plex = _state.value.plex
        val servers = plex.homeSources().map { it.baseUrl to it.token }.distinct()
            .ifEmpty {
                val base = plex.baseUrl
                val token = plex.serverToken
                if (base != null && token != null) listOf(base to token) else emptyList()
            }

        searchJob = viewModelScope.launch {
            _state.update { it.copy(search = it.search.copy(busy = true)) }
            // Typing on a remote is slow; wait for a pause rather than asking per letter.
            delay(400)
            // Every pinned server, merged. Searching one of two libraries and calling it
            // "your library" is the thing this whole change is about.
            val results = servers.flatMap { (base, token) ->
                runCatching { PlexApi.search(base, token, query) }.getOrElse { emptyList() }
            }
            // Channels are matched here rather than asked of the panel: the panel has no
            // search, and the whole list is already in hand.
            val channels = allChannels.orEmpty()
                .filter { it.name.contains(query.trim(), ignoreCase = true) }
                .take(CHANNEL_RESULTS)
            _state.update { current ->
                if (current.search.query != query) current
                else current.copy(
                    search = current.search.copy(results = results, channels = channels, busy = false)
                )
            }
        }
    }

    /** Pulls the whole channel list once, so search has something to match against. */
    private fun primeChannelIndex() {
        if (allChannels != null || channelsJob?.isActive == true) return
        val credentials = _state.value.live.credentials ?: return
        channelsJob = viewModelScope.launch {
            allChannels = runCatching { XtreamApi.liveChannels(credentials) }.getOrElse { emptyList() }
            // The list usually lands after the debounce, so re-run the match it missed.
            val query = _state.value.search.query
            if (query.isNotBlank()) {
                val channels = allChannels.orEmpty()
                    .filter { it.name.contains(query.trim(), ignoreCase = true) }
                    .take(CHANNEL_RESULTS)
                _state.update { current ->
                    if (current.search.query != query) current
                    else current.copy(search = current.search.copy(channels = channels))
                }
            }
        }
    }

    /**
     * Plays a channel found by search. It may not be in the category currently open, in
     * which case there is nothing to surf through and the skip buttons stay quiet.
     */
    fun playSearchChannel(channel: XtreamChannel) {
        val live = _state.value.live
        val credentials = live.credentials ?: return
        val index = live.channels.indexOfFirst { it.streamId == channel.streamId }
        _state.update {
            it.copy(
                upNext = null,
                playback = Playback(
                    title = channel.name,
                    subtitle = null,
                    url = XtreamApi.streamUrl(credentials, channel, live.format),
                    isLive = true,
                    channelIndex = index,
                    format = live.format,
                ),
            )
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
        val base = item.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(item.serverBase) ?: return
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

    /**
     * Plays the item's trailer, when the server has one to give.
     *
     * Always through the transcoder, whatever the playback mode says. An extra's part key
     * is not something this app can fetch directly — an online trailer's is indirect and
     * the server answers a direct request for it with a 401 — so the server is asked to
     * resolve and serve it instead. A trailer is two minutes; the encoding costs nothing.
     *
     * No rating key goes on the playback, so watching a trailer is never reported to Plex
     * as watching the film.
     */
    fun playTrailer() {
        val plex = _state.value.plex
        val detail = _state.value.detail ?: return
        val base = detail.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(detail.serverBase) ?: return
        val trailer = detail.trailers.firstOrNull() ?: return
        val title = detail.detail?.title ?: "Trailer"
        val session = UUID.randomUUID().toString()

        silenceTheme()
        releaseTranscode()
        _state.update {
            it.copy(
                upNext = null,
                playback = Playback(
                    title = title,
                    subtitle = trailer.title,
                    url = PlexApi.transcodeUrl(
                        base = base,
                        token = token,
                        clientId = clientId,
                        ratingKey = trailer.ratingKey,
                        sessionId = session,
                        offsetMs = 0,
                        maxBitrateKbps = it.prefs.maxBitrateKbps,
                        resolution = RESOLUTION,
                    ),
                    isLive = false,
                    durationMs = trailer.durationMs,
                    serverBase = detail.serverBase,
                    transcoding = true,
                    transcodeSession = session,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- Live TV

    private suspend fun restoreLive() {
        updateLive {
            it.copy(
                format = if (settings.streamFormat == Settings.FORMAT_HLS) StreamFormat.HLS
                else StreamFormat.TS
            )
        }
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
        allChannels = null
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

    /**
     * Asks the panel for its category and channel list again. Providers add and drop
     * channels without warning, and nothing else in the app ever refetches them.
     */
    fun refreshLiveChannels() {
        val credentials = _state.value.live.credentials ?: return
        channelsJob?.cancel()
        allChannels = null
        viewModelScope.launch {
            updateLive { it.copy(busy = true, error = null) }
            val categories = runCatching { XtreamApi.liveCategories(credentials) }
                .getOrElse { failure ->
                    updateLive { it.copy(busy = false, error = failure.readable()) }
                    return@launch
                }
            updateLive { it.copy(busy = false, categories = categories, guide = emptyMap()) }
            _state.value.live.selectedCategory?.let { openCategory(it) }
        }
    }

    /** Backing out of the grid returns to the category picker. */
    fun clearCategory() {
        guideJob?.cancel()
        livePlayer.stop()
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
        silenceTheme()
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

    /**
     * Adds a channel beside the one playing. Four tiles is the ceiling — not because the
     * hardware was asked what it can manage, but because a 2x2 grid is where the screen
     * runs out. Whether a stick can decode four streams at once, and whether the provider
     * will serve four connections, is left to find out.
     */
    fun addToMultiview(channel: XtreamChannel) {
        val playback = _state.value.playback ?: return
        if (!playback.isLive) return
        _state.update { current ->
            val already = current.multiview.any { it.streamId == channel.streamId }
            val playing = current.live.channels
                .getOrNull(playback.channelIndex)?.streamId == channel.streamId
            if (already || playing || current.multiview.size >= MAX_EXTRA_TILES) current
            else current.copy(multiview = current.multiview + channel)
        }
    }

    fun removeFromMultiview(index: Int) {
        _state.update { current ->
            if (index !in current.multiview.indices) current
            else current.copy(
                multiview = current.multiview.toMutableList().apply { removeAt(index) }
            )
        }
    }

    fun clearMultiview() = _state.update { it.copy(multiview = emptyList()) }

    /** Going full screen on one tile: everything else goes away. */
    fun collapseToChannel(channel: XtreamChannel) {
        val index = _state.value.live.channels.indexOfFirst { it.streamId == channel.streamId }
        _state.update { it.copy(multiview = emptyList()) }
        if (index >= 0) playChannel(index)
    }

    /** Channel surfing from the player — the thing that decides whether this feels like a TV app. */
    fun stepChannel(delta: Int) {
        val playback = _state.value.playback ?: return
        if (!playback.isLive) return
        val channels = _state.value.live.channels
        if (channels.isEmpty() || playback.channelIndex < 0) return
        playChannel(wrapIndex(playback.channelIndex + delta, channels.size))
    }

    /** TS and HLS behave differently on recovery; worth being able to switch on the spot. */
    fun toggleFormat() {
        val next = if (_state.value.live.format == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS
        settings.streamFormat = next.extension
        livePlayer.stop()
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
        livePlayer.stop()
        store.remove(SecureStore.XTREAM_HOST, SecureStore.XTREAM_USERNAME, SecureStore.XTREAM_PASSWORD)
        channelsJob?.cancel()
        allChannels = null
        _state.update { it.copy(live = LiveState(), search = it.search.copy(channels = emptyList())) }
    }

    // ---------------------------------------------------------------- Updates

    val updateUrl: String get() = settings.updateUrl

    /**
     * Asks what is published and whether it is newer than what is running.
     *
     * The version comparison is the whole point: downloading the build already installed
     * is worse than useless. That needs the server to say what it is holding, which is
     * what the manifest beside the APK is for. Without one nothing here will claim an
     * update exists — it reports what it found and leaves the decision alone.
     */
    fun checkForUpdate() {
        if (_state.value.update is UpdateStatus.Checking) return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _state.update { it.copy(update = UpdateStatus.Checking) }
            val info = runCatching { Updater.check(settings.updateUrl) }.getOrElse { failure ->
                _state.update { it.copy(update = UpdateStatus.Failed(failure.readable())) }
                return@launch
            }
            _state.update {
                it.copy(
                    update = when {
                        !info.describesItself -> UpdateStatus.Unlabelled(info)
                        info.isNewerThan(BuildConfig.VERSION_CODE) -> UpdateStatus.Available(info)
                        else -> UpdateStatus.UpToDate
                    }
                )
            }
        }
    }

    /** Fetches the published build and hands it to the system installer. */
    fun installUpdate() {
        val info = when (val status = _state.value.update) {
            is UpdateStatus.Available -> status.info
            is UpdateStatus.Unlabelled -> status.info
            else -> return
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _state.update { it.copy(update = UpdateStatus.Downloading(0, info.sizeBytes)) }
            val file = runCatching {
                Updater.download(getApplication(), info.url) { read, total ->
                    _state.update { it.copy(update = UpdateStatus.Downloading(read, total)) }
                }
            }.getOrElse { failure ->
                _state.update { it.copy(update = UpdateStatus.Failed(failure.readable())) }
                return@launch
            }
            runCatching { Updater.install(getApplication(), file) }.onFailure { failure ->
                _state.update { it.copy(update = UpdateStatus.Failed(failure.readable())) }
                return@launch
            }
            _state.update { it.copy(update = UpdateStatus.Handed) }
        }
    }

    // ---------------------------------------------------------------- Theme music

    fun toggleThemeMusic() {
        val next = !settings.themeMusic
        settings.themeMusic = next
        _state.update { it.copy(prefs = it.prefs.copy(themeMusic = next)) }
        if (!next) themePlayer.silence() else startTheme()
    }

    fun nudgeThemeVolume(delta: Float) {
        val next = (settings.themeVolume + delta).coerceIn(Settings.MIN_THEME_VOLUME, 1f)
        settings.themeVolume = next
        _state.update { it.copy(prefs = it.prefs.copy(themeVolume = next)) }
    }

    /**
     * Starts the theme for whatever page is open, after a pause. The pause is there so
     * that opening a show and immediately pressing play does not fire a title tune at
     * somebody on their way into an episode.
     */
    private fun startTheme() {
        themeJob?.cancel()
        if (!settings.themeMusic) return
        val detail = _state.value.detail ?: return
        val theme = detail.detail?.theme ?: return
        val plex = _state.value.plex
        val base = detail.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(detail.serverBase) ?: return
        themeJob = viewModelScope.launch {
            delay(THEME_DELAY_MS)
            themePlayer.play("$base$theme?X-Plex-Token=$token", settings.themeVolume)
        }
    }

    /** Leaving a page. Fades, because stopping a tune mid-bar sounds like a fault. */
    private fun stopTheme() {
        themeJob?.cancel()
        themePlayer.fadeOut()
    }

    /** The app went away, or something is about to play. No fade. */
    fun silenceTheme() {
        themeJob?.cancel()
        themePlayer.silence()
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
        themePlayer.release()
        livePlayer.release()
        epgStore.close()
    }

    private companion object {
        /** How much of the guide is held in memory at once. */
        const val WINDOW_SECONDS = 24L * 3_600

        /** Guide data older than this is worth fetching again. */
        const val REFRESH_AFTER_SECONDS = 6L * 3_600

        /** What a transcode is asked to produce. A stick has no use for more. */
        const val RESOLUTION = "1920x1080"

        /** How many shows the Recently Added row aims to carry. */
        const val TARGET_SHOW_COUNT = 30
        const val EPISODE_PAGE = 200

        /** A ceiling, so a library of nothing but one huge series still finishes. */
        const val MAX_EPISODE_SCAN = 2_000

        /** A search for "sports" on a big panel matches thousands; a screenful is plenty. */
        const val CHANNEL_RESULTS = 40

        /** Long enough that opening a show and pressing play never starts a tune. */
        const val THEME_DELAY_MS = 900L

        /** Three beside the one playing, which fills a 2x2 grid. */
        const val MAX_EXTRA_TILES = 3
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
    librarySectionId = null,
)

private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: (this::class.java.simpleName + " while talking to the server")
