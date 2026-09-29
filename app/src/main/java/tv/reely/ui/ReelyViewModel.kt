package tv.reely.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.reely.core.AudioPlan
import tv.reely.core.DeviceAudio
import tv.reely.core.audioPlan
import tv.reely.core.continueWatchingOrder
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
import tv.reely.plex.PlexLetter
import tv.reely.plex.PlexExtra
import tv.reely.plex.PlexHomeUser
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
enum class LibraryView { HOME, GRID, COLLECTIONS }

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

    /** A playlist: what is in it, to play from the top or shuffled. */
    data class Playlist(
        val ratingKey: String,
        val title: String,
        val serverBase: String? = null,
    ) : Route

    /** Someone from a cast: what else they are in, across the server's libraries. */
    data class Person(
        val id: String,
        val name: String,
        val thumb: String? = null,
        val serverBase: String? = null,
    ) : Route
}

/** A playlist's page. */
data class PlaylistState(
    val route: Route.Playlist,
    val items: List<PlexItem> = emptyList(),
    val busy: Boolean = true,
    val error: String? = null,
)

/** A person's page: who they are and what they appear in. */
data class PersonState(
    val route: Route.Person,
    val items: List<PlexItem> = emptyList(),
    val busy: Boolean = true,
    val error: String? = null,
)

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
    /** What's on the account's Watchlist that a server here actually has. */
    val watchlist: List<PlexItem> = emptyList(),
    /** The servers' video playlists. */
    val playlists: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = continueWatching.isEmpty() && recentEpisodes.isEmpty() && recentMovies.isEmpty() &&
            watchlist.isEmpty() && playlists.isEmpty()
}

/**
 * How a library grid is ordered. The value is Plex's own sort key, so the server does the
 * ordering over the whole library rather than this app re-sorting one page of it.
 */
enum class LibrarySort(val key: String, val label: String) {
    TITLE("titleSort:asc", "A–Z"),
    ADDED("addedAt:desc", "Recently added"),
    RELEASED("originallyAvailableAt:desc", "Newest releases"),
    OLDEST("originallyAvailableAt:asc", "Oldest releases"),
    RATED("rating:desc", "Critic rating"),
    AUDIENCE("audienceRating:desc", "Audience rating"),
    WATCHED("lastViewedAt:desc", "Recently watched"),
}

/** Where the grid has been asked to move the cursor to: a letter's first title. */
data class GridJump(val index: Int, val serial: Int)

/** One library grid. No drill-down: opening something goes to its own detail route. */
data class BrowseState(
    val section: PlexSection? = null,
    val items: List<PlexItem> = emptyList(),
    /** Newest by release date, which is not the same as newest to the library. */
    val released: List<PlexItem> = emptyList(),
    val genres: List<PlexGenre> = emptyList(),
    val sort: LibrarySort = LibrarySort.TITLE,
    val genreId: String? = null,
    val decades: List<PlexGenre> = emptyList(),
    /** A decade's start year, "1990", as Plex filters by it. */
    val decade: String? = null,
    val unwatchedOnly: Boolean = false,
    /** Where each letter starts, for the A–Z rail. Only ever for title order. */
    val letters: List<PlexLetter> = emptyList(),
    val jump: GridJump? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** The library's collections, once they have been asked for. */
    val collections: List<PlexItem>? = null,
    /** Every page of the grid is in: there is nothing more to ask the server for. */
    val complete: Boolean = false,
    /** A further page is on its way. */
    val loadingMore: Boolean = false,
) {
    /** True when the grid is showing less than the whole library. */
    val isFiltered: Boolean get() = genreId != null || decade != null || unwatchedOnly

    /** Where [letter]'s titles begin in the grid, counting everything before it. */
    fun letterStart(letter: String): Int? {
        val at = letters.indexOfFirst { it.letter == letter }
        if (at < 0) return null
        return letters.take(at).sumOf { it.count }
    }
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
    /** "More like this", as the server suggests it. */
    val related: List<PlexItem> = emptyList(),
    /** For a collection's page: what is in it. */
    val members: List<PlexItem> = emptyList(),
    /** Which of the title's files Play uses, when it has several. See [PlexDetail.versions]. */
    val versionIndex: Int = 0,
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
    /** The account's Watchlist, by Plex's own ids; null until it has been asked for. */
    val watchlist: Set<String>? = null,
    /** Every server the account can see, so one of several can be chosen. */
    val servers: List<PlexServer> = emptyList(),
    /** Every library on every server the account can reach. */
    val libraryChoices: List<LibraryChoice> = emptyList(),
    val browse: Map<LibraryKind, BrowseState> = LibraryKind.entries.associateWith { BrowseState() },
    val linkCode: String? = null,
    /** The same sign-in as a web address, shown as a QR code beside [linkCode]. */
    val linkUrl: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val favouriteSections: Set<String> = emptySet(),
    /** Whose profile this is: the account, or whoever in its Plex Home was switched to. */
    val user: PlexHomeUser? = null,
    /** Everybody in the account's Plex Home; empty for an account not in one. */
    val homeUsers: List<PlexHomeUser> = emptyList(),
    /** Switching profiles: the one being switched to while it happens. */
    val switchingTo: PlexHomeUser? = null,
    val switchError: String? = null,
) {
    val isConnected: Boolean get() = baseUrl != null && serverToken != null

    /** Whether there is anybody else to switch to. */
    val canSwitchUser: Boolean get() = homeUsers.size > 1

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
    val format: StreamFormat = StreamFormat.HLS,
    val busy: Boolean = false,
    val error: String? = null,
    /** Channels marked as favorites, by stream id. */
    val favorites: Set<Int> = emptySet(),
) {
    val isConnected: Boolean get() = credentials != null && account != null

    fun nowNext(streamId: Int): List<XtreamProgramme> = guide[streamId].orEmpty()

    /** The provider's categories, with Favorites first once there are any. */
    val shownCategories: List<XtreamCategory>
        get() = if (favorites.isEmpty()) categories else listOf(FAVORITES) + categories

    companion object {
        /** Not one of the provider's: the channels marked as favorites, from all of them. */
        val FAVORITES = XtreamCategory(id = "reely:favorites", name = "Favorites")
    }
}

data class SearchState(
    val query: String = "",
    val results: List<PlexItem> = emptyList(),
    /** Live channels whose name matches. Empty when no provider is configured. */
    val channels: List<XtreamChannel> = emptyList(),
    /** What else the server offered that doesn't have the words in its name. */
    val more: List<PlexItem> = emptyList(),
    /** Actors whose name matches, whose page shows what else they're in. */
    val people: List<tv.reely.plex.PlexPerson> = emptyList(),
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
    /** The server's scrubbing pictures, `{ms}` for the moment; see [PlexPlayback.previewUrl]. */
    val previewUrl: String? = null,
    /** True when the server is encoding this rather than handing over the file. */
    val transcoding: Boolean = false,
    /**
     * True when only the sound is being converted, because this device could not play
     * it; the picture is the file's own. Implies [transcoding].
     */
    val audioConverted: Boolean = false,
    /** The file's own sound, by Plex's name for it, and its channels, when known. */
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val transcodeSession: String? = null,
    /** Which of the title's files is playing, for a server fallback to ask for the same one. */
    val mediaIndex: Int = 0,
    val chapters: List<tv.reely.plex.PlexChapter> = emptyList(),
)

data class PlayerPrefs(
    val subtitleScale: Float = Settings.DEFAULT_SCALE,
    val subtitleBackground: Boolean = false,
    val upNextSeconds: Int = Settings.DEFAULT_UP_NEXT,
    val guidePreview: Boolean = true,
    val playbackMode: String = Settings.MODE_AUTO,
    val maxBitrateKbps: Int = 0,
    val multiviewLayout: String = Settings.LAYOUT_GRID,
    val themeMusic: Boolean = false,
    val themeVolume: Float = Settings.DEFAULT_THEME_VOLUME,
    val matchFrameRate: Boolean = true,
    val largerBuffer: Boolean = false,
)

/** How often a browsing screen left up asks for what has changed. */
/** How long after starting the app it looks for a newer version. */
private const val UPDATE_CHECK_DELAY_MS = 4_000L

/** How many titles the library grid asks for at a time. */
private const val GRID_PAGE = 300

internal const val BROWSE_REFRESH_MS = 5L * 60_000

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
    val person: PersonState? = null,
    val playlist: PlaylistState? = null,
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
    /** A newer version turned up at startup: ask whether to install it. */
    val updatePrompt: Boolean = false,
) {
    val route: Route get() = stack.last()
}

class ReelyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SecureStore(application)
    private val settings = Settings(application)
    private val epgStore = EpgStore(application)
    private val deviceAudio = DeviceAudio(application)

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
                multiviewLayout = settings.multiviewLayout,
                themeMusic = settings.themeMusic,
                themeVolume = settings.themeVolume,
                matchFrameRate = settings.matchFrameRate,
                largerBuffer = settings.largerBuffer,
            )
        )
    )
    val state: StateFlow<ReelyState> = _state.asStateFlow()

    private val clientId: String = store.get(SecureStore.PLEX_CLIENT_ID)
        ?: UUID.randomUUID().toString().also { store.put(SecureStore.PLEX_CLIENT_ID, it) }

    private var linkJob: Job? = null
    private var guideJob: Job? = null
    private var timelineJob: Job? = null

    /**
     * One identifier for one sitting. Plex groups consecutive timeline reports by this,
     * so it has to survive the whole of an item and change when the item does.
     */
    private var timelineSession = UUID.randomUUID().toString()
    private var timelineSessionFor: String? = null
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
        // The name the television was given in its own settings, as Plex's apps use; the
        // model when there is none, which is still more use than the app's name.
        PlexApi.deviceName = runCatching {
            android.provider.Settings.Global.getString(application.contentResolver, "device_name")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Reely TV"
        updatePlex { it.copy(favouriteSections = settings.favouriteSections) }
        updateLive { it.copy(favorites = settings.favoriteChannels) }
        viewModelScope.launch {
            restorePlex()
            restoreLive()
            _state.update { it.copy(restoring = false) }
        }
        checkForUpdateAtStart()
    }

    /**
     * A quiet look for a newer version, a few seconds in so it doesn't hold up the first
     * screen. Found, it asks; not found or not reachable, it says nothing — Settings,
     * Updates is where a failed check is worth reporting.
     */
    private fun checkForUpdateAtStart() {
        viewModelScope.launch {
            delay(UPDATE_CHECK_DELAY_MS)
            if (_state.value.update !is UpdateStatus.Idle) return@launch
            val info = runCatching { Updater.check(settings.updateUrl) }.getOrNull() ?: return@launch
            if (!info.describesItself || !info.isNewerThan(BuildConfig.VERSION_CODE)) return@launch
            _state.update {
                if (it.update !is UpdateStatus.Idle) it
                else it.copy(update = UpdateStatus.Available(info), updatePrompt = true)
            }
        }
    }

    /** The build last offered, so a failed download can be tried again. */
    private var offeredUpdate: tv.reely.core.UpdateInfo? = null

    /** Later: not asked again until the app is next started. */
    fun dismissUpdatePrompt() = _state.update { it.copy(updatePrompt = false) }

    // ---------------------------------------------------------------- Navigation

    fun navigate(route: Route) {
        _state.update { current ->
            // Switching top-level destination replaces the stack rather than growing it.
            // Settings is not one of those: it is somewhere you step into from wherever
            // you were and expect to come back from, so it grows the stack like a page.
            val stack = when {
                route is Route.Detail || route is Route.Person || route is Route.Playlist -> current.stack + route
                route !is Route.Settings -> listOf(route)
                current.route is Route.Settings -> current.stack
                else -> current.stack + route
            }
            current.copy(stack = stack, focused = null)
        }
        // The guide's preview is the shared player, and nothing else on screen would
        // account for the sound if it were left running.
        if (route !is Route.Live && _state.value.playback == null) livePlayer.stop()
        if (route !is Route.Detail) stopTheme()
        if (route is Route.Detail) loadDetail(route)
        if (route is Route.Person) loadPerson(route)
        if (route is Route.Playlist) loadPlaylist(route)
        if (route is Route.Home) refreshHome()
        if (route is Route.Library) refreshLibrary(route.kind, force = false)
        if (route is Route.Library && route.view == LibraryView.COLLECTIONS) loadCollections(route.kind)
        if (route is Route.Live) openGuide()
    }

    /*
     * Keeping the libraries current. There is no push from the server, so the app asks
     * again: on arriving at a library, on coming back to the app, and every few minutes
     * while a browsing screen is up. Before, only Home asked — a film added while the
     * app sat on the Movies tab stayed missing until something happened to visit Home.
     */
    private val libraryRefreshedAt = mutableMapOf<LibraryKind, Long>()

    /** What is on screen, asked for again. Called on coming back to the app and on a timer. */
    fun refreshVisible() {
        if (_state.value.playback != null) return
        when (val route = _state.value.route) {
            is Route.Home -> refreshHome()
            is Route.Library -> refreshLibrary(route.kind, force = true)
            else -> Unit
        }
    }

    /**
     * A library's rows and grid, read again. Arriving by moving across the tabs visits
     * each one on the way, so that only asks once a minute; the timer and coming back to
     * the app always ask. The grid is refreshed in place — the items stay on screen while
     * the new list is fetched, and keep their keys, so the cursor stays where it was.
     */
    private fun refreshLibrary(kind: LibraryKind, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - (libraryRefreshedAt[kind] ?: 0L) < LIBRARY_REFRESH_GAP_MS) return
        val section = _state.value.plex.browseFor(kind).section ?: return
        libraryRefreshedAt[kind] = now
        refreshHome()
        loadReleased(kind, section)
        loadBrowse(kind, keep = true)
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
        if (route is Route.Person && _state.value.person?.route != route) loadPerson(route)
        if (route is Route.Playlist && _state.value.playlist?.route != route) loadPlaylist(route)
    }

    private fun loadPlaylist(route: Route.Playlist) {
        val plex = _state.value.plex
        val base = route.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(route.serverBase) ?: return
        _state.update { it.copy(playlist = PlaylistState(route)) }
        viewModelScope.launch {
            val result = runCatching { PlexApi.playlistItems(base, token, route.ratingKey) }
            _state.update { current ->
                if (current.playlist?.route != route) current
                else current.copy(
                    playlist = current.playlist.copy(
                        items = result.getOrElse { emptyList() },
                        busy = false,
                        error = result.exceptionOrNull()?.readable(),
                    ),
                )
            }
        }
    }

    /** The playlist from the top, or in a shuffled order, each going on to the next. */
    fun playPlaylist(shuffle: Boolean) {
        val items = _state.value.playlist?.items?.filter { it.isPlayable }.orEmpty()
        if (items.isEmpty()) return
        val order = if (shuffle) items.shuffled() else items
        play(order.first(), queue = order, resume = !shuffle)
    }

    /**
     * What a person is in: every film and show library on the server that holds the title
     * they were found in, asked at once, films and shows together, newest first.
     */
    private fun loadPerson(route: Route.Person) {
        val plex = _state.value.plex
        val base = route.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(route.serverBase) ?: return
        _state.update { it.copy(person = PersonState(route)) }
        viewModelScope.launch {
            val sections = if (base == plex.baseUrl && plex.sections.isNotEmpty()) plex.sections
            else runCatching { PlexApi.sections(base, token) }.getOrElse { emptyList() }
            val libraries = sections.mapNotNull { section ->
                LibraryKind.entries.firstOrNull { it.plexType == section.type }?.let { section to it }
            }
            val found = libraries.map { (section, kind) ->
                async { runCatching { PlexApi.withActor(base, token, section, kind.filter, route.id) } }
            }.map { it.await() }
            val items = found.flatMap { it.getOrElse { emptyList() } }
                .distinctBy { it.listKey }
                .sortedByDescending { it.airDate ?: it.year?.toString() ?: "" }
            val failed = found.isNotEmpty() && found.all { it.isFailure }
            _state.update { current ->
                if (current.person?.route != route) current
                else current.copy(
                    person = current.person.copy(
                        items = items,
                        busy = false,
                        error = if (failed) found.first().exceptionOrNull()?.readable() else null,
                    ),
                )
            }
        }
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
        loadProfiles(token)
    }

    /** Who is signed in, and who else is in their Plex Home to switch to. */
    private fun loadProfiles(token: String) {
        viewModelScope.launch {
            val user = runCatching { PlexApi.account(clientId, token) }.getOrNull()
            val home = runCatching { PlexApi.homeUsers(clientId, token) }.getOrElse { emptyList() }
            if (_state.value.plex.token != token) return@launch
            updatePlex { it.copy(user = user, homeUsers = home) }
        }
    }

    /**
     * Becomes somebody else in the Plex Home, as Plex's own "Who's watching?" does. Their
     * token replaces the account's, which brings their own libraries, what they're part
     * way through, and what they've watched — and nothing of anybody else's. Everything
     * on screen was the last profile's, so it all goes and is read again.
     */
    fun switchUser(target: PlexHomeUser, pin: String?) {
        val plex = _state.value.plex
        val token = plex.token ?: return
        if (plex.switchingTo != null) return
        if (target.uuid == plex.user?.uuid) return
        updatePlex { it.copy(switchingTo = target, switchError = null) }
        viewModelScope.launch {
            val next = runCatching { PlexApi.switchHomeUser(clientId, token, target.uuid, pin) }
                .getOrElse { failure ->
                    updatePlex { it.copy(switchingTo = null, switchError = failure.readable()) }
                    return@launch
                }
            stopTheme()
            store.put(SecureStore.PLEX_TOKEN, next)
            store.remove(SecureStore.PLEX_SERVER_URI, SecureStore.PLEX_SERVER_TOKEN, SecureStore.PLEX_SERVER_NAME)
            _state.update {
                it.copy(
                    plex = PlexState(token = next, user = target, homeUsers = plex.homeUsers, busy = true),
                    home = HomeState(),
                    detail = null,
                    focused = null,
                    stack = listOf(Route.Home),
                )
            }
            connectServer(next)
            loadProfiles(next)
        }
    }

    fun dismissSwitchError() = updatePlex { it.copy(switchError = null) }

    private fun loadServerList(token: String) {
        viewModelScope.launch {
            val servers = runCatching { PlexApi.servers(clientId, token) }.getOrElse { return@launch }
            updatePlex { it.copy(servers = servers) }
            scanLibraries()
            preferBetterAddress(servers)
        }
    }

    /*
     * The address found at sign-in is stored and used from then on. If it was the internet
     * one — because the local one would not answer then, or was not tried — every stream
     * went out through the router and back, and the server treated it as remote. So look
     * again at each start for anything better on the list that answers now, and keep it
     * for the next start. Not this one: everything on screen was read from the address in
     * use, and switching under it would leave those items pointing at the old one.
     */
    private suspend fun preferBetterAddress(servers: List<PlexServer>) {
        val plex = _state.value.plex
        val current = plex.baseUrl ?: return
        val server = servers.firstOrNull { it.name == plex.serverName } ?: return
        val better = server.connections.takeWhile { it != current }
        for (uri in better) {
            if (PlexApi.reachable(server, uri)) {
                store.put(SecureStore.PLEX_SERVER_URI, uri)
                return
            }
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
                    it.copy(busy = false, error = "Couldn't reach ${server.name}. Make sure it's on and connected.")
                }
                return@launch
            }
            useServer(server.name, base, server.accessToken)
        }
    }

    fun startPlexLink() {
        if (linkJob?.isActive == true) return
        linkJob = viewModelScope.launch {
            updatePlex { it.copy(busy = true, error = null, linkCode = null, linkUrl = null) }
            // Two PINs for one sign-in: the short one to type, the strong one for the QR
            // code. Whichever gets approved first signs in. The QR is a nicety, so if its
            // PIN can't be had the typed code still works alone.
            val scanned = async { runCatching { PlexApi.createPin(clientId, strong = true) }.getOrNull() }
            val pin = runCatching { PlexApi.createPin(clientId) }.getOrElse { failure ->
                scanned.cancel()
                updatePlex { it.copy(busy = false, error = failure.readable()) }
                return@launch
            }
            val strong = scanned.await()
            updatePlex {
                it.copy(
                    busy = false,
                    linkCode = pin.code,
                    linkUrl = strong?.let { s -> PlexApi.authUrl(clientId, s.code) },
                )
            }

            // plex.tv expires a PIN after 15 minutes; stop looking well before that.
            repeat(150) {
                delay(2_000)
                val token = listOfNotNull(pin, strong).firstNotNullOfOrNull { candidate ->
                    runCatching { PlexApi.claimPin(clientId, candidate.id) }.getOrNull()
                }
                if (token != null) {
                    store.put(SecureStore.PLEX_TOKEN, token)
                    updatePlex { it.copy(token = token, linkCode = null, linkUrl = null) }
                    connectServer(token)
                    loadProfiles(token)
                    return@launch
                }
            }
            updatePlex { it.copy(linkCode = null, linkUrl = null, error = "That code has expired. Try signing in again.") }
        }
    }

    fun cancelPlexLink() {
        linkJob?.cancel()
        linkJob = null
        updatePlex { it.copy(linkCode = null, linkUrl = null, busy = false) }
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
                    error = "This Plex account doesn't have access to any servers.",
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
                error = "Couldn't reach your Plex server. Make sure it's on and connected.",
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
            refreshWatchlist(servers)
            val playlists = servers.flatMap { (base, token) ->
                runCatching { PlexApi.playlists(base, token) }.getOrElse { emptyList() }
            }

            // The row Plex's own home screen shows, from every server, in order of when
            // each thing was last watched. It used to be ordered by when things were
            // added to the library — see continueWatchingOrder.
            val onDeck = continueWatchingOrder(
                servers.flatMap { (base, token) ->
                    runCatching { PlexApi.continueWatching(base, token) }.getOrElse { emptyList() }
                }
            ).take(40)

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

            // Episodes on an older server do not carry their show's logo; fetch them all
            // at once so the hero can show the logo rather than the name in type.
            val showLogos = showLogosFor(onDeck + recentEpisodes.map { group -> group.newest }, servers)
            fun PlexItem.withShowLogo() =
                if (logo != null || type != "episode") this
                else copy(logo = showLogos[serverBase to grandparentRatingKey])

            _state.update {
                it.copy(
                    home = HomeState(
                        continueWatching = onDeck.map { item -> item.withShowLogo() },
                        recentEpisodes = recentEpisodes.map { group -> group.copy(newest = group.newest.withShowLogo()) },
                        recentMovies = recentMovies,
                        // Filled in on its own, and not to be lost when the rest comes in.
                        watchlist = it.home.watchlist,
                        playlists = playlists,
                        busy = false,
                    )
                )
            }
        }
    }

    /**
     * The logos of the shows these episodes belong to, for those that did not arrive with
     * one, keyed by server and show. One request per server.
     */
    private suspend fun showLogosFor(
        items: List<PlexItem>,
        servers: List<Pair<String, String>>,
    ): Map<Pair<String?, String?>, String> {
        val missing = items
            .filter { it.type == "episode" && it.logo == null && it.grandparentRatingKey != null }
            .groupBy { it.serverBase }
        val found = mutableMapOf<Pair<String?, String?>, String>()
        for ((base, episodes) in missing) {
            val token = servers.firstOrNull { it.first == base }?.second ?: continue
            val keys = episodes.mapNotNull { it.grandparentRatingKey }.distinct()
            PlexApi.logos(base ?: continue, token, keys).forEach { (key, logo) -> found[base to key] = logo }
        }
        return found
    }

    /** A title's logo at its own size; see [PlexApi.logoUrl]. */
    fun plexLogoUrl(serverBase: String?, path: String?): String? {
        if (path == null) return null
        val plex = _state.value.plex
        val base = serverBase ?: plex.baseUrl ?: return null
        val token = plex.tokenFor(serverBase) ?: return null
        return PlexApi.logoUrl(base, token, path)
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
        val route = _state.value.route
        if (route is Route.Library && route.kind == kind && route.view == LibraryView.COLLECTIONS) {
            loadCollections(kind)
        }
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

    fun setSort(kind: LibraryKind, sort: LibrarySort) {
        if (_state.value.plex.browseFor(kind).sort == sort) return
        updateBrowse(kind) { it.copy(sort = sort) }
        loadBrowse(kind)
    }

    /** Null is every decade. */
    fun selectDecade(kind: LibraryKind, decade: String?) {
        if (_state.value.plex.browseFor(kind).decade == decade) return
        updateBrowse(kind) { it.copy(decade = decade) }
        loadBrowse(kind)
    }

    /**
     * The A–Z rail: puts the cursor on the first title under [letter]. The grid only
     * holds the pages scrolled through so far, so anything up to there is fetched first.
     */
    fun jumpToLetter(kind: LibraryKind, letter: String) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val browse = plex.browseFor(kind)
        val section = browse.section ?: return
        val index = browse.letterStart(letter) ?: return
        val serial = (browse.jump?.serial ?: 0) + 1
        if (index < browse.items.size) {
            updateBrowse(kind) { it.copy(jump = GridJump(index, serial)) }
            return
        }
        if (browse.busy) return
        val offset = browse.items.size
        browseJobs[kind]?.cancel()
        browseJobs[kind] = viewModelScope.launch {
            updateBrowse(kind) { it.copy(loadingMore = true) }
            // Up to the letter, and a page past it so there is something to move on to.
            val limit = index - offset + GRID_PAGE
            val page = runCatching {
                PlexApi.items(base, token, browsePath(kind, browse, section), limit = limit, offset = offset)
            }.getOrElse {
                updateBrowse(kind) { it.copy(loadingMore = false) }
                return@launch
            }
            updateBrowse(kind) { current ->
                if (current.items.size != offset) current.copy(loadingMore = false)
                else {
                    val items = (current.items + page).distinctBy { it.listKey }
                    current.copy(
                        items = items,
                        loadingMore = false,
                        complete = page.size < limit,
                        jump = GridJump(index.coerceAtMost(items.lastIndex), serial),
                    )
                }
            }
        }
    }

    private fun loadLetters(kind: LibraryKind) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val browse = plex.browseFor(kind)
        val section = browse.section ?: return
        if (browse.sort != LibrarySort.TITLE) {
            updateBrowse(kind) { it.copy(letters = emptyList()) }
            return
        }
        viewModelScope.launch {
            val letters = runCatching {
                PlexApi.firstCharacters(base, token, section.key, kind.filter, browseFilters(browse))
            }.getOrElse { emptyList() }
            updateBrowse(kind) {
                // Only if nothing has changed the grid's order or filters meanwhile.
                val same = it.section?.key == section.key && it.sort == browse.sort &&
                    browseFilters(it) == browseFilters(browse)
                if (same) it.copy(letters = letters) else it
            }
        }
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
    /**
     * The grid from its first page. [keep] is a refresh of the same grid: it asks for as
     * many as are already showing, so a timer going off doesn't take away the page the
     * cursor is on.
     */
    private fun loadBrowse(kind: LibraryKind, keep: Boolean = false) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val browse = plex.browseFor(kind)
        val section = browse.section ?: return

        browseJobs[kind]?.cancel()
        if (!keep) loadLetters(kind)
        browseJobs[kind] = viewModelScope.launch {
            updateBrowse(kind) { it.copy(busy = true, error = null, loadingMore = false, jump = null) }
            val limit = if (keep) maxOf(GRID_PAGE, browse.items.size) else GRID_PAGE
            val items = runCatching { PlexApi.items(base, token, browsePath(kind, browse, section), limit = limit) }
                .getOrElse { failure ->
                    updateBrowse(kind) { it.copy(busy = false, error = failure.readable()) }
                    return@launch
                }
            updateBrowse(kind) { it.copy(busy = false, items = items, complete = items.size < limit) }
        }
    }

    private fun browsePath(kind: LibraryKind, browse: BrowseState, section: PlexSection) =
        "/library/sections/${section.key}/all?type=${kind.filter}&sort=${browse.sort.key}" + browseFilters(browse)

    private fun browseFilters(browse: BrowseState) = buildString {
        browse.genreId?.let { append("&genre=$it") }
        browse.decade?.let { append("&decade=$it") }
        if (browse.unwatchedOnly) append("&unwatched=1")
    }

    /**
     * The next page of the grid, asked for as the cursor nears the end of what is there.
     * The grid used to stop at the first 400, which on a big library was most of it
     * missing with no sign anything was.
     */
    fun loadMoreBrowse(kind: LibraryKind) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val browse = plex.browseFor(kind)
        val section = browse.section ?: return
        if (browse.complete || browse.busy || browse.loadingMore || browse.items.isEmpty()) return
        val offset = browse.items.size
        updateBrowse(kind) { it.copy(loadingMore = true) }
        browseJobs[kind] = viewModelScope.launch {
            val page = runCatching {
                PlexApi.items(base, token, browsePath(kind, browse, section), limit = GRID_PAGE, offset = offset)
            }.getOrElse {
                updateBrowse(kind) { it.copy(loadingMore = false) }
                return@launch
            }
            updateBrowse(kind) { current ->
                // A filter changed while this was on its way: it belongs to a grid that is gone.
                if (current.items.size != offset) current.copy(loadingMore = false)
                else current.copy(
                    items = (current.items + page).distinctBy { it.listKey },
                    loadingMore = false,
                    complete = page.size < GRID_PAGE,
                )
            }
        }
    }

    /** The collections in the library the tab is showing. */
    private fun loadCollections(kind: LibraryKind) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        val section = plex.browseFor(kind).section ?: return
        viewModelScope.launch {
            val collections = runCatching { PlexApi.collections(base, token, section.key) }
                .getOrElse { emptyList() }
            updateBrowse(kind) {
                if (it.section?.key != section.key) it else it.copy(collections = collections)
            }
        }
    }

    private fun loadGenres(kind: LibraryKind, section: PlexSection) {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        viewModelScope.launch {
            val genres = runCatching { PlexApi.genres(base, token, section.key, kind.filter) }
                .getOrElse { emptyList() }
            val decades = runCatching { PlexApi.decades(base, token, section.key, kind.filter) }
                .getOrElse { emptyList() }
            updateBrowse(kind) {
                if (it.section?.key != section.key) it else it.copy(genres = genres, decades = decades)
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
                    it.copy(detail = it.detail?.copy(busy = false, error = "This title isn't available."))
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
            if (detail.type == "collection") launch {
                val members = runCatching { PlexApi.collectionItems(base, token, ratingKey) }
                    .getOrElse { emptyList() }
                _state.update { current ->
                    if (current.detail?.ratingKey != ratingKey) current
                    else current.copy(detail = current.detail.copy(members = members))
                }
            }
            if (detail.type != "collection") launch {
                val related = runCatching { PlexApi.related(base, token, ratingKey) }
                    .getOrElse { emptyList() }
                _state.update { current ->
                    if (current.detail?.ratingKey != ratingKey) current
                    else current.copy(detail = current.detail.copy(related = related))
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
            // Arriving from a row lands on the episode that row was about. Choosing a
            // season by hand has no such episode in mind, and leaving it on nothing meant
            // the rail kept the previous season's scroll and the cursor had nowhere to go.
            val landOn = focusEpisodeKey?.let { key -> episodes.firstOrNull { it.ratingKey == key } }
                ?: episodes.firstOrNull()
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
            play(detail.asItem(), resume = resume, mediaIndex = detailState.versionIndex)
        }
    }

    /**
     * The account's Watchlist, and which of it the servers here have, in the Watchlist's
     * own order. Something no server has can't be opened or played, so it isn't shown.
     */
    private fun refreshWatchlist(servers: List<Pair<String, String>>) {
        val token = _state.value.plex.token ?: return
        viewModelScope.launch {
            val guids = runCatching { PlexApi.watchlist(token) }.getOrElse { return@launch }
            _state.update { it.copy(plex = it.plex.copy(watchlist = guids.toSet())) }
            val found = guids.take(WATCHLIST_ROW).chunked(8).flatMap { batch ->
                batch.map { guid ->
                    async {
                        servers.firstNotNullOfOrNull { (base, serverToken) ->
                            runCatching { PlexApi.byGuid(base, serverToken, guid) }.getOrNull()
                        }
                    }
                }.map { it.await() }
            }.filterNotNull()
            _state.update { it.copy(home = it.home.copy(watchlist = found)) }
        }
    }

    /** On the Watchlist, or off it, for the title whose page this is. */
    fun toggleWatchlist() {
        val detail = _state.value.detail?.detail ?: return
        val guid = detail.guid ?: return
        val token = _state.value.plex.token ?: return
        val on = guid !in _state.value.plex.watchlist.orEmpty()
        fun mark(listed: Boolean) = _state.update {
            val now = it.plex.watchlist.orEmpty()
            it.copy(plex = it.plex.copy(watchlist = if (listed) now + guid else now - guid))
        }
        // Straight away: waiting on Plex's answer made the button feel broken.
        mark(on)
        viewModelScope.launch {
            runCatching { PlexApi.setWatchlisted(token, guid, on) }
                .onSuccess { refreshHome() }
                .onFailure { failure ->
                    mark(!on)
                    _state.update { current ->
                        current.copy(detail = current.detail?.copy(error = failure.readable()))
                    }
                }
        }
    }

    /** The file Play uses on this page, for a title in more than one version. */
    fun selectVersion(index: Int) {
        _state.update { current ->
            val detail = current.detail ?: return@update current
            val count = detail.detail?.versions?.size ?: 0
            if (index !in 0 until count) current else current.copy(detail = detail.copy(versionIndex = index))
        }
    }

    /** Play a library item, resuming where Plex says it was left. */
    fun play(
        item: PlexItem,
        queue: List<PlexItem> = emptyList(),
        resume: Boolean = true,
        mediaIndex: Int = 0,
    ): Job? {
        val plex = _state.value.plex
        val on = item.serverBase
        val base = on ?: plex.baseUrl ?: return null
        val token = plex.tokenFor(on) ?: return null

        return viewModelScope.launch {
            val resolved = runCatching { PlexApi.playback(base, token, item.ratingKey, mediaIndex) }
                .getOrElse { failure ->
                    reportPlaybackProblem(failure.readable())
                    return@launch
                }
            if (resolved == null) {
                reportPlaybackProblem("\"${item.title}\" can't be played.")
                return@launch
            }

            val effectiveQueue = queue.ifEmpty { siblingQueue(item) }
            // Anything from the library is a single picture; the live grid does not survive
            // it, and neither does the connection live television was holding.
            livePlayer.stop()
            silenceTheme()
            _state.update { it.copy(multiview = emptyList()) }
            val startAt = if (resume) item.viewOffsetMs else 0
            val mode = _state.value.prefs.playbackMode
            val transcode = mode == Settings.MODE_TRANSCODE
            val session = UUID.randomUUID().toString()

            /*
             * Decided before the first frame, from what the file's sound is and what this
             * device plays — so a file it cannot play starts converted, rather than
             * starting in silence and restarting once that is noticed. Direct-only means
             * the server is not asked; a full transcode already makes its own choice.
             */
            val convert = if (transcode || mode == Settings.MODE_DIRECT) {
                null
            } else {
                audioPlan(resolved.audioCodec, resolved.audioChannels, deviceAudio::canPlay)
                    as? AudioPlan.Convert
            }
            val serverWorks = transcode || convert != null

            releaseTranscode()
            _state.update {
                it.copy(
                    upNext = null,
                    playback = Playback(
                        title = item.title,
                        subtitle = subtitleLineFor(item),
                        url = if (convert != null) {
                            PlexApi.audioConvertUrl(
                                base = base,
                                token = token,
                                clientId = clientId,
                                ratingKey = item.ratingKey,
                                sessionId = session,
                                audioCodecs = convert.codecs,
                                mediaIndex = mediaIndex,
                            )
                        } else if (transcode) {
                            PlexApi.transcodeUrl(
                                base = base,
                                token = token,
                                clientId = clientId,
                                ratingKey = item.ratingKey,
                                sessionId = session,
                                maxBitrateKbps = it.prefs.maxBitrateKbps,
                                resolution = RESOLUTION,
                                mediaIndex = mediaIndex,
                            )
                        } else {
                            resolved.url
                        },
                        isLive = false,
                        ratingKey = item.ratingKey,
                        // Both start from the file's beginning and seek, so the clock the
                        // player keeps is the file's own — see PlexApi.transcodeUrl.
                        startPositionMs = startAt,
                        durationMs = item.durationMs,
                        subtitles = if (transcode) emptyList() else resolved.subtitles,
                        markers = resolved.markers,
                        previewUrl = resolved.previewUrl,
                        chapters = resolved.chapters,
                        serverBase = on,
                        queue = effectiveQueue,
                        queueIndex = effectiveQueue.indexOfFirst { entry -> entry.ratingKey == item.ratingKey },
                        transcoding = serverWorks,
                        audioConverted = convert != null,
                        transcodeSession = if (serverWorks) session else null,
                        audioCodec = resolved.audioCodec,
                        audioChannels = resolved.audioChannels,
                        mediaIndex = mediaIndex,
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
    /**
     * The file ran out. Up Next if there is anything to go on to; if not — the newest
     * episode, the end of a series, a film — the player closes, rather than sitting on
     * the last frame waiting for Back.
     */
    fun onPlaybackEnded() {
        if (startingNext) return
        val playback = _state.value.playback ?: return
        if (playback.isLive) return
        val next = playback.queue.getOrNull(playback.queueIndex + 1)
        if (next != null) {
            _state.update { it.copy(upNext = next) }
            return
        }
        viewModelScope.launch {
            val following = firstOfNextSeason()
            // Somebody pressed Back, or picked something else, while that was asked.
            if (_state.value.playback !== playback) return@launch
            if (following != null) _state.update { it.copy(upNext = following) }
            else finishAtEnd(playback)
        }
    }

    /**
     * Out of the player after the last thing there was to watch. An episode goes back to
     * its show's page, landed on that episode and ticked as watched; if the page
     * underneath is something else, the show's page is opened. Anything else just closes,
     * back to the page it was played from.
     */
    private fun finishAtEnd(playback: Playback) {
        val detail = _state.value.detail
        val finished = playback.queue.firstOrNull { it.ratingKey == playback.ratingKey }
            ?: detail?.episodes?.firstOrNull { it.ratingKey == playback.ratingKey }
        val show = finished?.grandparentRatingKey?.takeIf { finished.type == "episode" }
        if (finished != null && show != null) {
            val route = _state.value.route
            if (route is Route.Detail && route.ratingKey == show && detail != null) {
                val inRail = detail.episodes.firstOrNull { it.ratingKey == finished.ratingKey }
                val season = detail.seasons.firstOrNull { it.ratingKey == finished.parentRatingKey }
                when {
                    inRail != null -> _state.update { it.copy(detail = it.detail?.copy(focusedEpisode = inRail)) }
                    season != null -> selectSeason(season, focusEpisodeKey = finished.ratingKey)
                }
            } else {
                navigate(
                    Route.Detail(
                        ratingKey = show,
                        seasonKey = finished.parentRatingKey,
                        episodeKey = finished.ratingKey,
                        serverBase = finished.serverBase ?: playback.serverBase,
                    )
                )
            }
        }
        // Reported as stopped at the very end, which is what marks it watched on the server.
        stopPlayback(playback.durationMs)
        playback.ratingKey?.let { applyWatched(it, watched = true) }
    }

    /**
     * The credits have started. Up Next is offered now rather than at the very end, the
     * way Plex does it; the end of the file offers it again to anybody who chose to
     * watch them.
     */
    fun onCreditsReached() {
        if (_state.value.upNext != null) return
        offerUpNext()
    }

    private fun offerUpNext() {
        // Already on its way: the file running out while the next one loads must not
        // put Up Next back up and start its countdown again.
        if (startingNext) return
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

    /*
     * Set from choosing the next episode until it is playing, or has failed to.
     *
     * Up Next used to be cleared the moment Play was chosen, while the next episode was
     * still being asked for. For that second the finished episode's credits grew back to
     * full screen — it looked as though it had started over — and if the file ran out in
     * that time, the end of it offered Up Next a second time, countdown and all, which
     * could start the next episode twice. Now the Up Next screen stays until the next
     * episode replaces it, and anything asking to go again in the meantime is ignored.
     */
    private var startingNext = false

    fun playUpNext() {
        if (startingNext) return
        val next = _state.value.upNext ?: return
        val queue = _state.value.playback?.queue.orEmpty()
        startingNext = true
        val job = play(next, queue = queue.takeIf { list -> list.any { it.ratingKey == next.ratingKey } } ?: emptyList())
        if (job == null) {
            startingNext = false
            return
        }
        job.invokeOnCompletion {
            startingNext = false
            // Playing, it was cleared along with the old playback. Failed, the screen
            // should not sit there offering what will not play.
            _state.update { if (it.upNext == next) it.copy(upNext = null) else it }
        }
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
        val session = sessionFor(ratingKey)
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
                    sessionId = session,
                )
            }
        }
    }

    /** The identifier for the sitting this item is part of, minted when the item opens. */
    private fun sessionFor(ratingKey: String): String {
        if (timelineSessionFor != ratingKey) {
            timelineSession = UUID.randomUUID().toString()
            timelineSessionFor = ratingKey
        }
        return timelineSession
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
                        maxBitrateKbps = it.prefs.maxBitrateKbps,
                        resolution = RESOLUTION,
                        mediaIndex = playback.mediaIndex,
                    ),
                    startPositionMs = positionMs,
                    subtitles = emptyList(),
                    transcoding = true,
                    transcodeSession = session,
                )
            )
        }
    }

    /**
     * The file's sound turned out not to play here, so ask the server to convert just
     * that — see [PlexApi.audioConvertUrl]. Picks up at the same point, with the same
     * subtitles, because the timeline is the file's own.
     *
     * This is the net under the check made before playback, so reaching it means the
     * device's own account of what it plays was wrong, or the file did not say what its
     * sound was. Either way that account is not trusted a second time: AAC only, the one
     * conversion certain to play.
     */
    fun convertAudio(positionMs: Long) {
        val playback = _state.value.playback ?: return
        if (playback.transcoding || playback.isLive) return
        val ratingKey = playback.ratingKey ?: return
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return

        val session = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                playback = playback.copy(
                    url = PlexApi.audioConvertUrl(
                        base = base,
                        token = token,
                        clientId = clientId,
                        ratingKey = ratingKey,
                        sessionId = session,
                        mediaIndex = playback.mediaIndex,
                    ),
                    startPositionMs = positionMs,
                    transcoding = true,
                    audioConverted = true,
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

    fun setMaxBitrate(kbps: Int) {
        settings.maxBitrateKbps = kbps
        _state.update { it.copy(prefs = it.prefs.copy(maxBitrateKbps = kbps)) }
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
                    PlexApi.reportTimeline(
                        base = base,
                        token = token,
                        ratingKey = ratingKey,
                        positionMs = positionMs,
                        durationMs = playback.durationMs,
                        state = "stopped",
                        sessionId = sessionFor(ratingKey),
                    )
                }
                refreshHome()
            }
        }
        // A later play of the same thing is a new sitting, so it gets a new identifier.
        timelineSessionFor = null
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
            val found = servers.map { (base, token) ->
                runCatching { PlexApi.searchAll(base, token, query) }.getOrElse { emptyList<PlexItem>() to emptyList() }
            }
            val (matches, others) = tv.reely.core.SearchMatch.split(query, found.flatMap { it.first })
            // Nothing by name, a misspelling most likely: then Plex's own guesses are the results.
            val results = matches.ifEmpty { others }
            val more = if (matches.isEmpty()) emptyList() else others
            val people = found.flatMap { it.second }.distinctBy { it.name.lowercase() }.take(PEOPLE_RESULTS)
            // Channels are matched here rather than asked of the panel: the panel has no
            // search, and the whole list is already in hand.
            val channels = allChannels.orEmpty()
                .filter { it.name.contains(query.trim(), ignoreCase = true) }
                .take(CHANNEL_RESULTS)
            _state.update { current ->
                if (current.search.query != query) current
                else current.copy(
                    search = current.search.copy(
                        results = results, more = more, channels = channels, people = people, busy = false,
                    )
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

    /** Off Continue Watching, here and on the server; put back if the server says no. */
    fun removeFromContinueWatching(item: PlexItem) {
        val plex = _state.value.plex
        val base = item.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(item.serverBase) ?: return
        val before = _state.value.home.continueWatching
        _state.update { it.copy(home = it.home.copy(continueWatching = before.filterNot { entry -> entry.listKey == item.listKey })) }
        viewModelScope.launch {
            val ok = runCatching { PlexApi.removeFromContinueWatching(base, token, item.ratingKey) }.isSuccess
            if (!ok) {
                _state.update { it.copy(home = it.home.copy(continueWatching = before)) }
                reportPlaybackProblem("Couldn't remove that from Continue Watching.")
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
                format = if (settings.streamFormat == Settings.FORMAT_TS) StreamFormat.TS
                else StreamFormat.HLS
            )
        }
        store.get(SecureStore.M3U_URL)?.let { url ->
            connectXtream(XtreamCredentials.playlist(url, store.get(SecureStore.M3U_GUIDE)), persist = false)
            return
        }
        val host = store.get(SecureStore.XTREAM_HOST) ?: return
        val username = store.get(SecureStore.XTREAM_USERNAME) ?: return
        val password = store.get(SecureStore.XTREAM_PASSWORD) ?: return
        connectXtream(XtreamCredentials(host, username, password), persist = false)
    }

    fun signInXtream(host: String, username: String, password: String) {
        val base = XtreamApi.normalizeBase(host)
        if (base.isEmpty() || username.isBlank() || password.isBlank()) {
            updateLive { it.copy(error = "Enter the server address, username and password.") }
            return
        }
        viewModelScope.launch {
            connectXtream(XtreamCredentials(base, username.trim(), password), persist = true)
        }
    }

    /**
     * Live TV from an M3U playlist. The playlist address a panel hands out (its get.php)
     * carries the login in it, and signing in with that login instead gets the panel's
     * own guide and categories, so that is what happens when one is entered.
     */
    fun signInPlaylist(url: String, guideUrl: String) {
        val address = url.trim()
        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
            updateLive { it.copy(error = "Enter the playlist's full address, starting with http:// or https://.") }
            return
        }
        val guide = guideUrl.trim().takeIf { it.isNotEmpty() }
        if (guide != null && !guide.startsWith("http://", true) && !guide.startsWith("https://", true)) {
            updateLive { it.copy(error = "Enter the guide's full address, starting with http:// or https://.") }
            return
        }
        val panel = panelLoginIn(address)
        viewModelScope.launch {
            if (panel != null && guide == null) connectXtream(panel, persist = true)
            else connectXtream(XtreamCredentials.playlist(address, guide), persist = true)
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
            store.remove(*LIVE_KEYS)
            if (credentials.playlistUrl != null) {
                store.put(SecureStore.M3U_URL, credentials.playlistUrl)
                credentials.guideUrl?.let { store.put(SecureStore.M3U_GUIDE, it) }
            } else {
                store.put(SecureStore.XTREAM_HOST, credentials.base)
                store.put(SecureStore.XTREAM_USERNAME, credentials.username)
                store.put(SecureStore.XTREAM_PASSWORD, credentials.password)
            }
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
            val categories = runCatching {
                XtreamApi.reload(credentials)
                XtreamApi.liveCategories(credentials)
            }
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
            val channels = runCatching {
                if (category.id == LiveState.FAVORITES.id) favoriteChannelList(credentials)
                else XtreamApi.liveChannels(credentials, category.id)
            }.getOrElse { failure ->
                updateLive { it.copy(busy = false, error = failure.readable()) }
                return@launch
            }
            updateLive { it.copy(busy = false, channels = channels) }
            _state.update { it.copy(guide = it.guide.copy(channelIndex = 0)) }
            loadGuideWindow()
            channels.firstOrNull()?.let { focusChannel(it) }
        }
    }

    /** The favorite channels, in the provider's own order, from its whole list. */
    private suspend fun favoriteChannelList(credentials: XtreamCredentials): List<XtreamChannel> {
        val all = allChannels ?: XtreamApi.liveChannels(credentials).also { allChannels = it }
        val favorites = _state.value.live.favorites
        return all.filter { it.streamId in favorites }
    }

    /**
     * Marks a channel as a favorite, or stops. The Favorites list itself is read again the
     * next time it is opened, not under somebody watching from it.
     */
    fun toggleFavoriteChannel(channel: XtreamChannel) {
        val next = _state.value.live.favorites.let {
            if (channel.streamId in it) it - channel.streamId else it + channel.streamId
        }
        settings.favoriteChannels = next
        updateLive { it.copy(favorites = next) }
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

    fun toggleMultiviewLayout() {
        val next = if (settings.multiviewLayout == Settings.LAYOUT_FOCUS) Settings.LAYOUT_GRID
        else Settings.LAYOUT_FOCUS
        settings.multiviewLayout = next
        _state.update { it.copy(prefs = it.prefs.copy(multiviewLayout = next)) }
    }

    /**
     * Puts a different channel in a tile that is already there. Slot zero is the channel
     * the player itself is on, so replacing that one is simply changing channel.
     */
    fun replaceInMultiview(slot: Int, channel: XtreamChannel) {
        if (slot == 0) {
            val index = _state.value.live.channels.indexOfFirst { it.streamId == channel.streamId }
            if (index >= 0) playChannel(index)
            return
        }
        val index = slot - 1
        _state.update { current ->
            if (index !in current.multiview.indices) current
            else current.copy(
                multiview = current.multiview.toMutableList().apply { this[index] = channel }
            )
        }
    }

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
            val credentials = _state.value.live.credentials
            if (credentials != null && XtreamApi.xmltvUrl(credentials) == null) {
                // A playlist that names no guide: say so, rather than fail at fetching one.
                if (stored == 0) {
                    _state.update { it.copy(guide = it.guide.copy(status = GuideStatus.Failed(NO_PLAYLIST_GUIDE))) }
                }
            } else if (stale) refreshGuide(force = false)
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
                it.copy(guide = it.guide.copy(status = GuideStatus.Failed("Sign in to your live TV provider first.")))
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
        store.remove(*LIVE_KEYS)
        channelsJob?.cancel()
        allChannels = null
        // Favorites belong to the television, not the login: they stay for the next one.
        _state.update {
            it.copy(live = LiveState(favorites = settings.favoriteChannels), search = it.search.copy(channels = emptyList()))
        }
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
            // Try again, after a download that failed: the same build as before.
            is UpdateStatus.Failed -> offeredUpdate ?: return
            else -> return
        }
        offeredUpdate = info
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

    fun toggleLargerBuffer() {
        val next = !settings.largerBuffer
        settings.largerBuffer = next
        _state.update { it.copy(prefs = it.prefs.copy(largerBuffer = next)) }
    }

    fun toggleMatchFrameRate() {
        val next = !settings.matchFrameRate
        settings.matchFrameRate = next
        _state.update { it.copy(prefs = it.prefs.copy(matchFrameRate = next)) }
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

        /** How many people a search offers. */
        const val PEOPLE_RESULTS = 12

        /** How much of the Watchlist Home looks for on the servers. */
        const val WATCHLIST_ROW = 40

        /** Guide data older than this is worth fetching again. */
        const val REFRESH_AFTER_SECONDS = 6L * 3_600

        const val NO_PLAYLIST_GUIDE =
            "This playlist doesn't come with a TV guide. Add a guide address when you sign in."

        /** Everything stored for live TV, whichever way it was signed in to. */
        val LIVE_KEYS = arrayOf(
            SecureStore.XTREAM_HOST,
            SecureStore.XTREAM_USERNAME,
            SecureStore.XTREAM_PASSWORD,
            SecureStore.M3U_URL,
            SecureStore.M3U_GUIDE,
        )

        /** The least time between two refreshes of a library caused by passing its tab. */
        const val LIBRARY_REFRESH_GAP_MS = 60_000L

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

/**
 * The Xtream Codes login inside a panel's playlist address
 * (`http://host:port/get.php?username=…&password=…&type=m3u_plus`), or null for any other.
 */
internal fun panelLoginIn(playlistUrl: String): XtreamCredentials? {
    val uri = runCatching { android.net.Uri.parse(playlistUrl) }.getOrNull() ?: return null
    if (uri.path?.endsWith("/get.php") != true) return null
    val username = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
    val password = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
    val base = playlistUrl.substringBefore("/get.php")
    return XtreamCredentials(base, username, password)
}
