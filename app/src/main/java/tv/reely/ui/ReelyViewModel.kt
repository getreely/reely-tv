package tv.reely.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin
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
import tv.reely.core.startsWithServerSubtitles
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
import tv.reely.plex.unreachableMessage
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
import tv.reely.xtream.IPTV_SOURCE
import tv.reely.xtream.IptvKey
import tv.reely.xtream.IptvWatch
import tv.reely.xtream.VodCatalogCache
import tv.reely.xtream.VodItems
import tv.reely.xtream.VodNames
import tv.reely.xtream.XtreamVod
import tv.reely.xtream.isIptv
import tv.reely.xtream.isIptvSource
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
enum class LibraryView { HOME, GRID, COLLECTIONS, IPTV }

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

    /** Asking for films and shows the server doesn't have, through Reely. */
    data object Requests : Route

    /** One title's page in Requests, before it is asked for. */
    data class RequestTitle(val title: tv.reely.requests.RequestTitle) : Route

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

/** The Request tab: Reely, where somebody asks for what the server doesn't have. */
data class RequestsState(
    /** Reely's address, once one has been given. */
    val server: String? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val rows: List<tv.reely.requests.RequestRow> = emptyList(),
    val loading: Boolean = false,
    val query: String = "",
    val results: List<tv.reely.requests.RequestTitle> = emptyList(),
    val searching: Boolean = false,
    /** What this account has asked for, and where each has got to. */
    val mine: List<tv.reely.requests.RequestRecord> = emptyList(),
    /** What Reely holds and what's been asked for, to mark posters with. */
    val marks: tv.reely.requests.TitleMarks = tv.reely.requests.TitleMarks(),
    /** This account's requests that have arrived since they were last looked at. */
    val ready: List<tv.reely.requests.RequestTitle> = emptyList(),
    /** The outside ids of the films and shows in the Plex libraries here; see plexHas. */
    val plexMovies: Set<String> = emptySet(),
    val plexShows: Set<String> = emptySet(),
) {
    /** The state of this account's request for [title], if it made one. */
    fun statusOf(title: tv.reely.requests.RequestTitle): String? =
        mine.firstOrNull { it.title.key == title.key }?.status

    /**
     * What a poster says under its title, as Reely's own Explore marks it — what Reely
     * holds first (Downloading, In library, Partial), then this account's own request
     * (Approved, Declined), then anybody's open request.
     */
    /**
     * The discovery rows less what's in the library already: there's nothing to ask for
     * in those. A show with seasons still to come stays (Partial), as does anything on
     * its way. Search still finds them; a row left empty isn't shown.
     */
    val shownRows: List<tv.reely.requests.RequestRow>
        get() = rows.mapNotNull { row ->
            row.copy(titles = row.titles.filterNot { badgeFor(it) == "In library" }).takeIf { it.titles.isNotEmpty() }
        }

    fun badgeFor(title: tv.reely.requests.RequestTitle): String? {
        val mark = marks.badge(title)
        // In Plex already, whether or not Reely keeps track of it: Reely only knows what
        // it added, and a library that was there first is most of what anybody has.
        // Reely's own word wins when it has more to say, like Downloading or Partial.
        if ((mark == null || mark == "Requested") && tv.reely.requests.plexHas(title, plexMovies, plexShows)) {
            return "In library"
        }
        if (mark != null && mark != "Requested") return mark
        return when (statusOf(title)) {
            "approved" -> "Approved"
            "denied" -> "Declined"
            "pending" -> "Requested"
            else -> mark
        }
    }
}

/** A title's page in Requests. */
data class RequestDetailState(
    val title: tv.reely.requests.RequestTitle,
    val detail: tv.reely.requests.RequestDetail? = null,
    val busy: Boolean = true,
    val error: String? = null,
    /** The seasons picked, for a show. */
    val chosen: Set<Int> = emptySet(),
    val sending: Boolean = false,
    /** What came of asking, to say on the page. */
    val outcome: String? = null,
    /** Where asks can go; null until known, or when Reely wouldn't say. */
    val places: tv.reely.requests.RequestPlaces? = null,
    /** The library picked, from [addable]. */
    val libraryId: Long? = null,
) {
    /** The libraries it could go to: the right kind, and not holding all of it already. */
    val addable: List<tv.reely.requests.RequestLibrary>?
        get() = places?.librariesFor(title, detail?.let { d -> d.inLibraries.filter(d::complete).toSet() }.orEmpty())

    /** The seasons offered in the library picked: those not asked for there already. */
    val seasonsOffered: List<tv.reely.requests.RequestSeason>
        get() = detail?.seasonsLeft(libraryId).orEmpty()

    /** Of [seasonsOffered], the ones picked. */
    val chosenOffered: Set<Int>
        get() = seasonsOffered.map { it.number }.toSet().intersect(chosen)

    /** The seasons the library picked has already, when it holds part of the show. */
    val seasonsHere: List<tv.reely.requests.RequestSeason>
        get() = detail?.let { d -> d.askedIn(libraryId).let { asked -> d.seasons.filter { it.number in asked } } }.orEmpty()

    /** The button: what pressing it asks for. */
    fun askLabel(verb: String): String = when {
        sending -> if (verb == "Add") "Adding…" else "Requesting…"
        !title.isShow || seasonsOffered.isEmpty() -> verb
        chosenOffered.size == seasonsOffered.size && seasonsHere.isEmpty() -> "$verb all seasons"
        chosenOffered.size == seasonsOffered.size && seasonsOffered.size > 1 -> "$verb the other ${seasonsOffered.size} seasons"
        chosenOffered.size == 1 -> "$verb 1 season"
        else -> "$verb ${chosenOffered.size} seasons"
    }

    /** What the library picked has of a show it holds part of, to say above the seasons. */
    val partNote: String?
        get() = seasonsHere.takeIf { it.isNotEmpty() && seasonsOffered.isNotEmpty() }?.let { here ->
            val names = if (here.size == 1) here.first().name else "${here.size} seasons"
            "$names here already. Pick more to ask for."
        }

    /** Whether there's anywhere left for it to go. Unknown counts as yes: Reely decides. */
    val canAsk: Boolean get() = addable?.isNotEmpty() ?: true

    val library: tv.reely.requests.RequestLibrary? get() = addable?.firstOrNull { it.id == libraryId }
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
    /** The newest films and series from the IPTV provider, when they're switched on. */
    val iptvMovies: List<PlexItem> = emptyList(),
    val iptvShows: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = continueWatching.isEmpty() && recentEpisodes.isEmpty() && recentMovies.isEmpty() &&
            watchlist.isEmpty() && playlists.isEmpty() && iptvMovies.isEmpty() && iptvShows.isEmpty()
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
    /** How many titles there are in all, with the filters on: the count over the grid. Null until known. */
    val total: Int? = null,
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
    /** Just signed in to a Plex Home of several people: "Who's watching?" comes up once. */
    val askWho: Boolean = false,
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
    /** Channels most recently tuned to, newest first, by stream id. */
    val recent: List<Int> = emptyList(),
) {
    val isConnected: Boolean get() = credentials != null && account != null

    fun nowNext(streamId: Int): List<XtreamProgramme> = guide[streamId].orEmpty()

    /** The provider's categories, after Favorites and Recently watched once there are any. */
    val shownCategories: List<XtreamCategory>
        get() = listOfNotNull(
            FAVORITES.takeIf { favorites.isNotEmpty() },
            RECENT.takeIf { recent.isNotEmpty() },
        ) + categories

    companion object {
        /** Not one of the provider's: the channels marked as favorites, from all of them. */
        val FAVORITES = XtreamCategory(id = "reely:favorites", name = "Favorites")

        /** Nor this: the channels last tuned to, the latest first. */
        val RECENT = XtreamCategory(id = "reely:recent", name = "Recently watched")

        /** How many channels Recently watched keeps. */
        const val RECENT_LIMIT = 20
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
    /** Collections whose name matches: "Marvel", "James Bond", whatever the server keeps. */
    val collections: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
    /** No server answered: not the same as nothing matching, and not to be said as though it were. */
    val unreachable: Boolean = false,
    /** What was searched for lately, newest first, to offer again when the box is empty. */
    val recent: List<String> = emptyList(),
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
    /**
     * Behind a live channel, out of its archive: the programme on now, rewound. Going
     * forward to now, or its end, goes back to the channel live.
     */
    val timeshift: Timeshift? = null,
    /**
     * The queue is a playlist: it ends where the playlist does. Otherwise the end of a
     * season carries on into the next one, and a playlist whose last item was an episode
     * offered the rest of that show.
     */
    val playlist: Boolean = false,
    val mediaIndex: Int = 0,
    val chapters: List<tv.reely.plex.PlexChapter> = emptyList(),
    /** The file's part, and its sound and subtitle streams with the server's choice marked. */
    val partId: Long? = null,
    val audioStreams: List<tv.reely.core.PlexStream> = emptyList(),
    val subtitleStreams: List<tv.reely.core.PlexStream> = emptyList(),
) {
    /** From the IPTV provider's films and series: nothing to report to Plex, nothing it can convert. */
    val fromIptv: Boolean get() = serverBase == tv.reely.xtream.IPTV_SOURCE

    /** A title from a Plex server, which is told where it's up to and can convert what won't play. */
    val onPlex: Boolean get() = !isLive && ratingKey != null && !fromIptv
}

data class PlayerPrefs(
    val subtitleScale: Float = Settings.DEFAULT_SCALE,
    val subtitleBackground: Boolean = false,
    /** Settings.SUBTITLES_PLEX or SUBTITLES_OFF: what a title starts with. */
    val subtitlesAtStart: String = Settings.SUBTITLES_PLEX,
    /** The colour things are marked in; see AccentChoice. */
    val accent: String = "blue",
    /** The IPTV library in the Movies and TV Shows menus, as one of the libraries. */
    val iptvInMenus: Boolean = true,
    val upNextSeconds: Int = Settings.DEFAULT_UP_NEXT,
    val guidePreview: Boolean = true,
    val playbackMode: String = Settings.MODE_AUTO,
    val maxBitrateKbps: Int = 0,
    val multiviewLayout: String = Settings.LAYOUT_GRID,
    val themeMusic: Boolean = false,
    val themeVolume: Float = Settings.DEFAULT_THEME_VOLUME,
    val matchFrameRate: Boolean = true,
    val largerBuffer: Boolean = false,
    /** Where sound goes (see AudioOutputs); null for wherever the system sends it. */
    val audioOutput: String? = null,
    /** Straight past an episode's intro, where the server has marked one. */
    val skipIntros: Boolean = false,
    /** Straight on to the next episode at the credits, where there is one. */
    val skipCredits: Boolean = false,
    /** Minutes idle before the screensaver; 0 for none. */
    val screensaverMinutes: Int = Settings.DEFAULT_SCREENSAVER,
    /** The tour of the remote has been taken or skipped; see Tour. */
    val tourSeen: Boolean = true,
    /** Home's rows switched off in Settings; see HomeRow. */
    val hiddenHomeRows: Set<String> = emptySet(),
    /** IPTV's films and series alongside Plex's; see Settings.iptvLibrary. */
    val iptvLibrary: Boolean = false,
    /** A title in both shows as IPTV's copy; see Settings.iptvWins. */
    val iptvWins: Boolean = false,
)

/**
 * Home's rows, each of which can be switched off in Settings. The ids are what's kept,
 * so they stay the same whatever the rows come to be called.
 */
enum class HomeRow(val id: String, val title: String, val fromReely: Boolean = false) {
    CONTINUE("continue", "Continue Watching"),
    EPISODES("episodes", "Recently Added Episodes"),
    MOVIES("movies", "Recently Added Movies"),
    WATCHLIST("watchlist", "Watchlist"),
    PLAYLISTS("playlists", "Playlists"),
    IPTV_MOVIES("iptvMovies", "New Movies on IPTV"),
    IPTV_SHOWS("iptvShows", "New Shows on IPTV"),
    TRENDING("trending", "Trending", fromReely = true),
    POPULAR("popular", "Popular", fromReely = true),
}

/** How often a browsing screen left up asks for what has changed. */
/** How long after starting the app it looks for a newer version. */
private const val UPDATE_CHECK_DELAY_MS = 4_000L

/**
 * A live channel rewound into its archive: which channel, by its id — its place in a list
 * changes with the category open — and the programme's span in epoch seconds.
 */
data class Timeshift(val streamId: Int, val start: Long, val stop: Long)

/** How near now counts as caught up with live, behind it in the archive. */
private const val LIVE_CATCH_UP_MS = 20_000L

/** How old the IPTV catalogue kept on the device can be before it's read from the provider again. */
private const val IPTV_CATALOG_MS = 12L * 60 * 60 * 1000

/** How soon Home is asked for again while the server isn't answering. */
private const val HOME_RETRY_MS = 30_000L

/** How far in Plex counts something as watched, by its default setting. */
private const val WATCHED_FRACTION = 0.9

/**
 * Where something was stopped, on the pages and rows already showing it: the show's page
 * it goes back to, a film's own page, Continue Watching. They were loaded before it
 * played, so Resume there went back to where the last sitting had started rather than
 * where this one ended. Past the point Plex counts it as watched, it's watched and
 * starts from the top.
 */
internal fun ReelyState.withProgress(ratingKey: String, positionMs: Long, durationMs: Long, server: String? = null): ReelyState {
    val watched = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
    val offset = if (watched) 0L else positionMs
    fun seen(count: Int) = if (watched) maxOf(count, 1) else count
    return patchItem(
        ratingKey,
        server,
        change = { it.copy(viewOffsetMs = offset, viewCount = seen(it.viewCount)) },
        changeDetail = { it.copy(viewOffsetMs = offset, viewCount = seen(it.viewCount)) },
    )
}

/**
 * Watched or not, on every screen and row showing it: a film's own page, a show's and its
 * episodes, the libraries, Home, search, a person's page, a playlist. Marking a film
 * watched on its own page used to change everywhere but that page, whose button then
 * offered to mark it watched again.
 */
internal fun ReelyState.withWatched(ratingKey: String, watched: Boolean, server: String? = null): ReelyState {
    fun PlexItem.marked() = copy(
        viewCount = if (watched) maxOf(1, viewCount) else 0,
        viewOffsetMs = if (watched) 0 else viewOffsetMs,
        viewedLeafCount = if (type == "show" || type == "season") (if (watched) leafCount else 0) else viewedLeafCount,
    )
    val marked = patchItem(
        ratingKey,
        server,
        change = { it.marked() },
        changeDetail = {
            it.copy(
                viewCount = if (watched) maxOf(1, it.viewCount) else 0,
                viewOffsetMs = if (watched) 0 else it.viewOffsetMs,
                viewedLeafCount = if (watched) it.leafCount else 0,
            )
        },
    )
    // A library showing only what's unwatched no longer shows it, as it wouldn't read again.
    val sifted = if (!watched) marked else marked.copy(
        plex = marked.plex.copy(
            browse = marked.plex.browse.mapValues { (_, browse) ->
                if (!browse.unwatchedOnly) browse
                else browse.copy(items = browse.items.filterNot { it.ratingKey == ratingKey && sameServer(it.serverBase, server) })
            },
        ),
        iptv = marked.iptv.copy(
            movies = marked.iptv.movies.let { browse ->
                if (!browse.unwatchedOnly) browse
                else browse.copy(items = browse.items.filterNot { it.ratingKey == ratingKey && sameServer(it.serverBase, server) })
            },
            shows = marked.iptv.shows.let { browse ->
                if (!browse.unwatchedOnly) browse
                else browse.copy(items = browse.items.filterNot { it.ratingKey == ratingKey && sameServer(it.serverBase, server) })
            },
        ),
    )
    // A show or season marked as a whole marks the episodes on its page with it.
    val page = sifted.detail ?: return sifted
    val whole = sameServer(page.serverBase, server) &&
        (page.detail?.ratingKey == ratingKey || page.selectedSeason?.ratingKey == ratingKey)
    if (!whole) return sifted
    return sifted.copy(
        detail = page.copy(
            episodes = page.episodes.map { it.marked() },
            focusedEpisode = page.focusedEpisode?.marked(),
            seasons = if (page.detail?.ratingKey == ratingKey) page.seasons.map { it.marked() } else page.seasons,
        ),
    )
}

/**
 * Everything read as one profile, or from one server, gone when that changes: the pages,
 * rows, search results, what was asked for in Reely and what's marked In library. A
 * profile switch used to leave the last one's search results, actor and playlist pages
 * and Requests behind, and a server switch left the way back to pages from the old server,
 * which then opened whatever had the same number on the new one.
 */
internal fun ReelyState.forgetAccount(): ReelyState = copy(
    home = HomeState(),
    detail = null,
    person = null,
    playlist = null,
    requestDetail = null,
    focused = null,
    upNext = null,
    search = SearchState(recent = search.recent),
    requests = requests.copy(
        mine = emptyList(),
        marks = tv.reely.requests.TitleMarks(),
        ready = emptyList(),
        plexMovies = emptySet(),
        plexShows = emptySet(),
    ),
    stack = if (route is Route.Settings) listOf(Route.Home, Route.Settings) else listOf(Route.Home),
)

/** Two ways of naming a server the same: no server given is the one connected. */
internal fun ReelyState.sameServer(a: String?, b: String?): Boolean = (a ?: plex.baseUrl) == (b ?: plex.baseUrl)

/**
 * The same title: the same number on the same server. A number is only a server's own,
 * so with two servers the same one is two different titles.
 */
internal fun ReelyState.sameTitle(a: PlexItem, b: PlexItem): Boolean =
    a.ratingKey == b.ratingKey && sameServer(a.serverBase, b.serverBase)

/** What is playing is [item]: not only the same number, but from the same server. */
internal fun ReelyState.isPlaying(playback: Playback, item: PlexItem): Boolean =
    playback.ratingKey == item.ratingKey && sameServer(playback.serverBase, item.serverBase)

/** One title changed, on every screen and row holding a copy of it. */
internal fun ReelyState.patchItem(
    ratingKey: String,
    server: String?,
    change: (PlexItem) -> PlexItem,
    changeDetail: (tv.reely.plex.PlexDetail) -> tv.reely.plex.PlexDetail,
): ReelyState {
    // By number and server: two servers number their titles independently, and the
    // same number on each is two different titles side by side on Home.
    fun one(item: PlexItem) = if (item.ratingKey == ratingKey && sameServer(item.serverBase, server)) change(item) else item
    fun all(items: List<PlexItem>) = items.map(::one)
    return copy(
        focused = focused?.let(::one),
        upNext = upNext?.let(::one),
        home = home.copy(
            continueWatching = all(home.continueWatching),
            recentEpisodes = home.recentEpisodes.map { it.copy(newest = one(it.newest)) },
            recentMovies = all(home.recentMovies),
            watchlist = all(home.watchlist),
            iptvMovies = all(home.iptvMovies),
            iptvShows = all(home.iptvShows),
        ),
        plex = plex.copy(
            browse = plex.browse.mapValues { (_, browse) ->
                browse.copy(items = all(browse.items), released = all(browse.released))
            },
        ),
        iptv = iptv.copy(
            movies = iptv.movies.copy(items = all(iptv.movies.items)),
            shows = iptv.shows.copy(items = all(iptv.shows.items)),
        ),
        detail = detail?.let { page ->
            page.copy(
                detail = page.detail?.let {
                    if (it.ratingKey == ratingKey && sameServer(page.serverBase, server)) changeDetail(it) else it
                },
                seasons = all(page.seasons),
                selectedSeason = page.selectedSeason?.let(::one),
                episodes = all(page.episodes),
                focusedEpisode = page.focusedEpisode?.let(::one),
                related = all(page.related),
                members = all(page.members),
            )
        },
        person = person?.let { it.copy(items = all(it.items)) },
        playlist = playlist?.let { it.copy(items = all(it.items)) },
        search = search.copy(
            results = all(search.results),
            more = all(search.more),
            collections = all(search.collections),
        ),
    )
}

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
    /**
     * Downloaded, and for the screen to open the installer on. [request] goes up each time
     * that's asked for; [note] says why the last one didn't get as far as the installer.
     */
    data class Handed(
        val file: java.io.File,
        val request: Int = 1,
        /** The last request the screen has acted on, so none is acted on twice. */
        val handled: Int = 0,
        val note: String? = null,
    ) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * When playback should stop by itself: at a time on the clock, or at the end of what's
 * playing. It carries on from one episode to the next, and goes when the player is left.
 */
data class SleepTimer(val atMs: Long? = null, val endOfEpisode: Boolean = false)

/** Looking for subtitles online for what's playing, through the server. */
data class SubtitleSearch(
    val language: String,
    val busy: Boolean = true,
    val results: List<tv.reely.plex.PlexOnlineSubtitle> = emptyList(),
    val error: String? = null,
    /** The one being fetched and added, by key. */
    val adding: String? = null,
)

data class ReelyState(
    /** Channels saved to open side by side again, the main one first. */
    val savedMultiview: List<tv.reely.core.SavedChannel> = emptyList(),
    /** Subtitles being looked for online, for what's playing. */
    val subtitleSearch: SubtitleSearch? = null,
    /** Programmes to be told about when they start, soonest first. */
    val reminders: List<tv.reely.core.Reminder> = emptyList(),
    /** The one starting now, while it's being said. */
    val dueReminder: tv.reely.core.Reminder? = null,
    val sleep: SleepTimer? = null,
    val restoring: Boolean = true,
    val stack: List<Route> = listOf(Route.Home),
    val plex: PlexState = PlexState(),
    val home: HomeState = HomeState(),
    val detail: DetailState? = null,
    val person: PersonState? = null,
    val playlist: PlaylistState? = null,
    val requests: RequestsState = RequestsState(),
    val requestDetail: RequestDetailState? = null,
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
    /** The IPTV provider's films and series, when switched on. */
    val iptv: IptvState = IptvState(),
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
                subtitlesAtStart = settings.subtitlesAtStart,
                accent = settings.accent,
                iptvInMenus = settings.iptvInMenus,
                upNextSeconds = settings.upNextSeconds,
                guidePreview = settings.guidePreview,
                playbackMode = settings.playbackMode,
                maxBitrateKbps = settings.maxBitrateKbps,
                multiviewLayout = settings.multiviewLayout,
                themeMusic = settings.themeMusic,
                themeVolume = settings.themeVolume,
                matchFrameRate = settings.matchFrameRate,
                largerBuffer = settings.largerBuffer,
                audioOutput = settings.audioOutput,
                skipIntros = settings.skipIntros,
                skipCredits = settings.skipCredits,
                screensaverMinutes = settings.screensaverMinutes,
                tourSeen = settings.tourSeen,
                hiddenHomeRows = settings.hiddenHomeRows,
                iptvLibrary = settings.iptvLibrary,
                iptvWins = settings.iptvWins,
            )
        )
    )
    val state: StateFlow<ReelyState> = _state.asStateFlow()

    /** For tests: the state as a screen should find it, set outright. */
    @androidx.annotation.VisibleForTesting
    internal fun setStateForTest(change: (ReelyState) -> ReelyState) = _state.update(change)

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
    private var reminderJob: Job? = null
    private var channelsJob: Job? = null
    private val browseJobs = mutableMapOf<LibraryKind, Job>()
    private var updateJob: Job? = null
    private var libraryScanJob: Job? = null
    private var themeJob: Job? = null

    /** Every live channel the account carries, fetched once and reused by search. */
    private var allChannels: List<XtreamChannel>? = null

    init {
        // The colour picked in Settings, before anything is drawn in it.
        tv.reely.ui.theme.useAccent(settings.accent)
        setReminders(tv.reely.core.Reminders.decode(settings.reminders))

        PlexApi.clientId = clientId
        // The name the television was given in its own settings, as Plex's apps use; the
        // model when there is none, which is still more use than the app's name.
        PlexApi.deviceName = runCatching {
            android.provider.Settings.Global.getString(application.contentResolver, "device_name")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Reely TV"
        updatePlex { it.copy(favouriteSections = settings.favouriteSections) }
        updateLive { it.copy(favorites = settings.favoriteChannels, recent = settings.recentChannels) }
        _state.update {
            it.copy(search = it.search.copy(recent = settings.recentSearches), savedMultiview = settings.savedMultiview)
        }
        viewModelScope.launch {
            restorePlex()
            restoreLive()
            restoreRequests()
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
        if (!BuildConfig.SELF_UPDATE) return
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
                route is Route.Detail || route is Route.Person || route is Route.Playlist ||
                    route is Route.RequestTitle -> current.stack + route
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
        if (route is Route.Requests) loadRequests()
        if (route is Route.RequestTitle) loadRequestTitle(route.title)
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
        // Back from a title's page: what was asked for there shows in Your requests.
        if (route is Route.Requests) refreshMyRequests()
    }

    // ---------------------------------------------------------------- Requests (Reely)

    private var reely: tv.reely.requests.ReelyRequests? = null
    private var requestSearchJob: Job? = null

    private fun reelyFor(address: String) =
        tv.reely.requests.ReelyRequests(address, { _state.value.plex.token }, { store.get(SecureStore.PLEX_ACCOUNT_TOKEN) })

    private suspend fun restoreRequests() {
        val address = store.get(SecureStore.REELY_URL) ?: return
        reely = reelyFor(address)
        _state.update { it.copy(requests = it.requests.copy(server = address)) }
    }

    /** Connects to Reely at [address], signing in with the Plex account in use. */
    fun connectReely(address: String) {
        if (!tv.reely.requests.ReelyRequests.isValid(address)) {
            _state.update { it.copy(requests = it.requests.copy(error = "Enter Reely's address, like reely.example.com or 192.168.1.20:8788.")) }
            return
        }
        val client = reelyFor(address)
        _state.update { it.copy(requests = it.requests.copy(connecting = true, error = null)) }
        viewModelScope.launch {
            var problem = client.signIn()
            if (problem == tv.reely.requests.ReelyRequests.PLEX_REJECTED) {
                // plex.tv itself is asked: if it takes this sign-in, the fault is between
                // Reely and plex.tv, and signing in again here wouldn't help.
                val token = _state.value.plex.token
                if (token != null && PlexApi.tokenAccepted(clientId, token) == true) {
                    problem = "Plex accepts this device's sign-in, but not from Reely. Sign out of Plex in Settings " +
                        "and sign in again, then connect. If that doesn't help, the Reely server needs a look."
                }
            }
            if (problem != null) {
                _state.update { it.copy(requests = it.requests.copy(connecting = false, error = problem)) }
                return@launch
            }
            reely = client
            store.put(SecureStore.REELY_URL, client.base)
            _state.update {
                it.copy(requests = RequestsState(server = client.base, connected = true))
            }
            loadRequests()
        }
    }

    fun disconnectReely() {
        store.remove(SecureStore.REELY_URL)
        reely = null
        requestSearchJob?.cancel()
        _state.update { it.copy(requests = RequestsState(), requestDetail = null) }
    }

    fun dismissRequestsError() = _state.update { it.copy(requests = it.requests.copy(error = null)) }

    /** When the Plex libraries' ids were last read, for marking what's already in them. */
    private var plexHoldingsAt = 0L

    /**
     * What the Plex libraries here hold, by outside id, to mark a Reely title In library
     * when it's there. Read at most every ten minutes: it's every title at once.
     */
    private fun refreshPlexHoldings(): kotlinx.coroutines.Job? {
        val now = System.currentTimeMillis()
        if (now - plexHoldingsAt < PLEX_HOLDINGS_MS) return null
        // Only the libraries switched on here: a server's library that has been turned off
        // isn't one this television watches from, so what's in it isn't "in the library".
        val choices = _state.value.plex.homeSources()
        if (choices.isEmpty()) return null
        plexHoldingsAt = now
        return viewModelScope.launch {
            suspend fun idsOf(kind: String, type: Int) = choices.filter { it.section.type == kind }.map { choice ->
                async {
                    runCatching { PlexApi.libraryGuids(choice.baseUrl, choice.token, choice.section.key, type) }
                        .getOrDefault(emptySet())
                }
            }.awaitAll().flatten().toSet()
            val movies = idsOf("movie", 1)
            val shows = idsOf("show", 2)
            _state.update { it.copy(requests = it.requests.copy(plexMovies = movies, plexShows = shows)) }
        }
    }

    /** Reely's discovery rows and this account's requests. */
    fun loadRequests() {
        val client = reely ?: return
        val holdings = refreshPlexHoldings()
        _state.update { it.copy(requests = it.requests.copy(loading = it.requests.rows.isEmpty(), error = null)) }
        viewModelScope.launch {
            val rows = async { runCatching { client.explore() } }
            val mine = async { runCatching { client.myRequests() } }
            val marks = async { runCatching { client.marks() } }
            val found = rows.await()
            // What's in the libraries already, before the rows are shown: titles that are
            // there are left out of them, and mustn't vanish from under the cursor after.
            holdings?.join()
            _state.update { current ->
                current.copy(
                    requests = current.requests.copy(
                        connected = found.isSuccess || current.requests.connected,
                        loading = false,
                        rows = found.getOrElse { current.requests.rows },
                        mine = mine.await().getOrElse { current.requests.mine },
                        marks = marks.await().getOrElse { current.requests.marks },
                        error = found.exceptionOrNull()?.readable(),
                    ),
                )
            }
        }
    }

    /**
     * Whether anything asked for has arrived. The library lists are only fetched while
     * something approved is still waiting, since they're the large ones. The first look
     * takes in what was ready already without saying so: that isn't news.
     */
    private fun checkReadyRequests() {
        val client = reely ?: return
        refreshPlexHoldings()
        viewModelScope.launch {
            val mine = runCatching { client.myRequests() }.getOrNull() ?: return@launch
            val seen = settings.readyRequestsSeen
            if (mine.none { it.status == "approved" && it.title.key !in seen.orEmpty() }) {
                if (seen == null) settings.readyRequestsSeen = emptySet()
                _state.update { it.copy(requests = it.requests.copy(mine = mine)) }
                return@launch
            }
            val marks = runCatching { client.marks() }.getOrNull() ?: return@launch
            val arrived = tv.reely.requests.readyRequests(mine, marks, seen.orEmpty())
            if (seen == null) {
                settings.readyRequestsSeen = arrived.map { it.key }.toSet()
                _state.update { it.copy(requests = it.requests.copy(mine = mine, marks = marks)) }
                return@launch
            }
            _state.update { it.copy(requests = it.requests.copy(mine = mine, marks = marks, ready = arrived)) }
        }
    }

    /** Put away without watching: not said again. */
    fun dismissReady(title: tv.reely.requests.RequestTitle) {
        settings.readyRequestsSeen = settings.readyRequestsSeen.orEmpty() + title.key
        _state.update { it.copy(requests = it.requests.copy(ready = it.requests.ready.filterNot { r -> r.key == title.key })) }
    }

    /**
     * Opens what arrived: its page on the server, found by name, kind and year; or, if the
     * server hasn't picked it up yet under that name, Search with the name typed in.
     */
    fun openReady(title: tv.reely.requests.RequestTitle) {
        dismissReady(title)
        openInPlex(title)
    }

    /** A title Reely knows of, on the server: its page, or Search with its name when not found. */
    fun openInPlex(title: tv.reely.requests.RequestTitle) {
        val plex = _state.value.plex
        val servers = plex.homeSources().map { it.baseUrl to it.token }.distinct().ifEmpty {
            val base = plex.baseUrl
            val token = plex.serverToken
            if (base != null && token != null) listOf(base to token) else emptyList()
        }
        viewModelScope.launch {
            val kind = if (title.isShow) "show" else "movie"
            val found = servers.firstNotNullOfOrNull { (base, token) ->
                runCatching { PlexApi.searchAll(base, token, title.title).items }.getOrNull()
                    ?.firstOrNull { item ->
                        item.type == kind && item.title.equals(title.title, ignoreCase = true) &&
                            (title.year == null || item.year == null || item.year == title.year)
                    }
            }
            if (found != null) {
                navigate(Route.Detail(found.ratingKey, serverBase = found.serverBase))
            } else {
                navigate(Route.Search)
                setQuery(title.title)
            }
        }
    }

    private fun refreshMyRequests() {
        val client = reely ?: return
        viewModelScope.launch {
            val mine = runCatching { client.myRequests() }.getOrNull() ?: return@launch
            val marks = runCatching { client.marks() }.getOrNull()
            _state.update { it.copy(requests = it.requests.copy(mine = mine, marks = marks ?: it.requests.marks)) }
        }
    }

    fun setRequestQuery(query: String) {
        _state.update { it.copy(requests = it.requests.copy(query = query)) }
        requestSearchJob?.cancel()
        val client = reely ?: return
        if (query.isBlank()) {
            _state.update { it.copy(requests = it.requests.copy(results = emptyList(), searching = false)) }
            return
        }
        requestSearchJob = viewModelScope.launch {
            _state.update { it.copy(requests = it.requests.copy(searching = true)) }
            // Typing on a remote is slow; wait for a pause rather than asking per letter.
            delay(400)
            val results = runCatching { client.search(query) }
            _state.update { current ->
                if (current.requests.query != query) current
                else current.copy(
                    requests = current.requests.copy(
                        results = results.getOrElse { emptyList() },
                        searching = false,
                        error = results.exceptionOrNull()?.readable(),
                    ),
                )
            }
        }
    }

    private fun loadRequestTitle(title: tv.reely.requests.RequestTitle) {
        val client = reely ?: return
        _state.update { it.copy(requestDetail = RequestDetailState(title)) }
        viewModelScope.launch {
            val places = async { runCatching { client.places() }.getOrNull() }
            val result = runCatching { client.detail(title) }
            val where = places.await()
            _state.update { current ->
                val page = current.requestDetail?.takeIf { it.title.key == title.key } ?: return@update current
                val detail = result.getOrNull()
                val ready = page.copy(
                    detail = detail,
                    busy = false,
                    error = result.exceptionOrNull()?.readable(),
                    // Every season to start with: asking for a show is usually asking for all of it.
                    chosen = detail?.seasons?.map { it.number }?.toSet().orEmpty(),
                    places = where,
                )
                current.copy(
                    requestDetail = ready.copy(libraryId = ready.addable?.let { where?.preferred(it) }?.id),
                )
            }
        }
    }

    /** In or out of the request, for one season of a show. */
    fun toggleRequestSeason(number: Int) = _state.update { current ->
        val page = current.requestDetail ?: return@update current
        val chosen = if (number in page.chosen) page.chosen - number else page.chosen + number
        current.copy(requestDetail = page.copy(chosen = chosen, outcome = null))
    }

    /** Every season offered, or, when every one is already picked, none. */
    fun toggleAllRequestSeasons() = _state.update { current ->
        val page = current.requestDetail ?: return@update current
        val offered = page.seasonsOffered.map { it.number }.toSet()
        val all = page.detail?.seasons?.map { it.number }?.toSet().orEmpty()
        current.copy(requestDetail = page.copy(chosen = if (page.chosenOffered == offered) page.chosen - offered else all, outcome = null))
    }

    /** Which library the title on the page goes to. */
    fun chooseRequestLibrary(id: Long) = _state.update { current ->
        val page = current.requestDetail ?: return@update current
        current.copy(requestDetail = page.copy(libraryId = id, outcome = null))
    }

    /** Asks Reely for the title on the page: a film, or the seasons picked of a show. */
    fun submitRequest() {
        val client = reely ?: return
        val page = _state.value.requestDetail ?: return
        // Once. A second press while the first was on its way came back "already
        // requested", and that replaced the message saying the first had worked.
        if (page.sending) return
        val detail = page.detail ?: return
        val title = detail.title
        val offered = page.seasonsOffered.map { it.number }.toSet()
        // Every season is the whole show, which also takes in seasons still to come. Of a
        // show partly here, it's the seasons picked: Reely keeps the ones already asked for.
        val seasons = if (!title.isShow || (page.chosenOffered == offered && page.seasonsHere.isEmpty())) null else page.chosenOffered.sorted()
        if (title.isShow && offered.isNotEmpty() && page.chosenOffered.isEmpty()) {
            _state.update { it.copy(requestDetail = page.copy(outcome = "Pick at least one season.")) }
            return
        }
        _state.update { it.copy(requestDetail = page.copy(sending = true, outcome = null)) }
        viewModelScope.launch {
            val library = page.library
            // Who it's for is Reely's to decide: the asker and their groups.
            val outcome = runCatching { client.request(title, seasons, library?.id) }
                .getOrElse { tv.reely.requests.RequestOutcome.Refused(it.readable()) }
            val into = library?.let { " to ${it.name}" }.orEmpty()
            val message = when (outcome) {
                is tv.reely.requests.RequestOutcome.Sent ->
                    if (outcome.approved) "Adding it$into now."
                    else "Requested${library?.let { " for ${it.name}" }.orEmpty()}. You'll see it here once it's approved."
                tv.reely.requests.RequestOutcome.AlreadyRequested -> "This has already been requested."
                is tv.reely.requests.RequestOutcome.Refused -> outcome.message
            }
            _state.update { current ->
                val now = current.requestDetail?.takeIf { it.title.key == page.title.key } ?: return@update current
                current.copy(requestDetail = now.copy(sending = false, outcome = message))
            }
            refreshMyRequests()
        }
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
        play(order.first(), queue = order, resume = !shuffle, playlist = true)
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
        showKeptHomeRows()
        if (base != null && serverToken != null) loadSections() else connectServer(token)
        // Only the chosen server was ever stored, so the rest have to be asked for again
        // before anything can offer to switch to them.
        loadServerList(token)
        loadProfiles(token)
    }

    /** Who is signed in, and who else is in their Plex Home to switch to. */
    private fun loadProfiles(token: String, ask: Boolean = false) {
        viewModelScope.launch {
            val user = runCatching { PlexApi.account(clientId, token) }.getOrNull()
            val home = runCatching { PlexApi.homeUsers(clientId, token) }.getOrElse { emptyList() }
            if (_state.value.plex.token != token) return@launch
            // Signed in as the account's owner, as Plex's own apps are, which then ask who
            // is watching: it went straight on as the owner, and everybody else in the
            // house found the owner's Continue Watching and none of their own.
            updatePlex { it.copy(user = user, homeUsers = home, askWho = ask && home.size > 1) }
        }
    }

    fun askedWho() = updatePlex { it.copy(askWho = false) }

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
            forgetAccount()
            _state.update {
                it.copy(
                    plex = PlexState(token = next, user = target, homeUsers = plex.homeUsers, busy = true),
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
            // IPTV's titles are matched against every library, now they're all known.
            refreshIptvIndex()
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
        forgetAccount()
        _state.update {
            it.copy(
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
                    store.put(SecureStore.PLEX_ACCOUNT_TOKEN, token)
                    updatePlex { it.copy(token = token, linkCode = null, linkUrl = null) }
                    connectServer(token)
                    loadProfiles(token, ask = true)
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
        connectRetry?.cancel()
        updatePlex { it.copy(busy = true, error = null) }
        val servers = runCatching { PlexApi.servers(clientId, token) }.getOrElse { failure ->
            updatePlex { it.copy(busy = false, error = failure.readable()) }
            connectAgainSoon(token)
            return
        }
        if (servers.isEmpty()) {
            updatePlex {
                it.copy(
                    busy = false,
                    error = "This Plex account has no Plex server of its own, and nobody has shared one with it yet.",
                )
            }
            connectAgainSoon(token)
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
        updatePlex { it.copy(busy = false, error = unreachable(servers)) }
        connectAgainSoon(token)
    }

    /**
     * What's said when none of the servers this account can use answered: which they were,
     * and whose. Somebody with only a friend's server shared with them was told to check
     * "your Plex server", as though they had to have one of their own.
     */
    private fun unreachable(servers: List<PlexServer>): String = unreachableMessage(servers)

    /*
     * Signed in, with no server to show. It used to stay that way until the app was
     * started again, or somebody signed in all over again to make it look: now it looks
     * again every little while, and at once from Try again.
     */
    private var connectRetry: Job? = null

    private fun connectAgainSoon(token: String) {
        connectRetry?.cancel()
        connectRetry = viewModelScope.launch {
            delay(HOME_RETRY_MS)
            val plex = _state.value.plex
            if (plex.token == token && !plex.isConnected && !plex.busy) connectServer(token)
        }
    }

    /** Try again, on the screen saying no server could be reached. */
    fun retryConnect() {
        val plex = _state.value.plex
        val token = plex.token ?: return
        if (plex.isConnected || plex.busy) return
        viewModelScope.launch { connectServer(token) }
    }

    /**
     * The server's address kept from last time doesn't answer. Addresses change — a
     * router hands the server a new one, the server moves house — and plex.tv knows its
     * addresses now. So it's looked up again, and if it answers somewhere else, that's
     * where it's used from, and kept. Before, the old address was asked again and again
     * for as long as the app was open, and Home said it was trying.
     */
    private suspend fun findServerAgain(): Boolean {
        val plex = _state.value.plex
        val token = plex.token ?: return false
        val current = plex.baseUrl ?: return false
        val servers = runCatching { PlexApi.servers(clientId, token) }.getOrNull() ?: return false
        if (_state.value.plex.baseUrl != current) return false
        updatePlex { it.copy(servers = servers) }
        val server = servers.firstOrNull { it.accessToken == plex.serverToken }
            ?: servers.firstOrNull { it.name == plex.serverName }
            ?: return false
        val base = PlexApi.firstReachable(server) ?: return false
        if (base == current || _state.value.plex.baseUrl != current) return false
        useServer(server.name, base, server.accessToken)
        return true
    }

    private suspend fun loadSections() {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        updatePlex { it.copy(busy = true, error = null) }
        val sections = runCatching { PlexApi.sections(base, token) }.getOrElse { failure ->
            if (findServerAgain()) return
            updatePlex { it.copy(busy = false, error = failure.readable()) }
            // On Home too, where it's noticed: it was only said in Settings, and Home
            // went on showing what was kept from last time as though all was well.
            _state.update {
                it.copy(home = it.home.copy(busy = false, error = "Couldn't reach your Plex server. Trying again…"))
            }
            retryHomeSoon()
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
        connectRetry?.cancel()
        store.remove(
            SecureStore.PLEX_TOKEN,
            SecureStore.PLEX_ACCOUNT_TOKEN,
            SecureStore.PLEX_SERVER_URI,
            SecureStore.PLEX_SERVER_TOKEN,
            SecureStore.PLEX_SERVER_NAME,
        )
        forgetAccount()
        _state.update { it.copy(plex = PlexState()) }
        // Nobody's rows to show the next person to sign in.
        tv.reely.core.HomeCache.clear(getApplication<Application>().filesDir)
    }

    /**
     * Full-screen artwork for a hero. Asked for at 720 wide rather than 1920: it is drawn
     * behind a heavy scrim, so the extra detail would cost megabytes of bitmap on a stick
     * and never be seen.
     */
    fun plexBackdropUrl(serverBase: String?, path: String?): String? =
        plexImageUrl(serverBase, path, width = 720, height = 405)

    fun toggleWatchedDetail() {
        val page = _state.value.detail ?: return
        val detail = page.detail ?: return
        toggleWatched(detail.asItem(page.serverBase))
    }

    /**
     * Artwork has to be asked of the server that holds it, with that server's token. A row
     * merged from several servers would otherwise draw everything against whichever one
     * happens to be connected, and the rest would come back unauthorised.
     */
    fun plexImageUrl(serverBase: String?, path: String?, width: Int, height: Int): String? {
        // The provider's pictures are whole addresses of their own.
        if (isIptvSource(serverBase)) return path?.takeIf { it.startsWith("http") }
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
    private var homeGeneration = 0

    /** See [forgetAccount]; and anything still on its way for the old one is ignored. */
    private fun forgetAccount() {
        homeGeneration++
        homeRetry?.cancel()
        plexHoldingsAt = 0L
        lastPosition = null
        _state.update { it.forgetAccount() }
        // IPTV's rows aren't the Plex account's: they go back on Home straight away.
        publishIptv()
    }

    /** Home asked for again shortly, while the server isn't answering. */
    private var homeRetry: Job? = null

    private fun retryHomeSoon() {
        if (homeRetry?.isActive == true) return
        homeRetry = viewModelScope.launch {
            delay(HOME_RETRY_MS)
            refreshHome()
        }
    }

    /**
     * Changes sent to Plex go one at a time, in the order they were made. Watched then
     * Unwatch in quick succession could otherwise reach the server the other way round,
     * leaving it the opposite of what the screen shows.
     */
    private val writes = kotlinx.coroutines.sync.Mutex()

    fun refreshHome() {
        // Anything asked for that has arrived, said on Home as it refreshes.
        checkReadyRequests()
        val plex = _state.value.plex
        // The server didn't answer when the app started, so there are no libraries to
        // ask about. Asking for them again is what brings Home back once it's up; before,
        // nothing did, and Home stayed as it was until the app was restarted.
        // Only after asking failed: a server with no libraries at all answers with none,
        // and asking again for ever would be the result.
        if (plex.isConnected && plex.sections.isEmpty() && !plex.busy && plex.error != null) {
            viewModelScope.launch { loadSections() }
            return
        }
        val sources = plex.homeSources().ifEmpty {
            val base = plex.baseUrl ?: return
            val token = plex.serverToken ?: return
            plex.sections.map { LibraryChoice(plex.serverName.orEmpty(), base, token, it) }
        }
        if (sources.isEmpty()) return

        // Several can be under way at once — one after stopping, another after marking
        // something watched — and the one asked for last is the one that counts. An
        // earlier one finishing later would put back rows from before the change.
        val generation = ++homeGeneration
        viewModelScope.launch {
            _state.update { it.copy(home = it.home.copy(busy = true, error = null)) }

            val servers = sources.map { it.baseUrl to it.token }.distinct()
            refreshWatchlist(servers)
            // Reely's rows, when Home shows any of them.
            val hidden = _state.value.prefs.hiddenHomeRows
            if (HomeRow.entries.any { it.fromReely && it.id !in hidden }) loadRequests()
            val playlists = servers.flatMap { (base, token) ->
                runCatching { PlexApi.playlists(base, token) }.getOrElse { emptyList() }
            }

            // The row Plex's own home screen shows, from every server, in order of when
            // each thing was last watched. It used to be ordered by when things were
            // added to the library — see continueWatchingOrder.
            // Whether any server answered at all. None did, and the rows on screen stay as
            // they are with a word about it: replacing them with nothing wiped Home blank
            // on a moment's lost connection, with nothing to say why.
            var answered = false
            val onDeck = continueWatchingOrder(
                servers.flatMap { (base, token) ->
                    runCatching { PlexApi.continueWatching(base, token) }
                        .onSuccess { answered = true }
                        .getOrElse { emptyList() }
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

            if (generation != homeGeneration) return@launch
            if (!answered) {
                _state.update {
                    it.copy(home = it.home.copy(busy = false, error = "Couldn't reach your Plex server. Trying again…"))
                }
                retryHomeSoon()
                return@launch
            }
            homeRetry?.cancel()
            _state.update {
                it.copy(
                    home = HomeState(
                        continueWatching = withIptvContinue(onDeck.map { item -> item.withShowLogo() }),
                        recentEpisodes = recentEpisodes.map { group -> group.copy(newest = group.newest.withShowLogo()) },
                        // Where IPTV's copy has been chosen over Plex's, it's the one shown.
                        recentMovies = recentMovies.filterNot { movie -> iptv.hides(movie, it.prefs.iptvWins) },
                        // Filled in on its own, and not to be lost when the rest comes in.
                        watchlist = it.home.watchlist,
                        playlists = playlists,
                        iptvMovies = it.home.iptvMovies,
                        iptvShows = it.home.iptvShows,
                        busy = false,
                    )
                )
            }
            keepHomeRows()
        }
    }

    /** Whose Home rows these are: the account (or profile) on the server it's using. */
    private fun homeOwner(): String? {
        val plex = _state.value.plex
        val token = plex.token ?: return null
        return token + "|" + plex.baseUrl.orEmpty()
    }

    /** Home as it is now, kept for the next start; see [tv.reely.core.HomeCache]. */
    private fun keepHomeRows() {
        val owner = homeOwner() ?: return
        val home = _state.value.home
        if (home.isEmpty) return
        val rows = tv.reely.core.HomeCache.Rows(
            // Plex's own: IPTV's are put back from this television's own record of them.
            continueWatching = home.continueWatching.filterNot { it.isIptv },
            recentMovies = home.recentMovies,
            recentEpisodes = home.recentEpisodes.map { it.newest to it.count },
        )
        viewModelScope.launch(Dispatchers.IO) { tv.reely.core.HomeCache.save(getApplication<Application>().filesDir, owner, rows) }
    }

    /**
     * Home from last time, shown the moment the app starts while fresh rows are fetched,
     * as long as it's the same account on the same server.
     */
    private suspend fun showKeptHomeRows() {
        val owner = homeOwner() ?: return
        val kept = withContext(Dispatchers.IO) { tv.reely.core.HomeCache.load(getApplication<Application>().filesDir, owner) } ?: return
        _state.update { current ->
            if (!current.home.isEmpty) current
            else current.copy(
                home = current.home.copy(
                    continueWatching = kept.continueWatching,
                    recentMovies = kept.recentMovies,
                    recentEpisodes = kept.recentEpisodes.map { (episode, count) ->
                        EpisodeGroup(
                            showTitle = episode.grandparentTitle ?: episode.title,
                            showRatingKey = episode.grandparentRatingKey ?: episode.ratingKey,
                            thumb = episode.grandparentThumb ?: episode.thumb,
                            newest = episode,
                            count = count,
                            addedAt = episode.addedAt,
                            librarySectionId = episode.librarySectionId,
                            serverBase = episode.serverBase,
                        )
                    },
                ),
            )
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
        if (path == null || isIptvSource(serverBase)) return null
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
        // What counts as in the library has changed with it.
        plexHoldingsAt = 0L
        refreshPlexHoldings()
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
        // Asked whatever the order: the letters' counts add up to the whole, for the count
        // over the grid. The rail itself is only for title order, the one they're in.
        val titleOrder = browse.sort == LibrarySort.TITLE
        updateBrowse(kind) { it.copy(letters = if (titleOrder) it.letters else emptyList(), total = null) }
        viewModelScope.launch {
            val letters = runCatching {
                PlexApi.firstCharacters(base, token, section.key, kind.filter, browseFilters(browse))
            }.getOrNull()
            updateBrowse(kind) {
                // Only if nothing has changed the grid's order or filters meanwhile.
                val same = it.section?.key == section.key && it.sort == browse.sort &&
                    browseFilters(it) == browseFilters(browse)
                if (same) it.copy(letters = if (titleOrder) letters.orEmpty() else emptyList(), total = letters?.sumOf { l -> l.count }) else it
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

    /** The page showing, asked for again after it couldn't be loaded. */
    fun retryDetail() {
        (_state.value.route as? Route.Detail)?.let(::loadDetail)
    }

    private fun loadDetail(route: Route.Detail) {
        if (isIptvSource(route.serverBase)) {
            loadIptvDetail(route)
            return
        }
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

        // Only onto this title's page. Leaving it quickly for another, the answer for this
        // one could arrive late and fill the other page with it.
        // The same number on another server is another title's page.
        fun ReelyState.isThisPage() = detail?.ratingKey == ratingKey && detail.serverBase == route.serverBase
        fun onThisPage(change: (DetailState) -> DetailState) = _state.update { current ->
            val page = current.detail
            if (page == null || !current.isThisPage()) current else current.copy(detail = change(page))
        }
        viewModelScope.launch {
            val detail = runCatching { PlexApi.detail(base, token, ratingKey) }.getOrElse { failure ->
                onThisPage { it.copy(busy = false, error = failure.readable()) }
                return@launch
            }
            if (detail == null) {
                onThisPage { it.copy(busy = false, error = "This title isn't available.") }
                return@launch
            }
            if (!_state.value.isThisPage()) return@launch

            onThisPage { it.copy(detail = detail, busy = detail.isShow) }
            startTheme()

            launch {
                val trailers = runCatching { PlexApi.trailers(base, token, ratingKey) }
                    .getOrElse { emptyList() }
                _state.update { current ->
                    if (current.detail == null || !current.isThisPage()) current
                    else current.copy(detail = current.detail.copy(trailers = trailers))
                }
            }
            if (detail.type == "collection") launch {
                val members = runCatching { PlexApi.collectionItems(base, token, ratingKey) }
                    .getOrElse { emptyList() }
                _state.update { current ->
                    if (current.detail == null || !current.isThisPage()) current
                    else current.copy(detail = current.detail.copy(members = members))
                }
            }
            if (detail.type != "collection") launch {
                val related = runCatching { PlexApi.related(base, token, ratingKey) }
                    .getOrElse { emptyList() }
                _state.update { current ->
                    if (current.detail == null || !current.isThisPage()) current
                    else current.copy(detail = current.detail.copy(related = related))
                }
            }

            if (!detail.isShow) return@launch

            val seasons = runCatching { PlexApi.children(base, token, ratingKey) }
                .getOrElse { emptyList() }
                .filter { it.type == "season" }
            if (!_state.value.isThisPage()) return@launch
            onThisPage { it.copy(seasons = seasons, busy = seasons.isNotEmpty()) }

            // Arriving from a row means arriving at one episode, not at the top of the show.
            // Arriving at the show itself means arriving where it's up to: the season and
            // episode to watch next, as Plex's own apps open a show. It opened on the first
            // season listed — Specials, as often as not — and Play started that.
            // The server says which with the page itself; failing that, the first season
            // that isn't Specials.
            val upTo = detail.onDeckKey.takeIf { route.seasonKey == null }
            val season = seasons.firstOrNull { it.ratingKey == route.seasonKey }
                ?: upTo?.let { seasons.firstOrNull { it.ratingKey == detail.onDeckSeasonKey } }
                ?: seasons.firstOrNull { (it.index ?: 0) > 0 }
                ?: seasons.firstOrNull()
            if (season == null) {
                onThisPage { it.copy(busy = false) }
            } else {
                selectSeason(season, focusEpisodeKey = route.episodeKey ?: upTo)
            }
        }
    }

    private var seasonJob: Job? = null

    fun selectSeason(season: PlexItem, focusEpisodeKey: String? = null) {
        if (season.isIptv) {
            selectIptvSeason(season, focusEpisodeKey)
            return
        }
        val plex = _state.value.plex
        val on = season.serverBase ?: _state.value.detail?.serverBase
        val base = on ?: plex.baseUrl ?: return
        val token = plex.tokenFor(on) ?: return
        val page = _state.value.detail ?: return
        // The season chosen last is the one whose episodes show. Moving along the seasons
        // quickly, an earlier one's episodes could arrive after a later one's and show
        // under it.
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
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
            // Still this page, still this season: not a season with the same number on
            // another server's page opened since.
            val now = _state.value.detail
            if (now?.selectedSeason?.ratingKey != season.ratingKey || now.ratingKey != page.ratingKey ||
                now.serverBase != page.serverBase
            ) return@launch
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
    /** A show's or season's next episode, from its menu: see [PlexApi.nextEpisode]. */
    fun playNextEpisode(item: PlexItem) {
        if (item.isIptv) {
            playNextIptvEpisode(item)
            return
        }
        val plex = _state.value.plex
        val base = item.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(item.serverBase) ?: return
        viewModelScope.launch {
            val episodes = runCatching { PlexApi.episodesOf(base, token, item.ratingKey) }
                .getOrElse { failure ->
                    reportPlaybackProblem(failure.readable())
                    return@launch
                }
            val next = PlexApi.nextEpisode(episodes) ?: run {
                reportPlaybackProblem("There are no episodes to play.")
                return@launch
            }
            play(next, queue = episodes)
        }
    }

    fun playFromDetail(resume: Boolean = true) {
        val detailState = _state.value.detail ?: return
        val detail = detailState.detail ?: return
        if (detail.isShow) {
            val episode = detailState.episodes.firstOrNull { it.resumeFraction != null }
                ?: detailState.episodes.firstOrNull()
                ?: return
            play(episode, queue = detailState.episodes, resume = resume)
        } else {
            play(detail.asItem(detailState.serverBase), resume = resume, mediaIndex = detailState.versionIndex)
        }
    }

    /**
     * The account's Watchlist, and which of it the servers here have, in the Watchlist's
     * own order. Something no server has can't be opened or played, so it isn't shown.
     */
    private fun refreshWatchlist(servers: List<Pair<String, String>>) {
        val token = _state.value.plex.token ?: return
        val edits = watchlistEdits
        viewModelScope.launch {
            val guids = runCatching { PlexApi.watchlist(token) }.getOrElse { return@launch }
            // Not over a change made since it was asked, which it can't know about: the
            // button flipped back. Nor for a profile that's no longer the one signed in.
            if (_state.value.plex.token != token) return@launch
            if (watchlistEdits == edits) {
                _state.update { it.copy(plex = it.plex.copy(watchlist = guids.toSet())) }
            }
            val found = guids.take(WATCHLIST_ROW).chunked(8).flatMap { batch ->
                batch.map { guid ->
                    async {
                        servers.firstNotNullOfOrNull { (base, serverToken) ->
                            runCatching { PlexApi.byGuid(base, serverToken, guid) }.getOrNull()
                        }
                    }
                }.map { it.await() }
            }.filterNotNull()
            if (_state.value.plex.token != token) return@launch
            _state.update { it.copy(home = it.home.copy(watchlist = found)) }
        }
    }

    /** Changes made to the Watchlist here, so a list read before one isn't put over it. */
    private var watchlistEdits = 0

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
        watchlistEdits++
        mark(on)
        viewModelScope.launch {
            writes.withLock { runCatching { PlexApi.setWatchlisted(token, guid, on) } }
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
        playlist: Boolean = false,
    ): Job? {
        // The next episode taking over from one still playing — Up Next, its countdown,
        // the skip button. The one finishing is told to Plex as stopped where it got to,
        // and its tick and progress change on the pages that show it. Without this it was
        // left playing as far as the server knew, and ticked on none of them.
        _state.value.playback
            ?.takeIf { !it.isLive && it.ratingKey != null && !_state.value.isPlaying(it, item) }
            ?.let { finishing -> endSitting(finishing, lastPositionOf(finishing)) }

        if (item.isIptv) return playIptv(item, queue, resume)
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
                                subtitles = startsWithServerSubtitles(resolved.subtitleStreams, it.prefs.subtitlesAtStart),
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
                        partId = resolved.partId,
                        audioStreams = resolved.audioStreams,
                        subtitleStreams = resolved.subtitleStreams,
                        serverBase = on,
                        queue = effectiveQueue,
                        queueIndex = effectiveQueue.indexOfFirst { entry -> entry.ratingKey == item.ratingKey },
                        playlist = playlist,
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
        val state = _state.value
        val episodes = state.detail?.episodes.orEmpty()
        return if (episodes.any { state.sameTitle(it, item) }) episodes else emptyList()
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
                if (!current.isPlaying(playback, item)) return@update current
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
        // The end of a programme watched behind live. Still behind — paused across the
        // change of programme, say — the next one carries on from the archive; caught up
        // with now, the channel live again. Going straight to live skipped whatever there
        // was in between.
        playback.timeshift?.let { timeshift ->
            val next = timeshift.stop * 1000
            val behind = next < System.currentTimeMillis() - LIVE_CATCH_UP_MS
            if (!behind || !timeshiftTo(next)) goLive()
            return
        }
        // The sleep timer said this episode was the last: out, with no Up Next.
        if (_state.value.sleep?.endOfEpisode == true) {
            finishAtEnd(playback)
            return
        }
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
        val state = _state.value
        val finished = playback.queue.firstOrNull { state.isPlaying(playback, it) }
            ?: state.detail?.episodes?.firstOrNull { state.isPlaying(playback, it) }
        val show = finished?.grandparentRatingKey?.takeIf { finished.type == "episode" }
        if (finished != null && show != null) {
            if (!landOnEpisode(finished)) {
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
        if (playback.fromIptv) {
            iptvPlaying?.takeIf { it.ratingKey == playback.ratingKey }?.let { iptv.watch?.setWatched(it, true) }
            refreshIptvContinue()
        }
        playback.ratingKey?.let { applyWatched(it, watched = true, server = playback.serverBase) }
    }

    /**
     * The credits have started. Up Next is offered now rather than at the very end, the
     * way Plex does it; the end of the file offers it again to anybody who chose to
     * watch them.
     */
    fun onCreditsReached() {
        if (_state.value.upNext != null) return
        // Asleep at the end of this one: the credits run, and nothing's offered after.
        if (_state.value.sleep?.endOfEpisode == true) return
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
        // A playlist ends where it does.
        if (playback.playlist) return null
        if (playback.fromIptv) return firstOfNextIptvSeason(playback)
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
        val inQueue = queue.any { _state.value.sameTitle(it, next) }
        val job = play(
            next,
            queue = if (inQueue) queue else emptyList(),
            playlist = inQueue && _state.value.playback?.playlist == true,
        )
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

    // ---------------------------------------------------------------- Reminders

    /**
     * Keeps the list, and waits for the first of them. When it starts it's said, on
     * whatever screen is up; then the wait moves on to the next.
     */
    private fun setReminders(list: List<tv.reely.core.Reminder>) {
        val upcoming = tv.reely.core.Reminders.upcoming(list, System.currentTimeMillis() / 1000)
        settings.reminders = tv.reely.core.Reminders.encode(upcoming)
        _state.update { it.copy(reminders = upcoming) }
        reminderJob?.cancel()
        val next = upcoming.firstOrNull() ?: return
        reminderJob = viewModelScope.launch {
            delay((next.start * 1000 - System.currentTimeMillis()).coerceAtLeast(0))
            _state.update { it.copy(dueReminder = next) }
            setReminders(_state.value.reminders.filterNot { it.key == next.key })
        }
    }

    /** Whether there's a reminder for [programme] on [channel]. */
    fun hasReminder(channel: XtreamChannel, start: Long): Boolean =
        _state.value.reminders.any { it.streamId == channel.streamId && it.start == start }

    /** A reminder for a programme still to come, or none if there was one. */
    fun toggleReminder(channel: XtreamChannel, programme: tv.reely.xtream.EpgProgramme) {
        if (programme.start <= System.currentTimeMillis() / 1000) return
        val reminder = tv.reely.core.Reminder(channel.streamId, channel.name, programme.title, programme.start)
        val list = _state.value.reminders
        setReminders(if (list.any { it.key == reminder.key }) list.filterNot { it.key == reminder.key } else list + reminder)
    }

    fun dismissReminder() = _state.update { it.copy(dueReminder = null) }

    /**
     * Back to the app after a while away. A reminder that came due meanwhile would still
     * say its programme was starting, hours later; one that far gone goes unsaid.
     */
    fun dropStaleReminder() = _state.update { current ->
        val due = current.dueReminder ?: return@update current
        if (tv.reely.core.Reminders.stillWorthSaying(due, System.currentTimeMillis() / 1000)) current
        else current.copy(dueReminder = null)
    }

    /** The channel of the programme starting, from wherever the reminder was said. */
    fun watchReminder() {
        val due = _state.value.dueReminder ?: return
        _state.update { it.copy(dueReminder = null) }
        val channel = _state.value.live.channels.firstOrNull { it.streamId == due.streamId }
            ?: allChannels?.firstOrNull { it.streamId == due.streamId }
        if (channel != null) {
            tuneChannel(channel)
            return
        }
        // Not in hand yet: the whole list, then the channel.
        val credentials = _state.value.live.credentials ?: return
        viewModelScope.launch {
            val all = allChannels ?: runCatching { XtreamApi.liveChannels(credentials) }.getOrNull()
                ?.also { allChannels = it } ?: return@launch
            all.firstOrNull { it.streamId == due.streamId }?.let(::tuneChannel)
        }
    }

    // ---------------------------------------------------------------- Finding subtitles

    /** Subtitles online for what's playing, in the television's language. */
    fun searchSubtitles() {
        val playback = _state.value.playback ?: return
        val ratingKey = playback.ratingKey ?: return
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
        val language = java.util.Locale.getDefault().language.ifBlank { "en" }
        _state.update { it.copy(subtitleSearch = SubtitleSearch(language)) }
        viewModelScope.launch {
            val found = runCatching { PlexApi.searchSubtitles(base, token, ratingKey, language) }
            _state.update { current ->
                val search = current.subtitleSearch ?: return@update current
                current.copy(
                    subtitleSearch = search.copy(
                        busy = false,
                        results = found.getOrElse { emptyList() },
                        error = found.exceptionOrNull()?.let { "Couldn't look for subtitles. Try again." },
                    ),
                )
            }
        }
    }

    fun closeSubtitleSearch() = _state.update { it.copy(subtitleSearch = null) }

    /**
     * Has the server fetch [subtitle] and add it to the file, then plays with it on: the
     * file's subtitles are read again, and the new one is picked and remembered.
     */
    fun addSubtitle(subtitle: tv.reely.plex.PlexOnlineSubtitle) {
        val playback = _state.value.playback ?: return
        val ratingKey = playback.ratingKey ?: return
        val search = _state.value.subtitleSearch ?: return
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
        _state.update { it.copy(subtitleSearch = search.copy(adding = subtitle.key, error = null)) }
        viewModelScope.launch {
            val added = PlexApi.addSubtitle(base, token, ratingKey, subtitle, search.language)
            val fresh = if (added) {
                runCatching { PlexApi.playback(base, token, ratingKey, playback.mediaIndex) }.getOrNull()
            } else null
            val before = playback.subtitles.map { it.id }.toSet()
            val newOne = fresh?.subtitles?.firstOrNull { it.id !in before }
            if (fresh == null || newOne == null) {
                _state.update { current ->
                    current.copy(
                        subtitleSearch = current.subtitleSearch?.copy(
                            adding = null,
                            error = "Plex couldn't add those subtitles. Try another.",
                        ),
                    )
                }
                return@launch
            }
            _state.update { current ->
                val now = current.playback?.takeIf { it.url == playback.url } ?: return@update current
                current.copy(
                    subtitleSearch = null,
                    playback = now.copy(
                        subtitles = fresh.subtitles,
                        subtitleStreams = fresh.subtitleStreams.map { it.copy(selected = it.id == newOne.id) },
                    ),
                )
            }
            saveStreamChoice(subtitleStreamId = newOne.id)
        }
    }

    /** The sleep timer: [minutes] from now, the end of this episode (0), or off (null). */
    fun setSleepTimer(minutes: Int?) {
        val timer = when {
            minutes == null -> null
            minutes == 0 -> SleepTimer(endOfEpisode = true)
            else -> SleepTimer(atMs = System.currentTimeMillis() + minutes * 60_000L)
        }
        _state.update { it.copy(sleep = timer) }
    }

    /**
     * Tell Plex where we are. Without this the server never learns anything was watched,
     * and Continue Watching on every device stays wrong.
     */
    fun reportProgress(positionMs: Long, playing: Boolean, durationMs: Long = 0) {
        val playback = _state.value.playback ?: return
        val ratingKey = playback.ratingKey ?: return
        if (playback.fromIptv) {
            // The provider isn't told anything; this television keeps where it's up to.
            if (durationMs > 0) iptvDurationMs = durationMs
            lastPosition = titleOf(playback) to positionMs
            keepIptvProgress(playback, positionMs)
            return
        }
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
        val session = sessionFor(playback)
        lastPosition = titleOf(playback) to positionMs
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

    /**
     * Saves a sound or subtitle choice made in the player to Plex, so the next episode,
     * and any other Plex app, starts with it. Subtitles off is stream "0".
     */
    fun saveStreamChoice(audioStreamId: String? = null, subtitleStreamId: String? = null) {
        val playback = _state.value.playback ?: return
        val partId = playback.partId ?: return
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
        viewModelScope.launch {
            writes.withLock { runCatching { PlexApi.selectStream(base, token, partId, audioStreamId, subtitleStreamId) } }
        }
    }

    /** The identifier for the sitting this item is part of, minted when the item opens. */
    private fun sessionFor(playback: Playback): String {
        val title = titleOf(playback)
        if (timelineSessionFor != title) {
            timelineSession = UUID.randomUUID().toString()
            timelineSessionFor = title
        }
        return timelineSession
    }

    /** What's playing, told apart from the same number on another server. */
    private fun titleOf(playback: Playback): String =
        (playback.serverBase ?: _state.value.plex.baseUrl).orEmpty() + "|" + playback.ratingKey.orEmpty()

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
                        subtitles = startsWithServerSubtitles(playback.subtitleStreams, it.prefs.subtitlesAtStart),
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

    /**
     * The show's page underneath, pointed at [episode]: the one just watched, which Up
     * Next may have moved on from the one the page was opened at. False when the page
     * underneath isn't that show's.
     */
    private fun landOnEpisode(episode: PlexItem): Boolean {
        val show = episode.grandparentRatingKey?.takeIf { episode.type == "episode" } ?: return false
        val route = _state.value.route
        val detail = _state.value.detail
        if (route !is Route.Detail || route.ratingKey != show || detail == null) return false
        // The same number on another server is another show.
        if (!_state.value.sameServer(route.serverBase, episode.serverBase)) return false
        val inRail = detail.episodes.firstOrNull { it.ratingKey == episode.ratingKey }
        val season = detail.seasons.firstOrNull { it.ratingKey == episode.parentRatingKey }
        when {
            inRail != null -> _state.update { it.copy(detail = it.detail?.copy(focusedEpisode = inRail)) }
            season != null -> selectSeason(season, focusEpisodeKey = episode.ratingKey)
        }
        return true
    }

    /**
     * Where something was stopped, on the pages already showing it: the show's page it
     * goes back to, a film's own page. They were loaded before it played, so Resume there
     * went back to where the last sitting had started rather than where this one ended.
     * Past the point Plex counts it as watched, it's watched and starts from the top.
     */
    private fun keepProgress(ratingKey: String, positionMs: Long, durationMs: Long, server: String?) =
        _state.update { it.withProgress(ratingKey, positionMs, durationMs, server) }

    /** Where the player last said it was, for the thing it said it about. */
    private var lastPosition: Pair<String, Long>? = null

    private fun lastPositionOf(playback: Playback): Long =
        lastPosition?.takeIf { it.first == titleOf(playback) }?.second ?: 0L

    /**
     * The end of watching one thing: its resume point and tick on the pages showing it,
     * and Plex told it stopped there. After any report still on its way, since one sent a
     * moment earlier and arriving after this would put Plex back up to ten seconds.
     */
    private fun endSitting(playback: Playback, positionMs: Long) {
        val ratingKey = playback.ratingKey ?: return
        if (positionMs <= 0) return
        if (playback.fromIptv) {
            keepIptvProgress(playback, positionMs)
            keepProgress(ratingKey, positionMs, iptvDurationMs.takeIf { it > 0 } ?: playback.durationMs, IPTV_SOURCE)
            refreshIptvContinue()
            lastPosition = null
            return
        }
        keepProgress(ratingKey, positionMs, playback.durationMs, playback.serverBase)
        val plex = _state.value.plex
        val base = playback.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(playback.serverBase) ?: return
        val session = sessionFor(playback)
        val earlier = timelineJob
        // Its own job: the next episode's first report cancels the one before it, and
        // must not take this with it.
        viewModelScope.launch {
            earlier?.cancelAndJoin()
            runCatching {
                PlexApi.reportTimeline(
                    base = base,
                    token = token,
                    ratingKey = ratingKey,
                    positionMs = positionMs,
                    durationMs = playback.durationMs,
                    state = "stopped",
                    sessionId = session,
                )
            }
            refreshHome()
        }
        lastPosition = null
    }

    fun stopPlayback(positionMs: Long = 0) {
        // Back to the show's page on the episode that was playing, not the one it opened at.
        _state.value.playback?.takeIf { !it.isLive }?.let { playing ->
            playing.queue.firstOrNull { _state.value.isPlaying(playing, it) }?.let(::landOnEpisode)
        }
        releaseTranscode()
        if (_state.value.playback?.isLive == true) livePlayer.stop()
        _state.update { it.copy(multiview = emptyList()) }
        _state.value.playback?.let { endSitting(it, positionMs) }
        // A later play of the same thing is a new sitting, so it gets a new identifier.
        timelineSessionFor = null
        _state.update { it.copy(playback = null, upNext = null, sleep = null) }
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

    fun setAccent(id: String) {
        val choice = tv.reely.ui.theme.AccentChoice.of(id)
        settings.accent = choice.id
        tv.reely.ui.theme.useAccent(choice.id)
        _state.update { it.copy(prefs = it.prefs.copy(accent = choice.id)) }
    }

    fun setSubtitlesAtStart(value: String) {
        settings.subtitlesAtStart = value
        _state.update { it.copy(prefs = it.prefs.copy(subtitlesAtStart = value)) }
    }

    // ---------------------------------------------------------------- Search

    fun setQuery(query: String) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    search = it.search.copy(
                        results = emptyList(), more = emptyList(), channels = emptyList(),
                        people = emptyList(), collections = emptyList(), busy = false,
                    )
                )
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
            var answered = servers.isEmpty()
            val found = servers.map { (base, token) ->
                runCatching { PlexApi.searchAll(base, token, query) }
                    .onSuccess { answered = true }
                    .getOrElse { tv.reely.plex.PlexFound() }
            }
            // IPTV's films and series, when switched on, matched here from the list in hand.
            // A title in both is the winner's copy only.
            val wins = _state.value.prefs.iptvWins
            val fromIptv = if (_state.value.iptv.on) {
                withContext(Dispatchers.Default) { iptv.search(query, wins) }
            } else emptyList()
            if (fromIptv.isNotEmpty()) answered = true
            val (matches, others) = tv.reely.core.SearchMatch.split(
                query,
                found.flatMap { it.items }.filterNot { iptv.hides(it, wins) } + fromIptv,
            )
            // Nothing by name, a misspelling most likely: then Plex's own guesses are the results.
            val results = matches.ifEmpty { others }
            val more = if (matches.isEmpty()) emptyList() else others
            val people = found.flatMap { it.people }.distinctBy { it.name.lowercase() }.take(PEOPLE_RESULTS)
            val collections = found.flatMap { it.collections }.distinctBy { it.title.lowercase() }
            // Channels are matched here rather than asked of the panel: the panel has no
            // search, and the whole list is already in hand.
            val channels = allChannels.orEmpty()
                .filter { it.name.contains(query.trim(), ignoreCase = true) }
                .take(CHANNEL_RESULTS)
            _state.update { current ->
                if (current.search.query != query) current
                else current.copy(
                    search = current.search.copy(
                        results = results, more = more, channels = channels, people = people,
                        collections = collections, busy = false, unreachable = !answered,
                    )
                )
            }
        }
    }

    /**
     * Keeps what's in the search box as a recent search. Called when something it found is
     * opened: that's a search that worked, and the one worth offering again. What was only
     * typed on the way to it isn't.
     */
    fun rememberSearch() {
        val next = tv.reely.core.rememberedSearches(settings.recentSearches, _state.value.search.query)
        settings.recentSearches = next
        _state.update { it.copy(search = it.search.copy(recent = next)) }
    }

    fun clearRecentSearches() {
        settings.recentSearches = emptyList()
        _state.update { it.copy(search = it.search.copy(recent = emptyList())) }
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
     * The channel with this number, if there is one: from the category open first, then
     * from the provider's whole list once it's in hand.
     */
    fun channelNumbered(number: Int): XtreamChannel? =
        _state.value.live.channels.firstOrNull { it.number == number }
            ?: allChannels?.firstOrNull { it.number == number }

    /**
     * Tunes to a channel typed by number. Within the open category it plays as if chosen
     * there, so channel up and down carry on from it.
     */
    fun tuneChannel(channel: XtreamChannel) {
        val index = _state.value.live.channels.indexOfFirst { it.streamId == channel.streamId }
        if (index >= 0) playChannel(index) else playSearchChannel(channel)
    }

    /**
     * Plays a channel found by search. It may not be in the category currently open, in
     * which case there is nothing to surf through and the skip buttons stay quiet.
     */
    fun playSearchChannel(channel: XtreamChannel) {
        val live = _state.value.live
        val credentials = live.credentials ?: return
        val index = live.channels.indexOfFirst { it.streamId == channel.streamId }
        rememberChannel(channel)
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
        val focused = _state.value.focused
        if (focused?.ratingKey == item?.ratingKey && focused?.serverBase == item?.serverBase) return
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
        if (item.isIptv) {
            toggleIptvWatched(item)
            return
        }
        val plex = _state.value.plex
        val base = item.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(item.serverBase) ?: return
        val watched = !item.isWatched

        applyWatched(item.ratingKey, watched, item.serverBase)
        viewModelScope.launch {
            val ok = writes.withLock { runCatching { PlexApi.setWatched(base, token, item.ratingKey, watched) }.isSuccess }
            if (!ok) {
                applyWatched(item.ratingKey, !watched, item.serverBase)
                reportPlaybackProblem("Couldn't mark that as ${if (watched) "watched" else "unwatched"}.")
            } else {
                refreshHome()
            }
        }
    }

    /** Off Continue Watching, here and on the server; put back if the server says no. */
    fun removeFromContinueWatching(item: PlexItem) {
        if (item.isIptv) {
            iptv.watch?.forgetProgress(item.ratingKey)
            keepProgress(item.ratingKey, 0, item.durationMs, IPTV_SOURCE)
            refreshIptvContinue()
            return
        }
        val plex = _state.value.plex
        val base = item.serverBase ?: plex.baseUrl ?: return
        val token = plex.tokenFor(item.serverBase) ?: return
        val before = _state.value.home.continueWatching
        _state.update { it.copy(home = it.home.copy(continueWatching = before.filterNot { entry -> entry.listKey == item.listKey })) }
        viewModelScope.launch {
            val ok = writes.withLock { runCatching { PlexApi.removeFromContinueWatching(base, token, item.ratingKey) }.isSuccess }
            if (!ok) {
                _state.update { it.copy(home = it.home.copy(continueWatching = before)) }
                reportPlaybackProblem("Couldn't remove that from Continue Watching.")
            }
        }
    }

    /** Patches the tick everywhere the same item is on screen. */
    private fun applyWatched(ratingKey: String, watched: Boolean, server: String?) =
        _state.update { it.withWatched(ratingKey, watched, server) }

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
        startIptvLibrary()
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
        updateLive { it.copy(selectedCategory = null, channels = emptyList(), focusedChannel = null, busy = false) }
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
                else if (category.id == LiveState.RECENT.id) recentChannelList(credentials)
                else XtreamApi.liveChannels(credentials, category.id)
            }.getOrElse { failure ->
                updateLive { if (it.selectedCategory?.id == category.id) it.copy(busy = false, error = failure.readable()) else it }
                return@launch
            }
            // Only if it's still the category open. Picking another quickly, or going back
            // to the categories, this one's channels arrived late and filled the other's
            // guide with them.
            if (_state.value.live.selectedCategory?.id != category.id) return@launch
            updateLive { it.copy(busy = false, channels = channels) }
            _state.update { it.copy(guide = it.guide.copy(channelIndex = 0)) }
            loadGuideWindow()
            channels.firstOrNull()?.let { focusChannel(it) }
        }
    }

    /** The favorite channels, in the provider's own order, from its whole list. */
    /** The channels last tuned to, the latest first, as far as the provider still has them. */
    private suspend fun recentChannelList(credentials: XtreamCredentials): List<XtreamChannel> {
        val all = allChannels ?: XtreamApi.liveChannels(credentials).also { allChannels = it }
        val byId = all.associateBy { it.streamId }
        return _state.value.live.recent.mapNotNull { byId[it] }
    }

    /** Puts a channel at the top of Recently watched. */
    private fun rememberChannel(channel: XtreamChannel) {
        val next = (listOf(channel.streamId) + _state.value.live.recent.filter { it != channel.streamId })
            .take(LiveState.RECENT_LIMIT)
        settings.recentChannels = next
        updateLive { it.copy(recent = next) }
    }

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
        rememberChannel(channel)
        // For typing a channel number, which can be any channel, not only this category's.
        primeChannelIndex()
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
            play(next, queue = playback.queue, playlist = playback.playlist)
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

    /** The channel the player itself is on, when live: the one the rest sit beside. */
    private fun mainChannelId(): Int? {
        val state = _state.value
        val playback = state.playback?.takeIf { it.isLive } ?: return null
        return state.live.channels.getOrNull(playback.channelIndex)?.streamId
            // Tuned from search or by number, outside the open category: every tune is
            // remembered first in Recently watched, so that is the one.
            ?: state.live.recent.firstOrNull()
    }

    /**
     * Keeps the channels up now, in the places they're in ([order] is the tiles left to
     * right: 0 the main one, then the others by their place in [ReelyState.multiview]).
     * There's one saved set: saving again replaces it.
     */
    fun saveMultiview(order: List<Int>) {
        val state = _state.value
        val playback = state.playback?.takeIf { it.isLive } ?: return
        val main = mainChannelId() ?: return
        val set = order.mapNotNull { tile ->
            if (tile == 0) tv.reely.core.SavedChannel(main, playback.title)
            else state.multiview.getOrNull(tile - 1)?.let { tv.reely.core.SavedChannel(it.streamId, it.name) }
        }.distinctBy { it.streamId }
        if (set.size < 2) return
        settings.savedMultiview = set
        _state.update { it.copy(savedMultiview = set) }
    }

    /** What to call the saved set in the menu, or null when it's what is already up. */
    fun savedMultiviewLabel(): String? {
        val state = _state.value
        val saved = state.savedMultiview.takeIf { it.size > 1 } ?: return null
        val up = listOfNotNull(mainChannelId()) + state.multiview.map { it.streamId }
        if (saved.map { it.streamId }.toSet() == up.toSet()) return null
        val first = saved.first().name.ifBlank { "Channel" }
        return if (saved.size == 2) "$first and 1 more" else "$first and ${saved.size - 1} more"
    }

    /** Puts the saved channels up, the main one in the player and the rest beside it. */
    fun openSavedMultiview() {
        val saved = _state.value.savedMultiview.takeIf { it.size > 1 } ?: return
        val credentials = _state.value.live.credentials ?: return
        viewModelScope.launch {
            val known = _state.value.live.channels + (
                allChannels ?: runCatching { XtreamApi.liveChannels(credentials) }.getOrNull()?.also { allChannels = it }
                    .orEmpty()
                )
            // A channel the provider has since dropped is left out rather than failing the lot.
            val channels = saved.mapNotNull { s -> known.firstOrNull { it.streamId == s.streamId } }
            if (channels.isEmpty()) return@launch
            _state.update { it.copy(multiview = channels.drop(1).take(MAX_EXTRA_TILES)) }
            tuneChannel(channels.first())
        }
    }

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
        // The window asked for last is the one shown. Moving along the hours quickly, an
        // earlier window's programmes could arrive after a later one's and fill the grid
        // with hours it wasn't showing.
        guideWindowJob?.cancel()
        guideWindowJob = viewModelScope.launch {
            val programmes = withContext(Dispatchers.IO) {
                epgStore.programmes(channelIds, guide.windowStart, guide.windowEnd)
            }
            _state.update {
                val same = it.guide.windowStart == guide.windowStart && it.guide.windowEnd == guide.windowEnd
                if (same) it.copy(guide = it.guide.copy(programmes = programmes)) else it
            }
        }
    }

    private var guideWindowJob: Job? = null

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

    /**
     * OK in the guide: a programme that has been on, on a channel that keeps them, plays
     * from its start; anything else plays the channel live.
     */
    fun guidePlaySelected() {
        val guide = _state.value.guide
        val channel = _state.value.live.channels.getOrNull(guide.channelIndex)
        val listing = channel?.epgChannelId?.let { guide.programmes[it] }.orEmpty()
        val past = channel?.let {
            tv.reely.xtream.catchUpProgramme(it, listing, guide.focusTime, System.currentTimeMillis() / 1000)
        }
        if (channel != null && past != null) playCatchUp(channel, past) else playChannel(guide.channelIndex)
    }

    /**
     * What's on the channel playing, when it can be started again from the beginning: a
     * channel with an archive, and a programme it still holds. Read from the guide, or
     * the panel's own now-and-next where the guide hasn't got it.
     */
    fun startOverProgramme(): Pair<XtreamChannel, tv.reely.xtream.EpgProgramme>? =
        if (_state.value.playback?.isLive == true) rewindableAt(System.currentTimeMillis() / 1000) else null

    /**
     * The live channel playing, and its programme at [at] (epoch seconds), when the
     * channel's archive still holds that. Read from the guide, or the panel's own
     * now-and-next where the guide hasn't got it.
     */
    private fun rewindableAt(at: Long): Pair<XtreamChannel, tv.reely.xtream.EpgProgramme>? {
        val state = _state.value
        val playback = state.playback ?: return null
        // Live, the channel playing; behind live, the one it was rewound from.
        val channel = if (playback.isLive) {
            state.live.channels.getOrNull(playback.channelIndex)
        } else {
            val id = playback.timeshift?.streamId ?: return null
            state.live.channels.firstOrNull { it.streamId == id } ?: allChannels?.firstOrNull { it.streamId == id }
        } ?: return null
        val now = System.currentTimeMillis() / 1000
        val from = channel.catchUpFrom(now) ?: return null
        val listed = channel.epgChannelId?.let { state.guide.programmes[it] }.orEmpty()
            .firstOrNull { it.isOnAt(at) }
        val programme = listed ?: state.live.nowNext(channel.streamId)
            .firstOrNull { at in it.startEpochSeconds until it.endEpochSeconds }
            ?.let {
                tv.reely.xtream.EpgProgramme(
                    channel.epgChannelId.orEmpty(), it.startEpochSeconds, it.endEpochSeconds, it.title, it.description,
                )
            }
        return programme?.takeIf { it.start >= from }?.let { channel to it }
    }

    /** The programme on now, from its beginning, out of the channel's archive. */
    fun startOver() {
        val (channel, programme) = startOverProgramme() ?: return
        playCatchUp(channel, programme, timeshift = Timeshift(channel.streamId, programme.start, programme.stop))
    }

    /**
     * What the live bar spans: the programme on now, where the channel's archive can go
     * back into it — or, rewound already, the programme being watched behind live. Null
     * for a channel with no archive, which can't go back at all: the provider only ever
     * sends now.
     */
    fun liveWindow(): Timeshift? {
        val playback = _state.value.playback ?: return null
        playback.timeshift?.let { return it }
        val (channel, programme) = startOverProgramme() ?: return null
        return Timeshift(channel.streamId, programme.start, programme.stop)
    }

    /**
     * Back to [atMs], a moment (epoch milliseconds) behind now, out of the archive: the
     * programme on at that moment, from there. By the clock rather than by a place in a
     * programme, so a bar or a pause from before the programme changed still goes where
     * it meant.
     */
    fun timeshiftTo(atMs: Long): Boolean {
        val (channel, programme) = rewindableAt(atMs / 1000) ?: return false
        playCatchUp(
            channel, programme,
            startAtMs = (atMs - programme.start * 1000).coerceAtLeast(0),
            timeshift = Timeshift(channel.streamId, programme.start, programme.stop),
        )
        return true
    }

    /** From behind, back to the channel as it is now: that channel, wherever it is in the lists. */
    fun goLive() {
        val timeshift = _state.value.playback?.timeshift ?: return
        val channel = _state.value.live.channels.firstOrNull { it.streamId == timeshift.streamId }
            ?: allChannels?.firstOrNull { it.streamId == timeshift.streamId }
        if (channel != null) tuneChannel(channel) else stopPlayback()
    }

    /**
     * A programme from a channel's archive. It plays like a film rather than a channel:
     * from its start, with the bar to move through it, and Back returns to the guide.
     */
    fun playCatchUp(
        channel: XtreamChannel,
        programme: tv.reely.xtream.EpgProgramme,
        startAtMs: Long = 0,
        timeshift: Timeshift? = null,
    ) {
        val live = _state.value.live
        val credentials = live.credentials ?: return
        val url = XtreamApi.catchUpUrl(credentials, channel, programme.start, programme.stop, live.account?.timezone)
            ?: return
        silenceTheme()
        livePlayer.stop()
        val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val at = java.util.Date(programme.start * 1000)
        _state.update {
            it.copy(
                upNext = null,
                multiview = emptyList(),
                playback = Playback(
                    title = programme.title,
                    subtitle = "${channel.name}  ·  ${day.format(at)} ${format.format(at)}",
                    url = url,
                    isLive = false,
                    durationMs = programme.durationSeconds * 1000,
                    startPositionMs = startAtMs,
                    format = StreamFormat.TS,
                    timeshift = timeshift,
                ),
            )
        }
    }

    fun signOutXtream() {
        stopIptvLibrary()
        livePlayer.stop()
        store.remove(*LIVE_KEYS)
        channelsJob?.cancel()
        allChannels = null
        // Favorites belong to the television, not the login: they stay for the next one.
        _state.update {
            it.copy(
                live = LiveState(favorites = settings.favoriteChannels, recent = settings.recentChannels),
                search = it.search.copy(channels = emptyList()),
            )
        }
    }

    // ---------------------------------------------------------------- IPTV films and series

    private val iptv = IptvLibrary()
    private val iptvOptions = mutableMapOf<LibraryKind, IptvBrowseOptions>()
    private var iptvJob: Job? = null
    private var iptvPublishJob: Job? = null

    /** The IPTV film or episode playing, for keeping where it was left. */
    private var iptvPlaying: PlexItem? = null

    /** How long the player last said the IPTV file playing runs, which its list rarely says. */
    private var iptvDurationMs = 0L

    /** Whose catalogue and watch record are loaded: the login, hashed. */
    private var iptvAccount: String? = null

    /** The tabs whose IPTV grid is waiting to be worked out again; see [publishIptv]. */
    private val iptvPublishKinds = mutableSetOf<LibraryKind>()

    /** The provider's films and series need an Xtream login; a playlist has none of them. */
    private fun iptvCredentials(): XtreamCredentials? =
        _state.value.live.credentials?.takeIf { !it.isPlaylist }

    fun setIptvLibrary(on: Boolean) {
        settings.iptvLibrary = on
        _state.update { it.copy(prefs = it.prefs.copy(iptvLibrary = on)) }
        if (on) startIptvLibrary() else stopIptvLibrary()
    }

    /** The IPTV library in or out of the Movies and TV Shows menus, as a Plex library can be. */
    fun toggleIptvInMenus() {
        val next = !settings.iptvInMenus
        settings.iptvInMenus = next
        _state.update { it.copy(prefs = it.prefs.copy(iptvInMenus = next)) }
    }

    fun setIptvWins(wins: Boolean) {
        settings.iptvWins = wins
        _state.update { it.copy(prefs = it.prefs.copy(iptvWins = wins)) }
        publishIptv()
        // Plex's copies were left out of Home's rows while IPTV won; they come back.
        if (!wins) refreshHome()
    }

    /** Read the provider's list again now, from Settings. */
    fun refreshIptvLibrary() = startIptvLibrary(fromProvider = true)

    /**
     * Switched on, with an Xtream login: the catalogue from the device at once, then from
     * the provider when what's kept is old. Matched against Plex's libraries as those are
     * read, so a title in both shows once.
     */
    private fun startIptvLibrary(fromProvider: Boolean = false) {
        val credentials = iptvCredentials()
        if (!_state.value.prefs.iptvLibrary || credentials == null) {
            stopIptvLibrary()
            return
        }
        if (!fromProvider && iptvJob?.isActive == true) return
        iptvJob?.cancel()
        val dir = getApplication<Application>().filesDir
        val account = (credentials.base + "|" + credentials.username).hashCode().toUInt().toString(16)
        // Another login is another provider's list and another record of what's been watched.
        if (account != iptvAccount) {
            iptv.clear()
            iptvAccount = account
        }
        if (iptv.watch == null) iptv.watch = IptvWatch(java.io.File(dir, "iptv-watch-$account.json"))
        _state.update { it.copy(iptv = it.iptv.copy(on = true, loading = true, error = null)) }
        iptvJob = viewModelScope.launch {
            val file = VodCatalogCache.file(dir, credentials)
            val kept = if (fromProvider) null else withContext(Dispatchers.IO) { VodCatalogCache.read(file) }
            if (kept != null) {
                withContext(Dispatchers.Default) { iptv.setCatalog(kept) }
                publishIptv()
            }
            launch { refreshIptvIndex() }
            if (kept != null && System.currentTimeMillis() - kept.loadedAt < IPTV_CATALOG_MS) {
                _state.update { it.copy(iptv = it.iptv.copy(loading = false)) }
                return@launch
            }
            val read = runCatching { XtreamVod.catalog(credentials) }
            read.onSuccess { catalog ->
                withContext(Dispatchers.Default) { iptv.setCatalog(catalog) }
                withContext(Dispatchers.IO) { runCatching { VodCatalogCache.write(file, catalog) } }
                publishIptv()
            }
            _state.update {
                it.copy(iptv = it.iptv.copy(loading = false, error = read.exceptionOrNull()?.readable()))
            }
        }
    }

    /** Switched off, or signed out of the provider: none of it anywhere. */
    private fun stopIptvLibrary() {
        val wasOn = _state.value.iptv.on
        iptvJob?.cancel()
        iptvPublishJob?.cancel()
        iptvPublishKinds.clear()
        iptv.clear()
        iptvAccount = null
        _state.update { current ->
            current.copy(
                iptv = IptvState(),
                home = current.home.copy(
                    iptvMovies = emptyList(),
                    iptvShows = emptyList(),
                    continueWatching = current.home.continueWatching.filterNot { it.isIptv },
                ),
                search = current.search.copy(results = current.search.results.filterNot { it.isIptv }),
            )
        }
        val route = _state.value.route
        if (route is Route.Library && route.view == LibraryView.IPTV) navigate(Route.Library(route.kind))
        // Plex's copies were left out of Home's rows while IPTV won; they come back.
        if (wasOn && _state.value.prefs.iptvWins) refreshHome()
    }

    /** What's in Plex's libraries, to match IPTV's titles against. */
    private suspend fun refreshIptvIndex() {
        if (!_state.value.iptv.on) return
        val choices = _state.value.plex.homeSources()
        if (choices.isEmpty()) return
        suspend fun entries(kind: LibraryKind, type: Int) = choices.filter { it.section.type == kind.plexType }
            .map { choice ->
                viewModelScope.async {
                    runCatching { PlexApi.libraryEntries(choice.baseUrl, choice.token, choice.section.key, type) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        val movies = entries(LibraryKind.MOVIES, PlexApi.TYPE_MOVIE)
        val shows = entries(LibraryKind.SHOWS, PlexApi.TYPE_SHOW)
        withContext(Dispatchers.Default) { iptv.setPlex(movies, shows) }
        publishIptv()
    }

    private fun iptvOptionsFor(kind: LibraryKind) = iptvOptions[kind] ?: IptvBrowseOptions()

    /** The grids, Home's rows and Continue Watching, from the catalogue as it is now. */
    private fun publishIptv(kinds: Collection<LibraryKind> = LibraryKind.entries) {
        if (!_state.value.iptv.on) return
        // One at a time, the latest wins; but a tab asked for by one that's overtaken is
        // still owed its grid. Movies' sort changing mustn't lose the shows a moment before.
        iptvPublishKinds += kinds
        iptvPublishJob?.cancel()
        iptvPublishJob = viewModelScope.launch {
            val todo = iptvPublishKinds.toSet()
            val wins = _state.value.prefs.iptvWins
            val options = todo.associateWith(::iptvOptionsFor)
            val built = withContext(Dispatchers.Default) {
                todo.associateWith { kind -> iptv.browse(kind, options.getValue(kind), wins) to iptv.newest(kind, wins) }
            }
            iptvPublishKinds -= todo
            val catalog = iptv.catalog
            val progress = iptvContinue()
            _state.update { current ->
                if (!current.iptv.on) return@update current
                val movies = built[LibraryKind.MOVIES]
                val shows = built[LibraryKind.SHOWS]
                current.copy(
                    iptv = current.iptv.copy(
                        movies = movies?.first ?: current.iptv.movies,
                        shows = shows?.first ?: current.iptv.shows,
                        movieCount = catalog.movies.size,
                        showCount = catalog.series.size,
                        loadedAt = catalog.loadedAt,
                    ),
                    home = current.home.copy(
                        iptvMovies = movies?.second ?: current.home.iptvMovies,
                        iptvShows = shows?.second ?: current.home.iptvShows,
                        continueWatching = withIptvContinue(current.home.continueWatching, progress),
                        recentMovies = current.home.recentMovies.filterNot { iptv.hides(it, wins) },
                    ),
                )
            }
        }
    }

    /** What's part-watched from IPTV, when it's switched on. */
    private fun iptvContinue(): List<PlexItem> =
        if (_state.value.iptv.on) iptv.watch?.continueWatching().orEmpty() else emptyList()

    /** Plex's Continue Watching with IPTV's in among it, by when each was last watched. */
    private fun withIptvContinue(row: List<PlexItem>, progress: List<PlexItem> = iptvContinue()): List<PlexItem> =
        continueWatchingOrder(row.filterNot { it.isIptv } + progress)

    /** Continue Watching again after something from IPTV was watched, stopped or marked. */
    private fun refreshIptvContinue() {
        val progress = iptvContinue()
        _state.update { it.copy(home = it.home.copy(continueWatching = withIptvContinue(it.home.continueWatching, progress))) }
    }

    fun setIptvSort(kind: LibraryKind, sort: LibrarySort) {
        iptvOptions[kind] = iptvOptionsFor(kind).copy(sort = sort)
        publishIptv(listOf(kind))
    }

    fun selectIptvCategory(kind: LibraryKind, categoryId: String?) {
        iptvOptions[kind] = iptvOptionsFor(kind).copy(categoryId = categoryId)
        publishIptv(listOf(kind))
    }

    fun toggleIptvUnwatched(kind: LibraryKind) {
        iptvOptions[kind] = iptvOptionsFor(kind).let { it.copy(unwatchedOnly = !it.unwatchedOnly) }
        publishIptv(listOf(kind))
    }

    /** The whole list is here, so a letter is only a matter of counting. */
    fun jumpToIptvLetter(kind: LibraryKind, letter: String) {
        _state.update { current ->
            val browse = current.iptv.browseFor(kind)
            val index = browse.letterStart(letter) ?: return@update current
            val jumped = browse.copy(jump = GridJump(index, (browse.jump?.serial ?: 0) + 1))
            current.copy(
                iptv = if (kind == LibraryKind.MOVIES) current.iptv.copy(movies = jumped)
                else current.iptv.copy(shows = jumped),
            )
        }
    }

    /** A series' seasons and episodes, kept a while: its page, Up Next and Play next all want them. */
    private suspend fun iptvSeries(showId: Int): tv.reely.xtream.SeriesInfo? {
        iptv.cachedSeries(showId)?.let { return it }
        val credentials = iptvCredentials() ?: return null
        val info = runCatching { XtreamVod.seriesInfo(credentials, showId) }.getOrNull() ?: return null
        iptv.keepSeries(showId, info)
        return info
    }

    /** Every episode of a series, as items, with where each was left. */
    private suspend fun iptvEpisodes(showId: Int): List<Pair<tv.reely.xtream.VodSeason, List<PlexItem>>> {
        val info = iptvSeries(showId) ?: return emptyList()
        val title = iptv.series(showId)
        val name = title?.name ?: info.name?.let { VodNames.parse(it).name } ?: "Series"
        val poster = title?.poster ?: info.poster
        return info.seasons.map { season ->
            season to VodItems.episodes(showId, name, poster, info.backdrop, season).map(iptv::marked)
        }
    }

    private fun iptvShowId(item: PlexItem): Int? = when (val key = IptvKey.parse(item.ratingKey)) {
        is IptvKey.Show -> key.id
        is IptvKey.Season -> key.showId
        is IptvKey.Episode -> item.grandparentRatingKey?.let(IptvKey::parse)?.let { (it as? IptvKey.Show)?.id }
        else -> null
    }

    private fun iptvSeasonNumber(item: PlexItem): Int? = when (val key = IptvKey.parse(item.ratingKey)) {
        is IptvKey.Season -> key.number
        is IptvKey.Episode -> item.parentRatingKey?.let(IptvKey::parse)?.let { (it as? IptvKey.Season)?.number }
        else -> null
    }

    /** [detail] with where it was left, or how much of it has been watched. */
    private fun markedIptvDetail(detail: PlexDetail): PlexDetail {
        val watch = iptv.watch ?: return detail
        if (detail.isShow) return detail.copy(viewedLeafCount = watch.watchedUnder(detail.ratingKey, season = false))
        val mark = watch.mark(detail.ratingKey) ?: return detail
        return detail.copy(
            viewOffsetMs = mark.offsetMs,
            viewCount = if (mark.watched) 1 else 0,
            durationMs = detail.durationMs.takeIf { it > 0 } ?: mark.durationMs,
        )
    }

    /** A film's or series' page, from the provider rather than a Plex server. */
    private fun loadIptvDetail(route: Route.Detail) {
        val ratingKey = route.ratingKey
        _state.update {
            it.copy(detail = DetailState(ratingKey = ratingKey, serverBase = IPTV_SOURCE, busy = true))
        }
        fun onThisPage(change: (DetailState) -> DetailState) = _state.update { current ->
            val page = current.detail
            if (page == null || page.ratingKey != ratingKey || page.serverBase != IPTV_SOURCE) current
            else current.copy(detail = change(page))
        }
        val credentials = iptvCredentials()
        val key = IptvKey.parse(ratingKey)
        if (credentials == null || key == null) {
            onThisPage { it.copy(busy = false, error = "Sign in to your IPTV provider in Live TV to watch this.") }
            return
        }
        val wins = _state.value.prefs.iptvWins
        viewModelScope.launch {
            when (key) {
                is IptvKey.Movie -> {
                    val title = iptv.movie(key.id)
                    if (title != null) {
                        onThisPage { it.copy(detail = markedIptvDetail(VodItems.movieDetail(ratingKey, title, null))) }
                    }
                    val info = runCatching { XtreamVod.movieInfo(credentials, key.id) }.getOrNull()
                    if (title == null && info == null) {
                        onThisPage { it.copy(busy = false, error = "This title isn't available from your IPTV provider.") }
                        return@launch
                    }
                    val detail = markedIptvDetail(VodItems.movieDetail(ratingKey, title, info))
                    val related = title?.let { iptv.related(VodItems.movie(it), wins) }.orEmpty()
                    onThisPage { it.copy(detail = detail, related = related, busy = false) }
                }

                is IptvKey.Show -> {
                    val info = iptvSeries(key.id)
                    val title = iptv.series(key.id)
                    if (info == null) {
                        onThisPage { it.copy(busy = false, error = "Couldn't load this series from your IPTV provider.") }
                        return@launch
                    }
                    val name = title?.name ?: info.name?.let { VodNames.parse(it).name } ?: "Series"
                    val poster = title?.poster ?: info.poster
                    val detail = markedIptvDetail(VodItems.showDetail(ratingKey, title, info))
                    val seasons = VodItems.seasons(key.id, name, poster, info).map(iptv::marked)
                    val all = iptvEpisodes(key.id)
                    // Where it's up to, as a Plex show's page opens: the season and episode to watch next.
                    val upTo = PlexApi.nextEpisode(all.flatMap { it.second }).takeIf { route.seasonKey == null }
                    val season = seasons.firstOrNull { it.ratingKey == route.seasonKey }
                        ?: upTo?.let { next -> seasons.firstOrNull { it.ratingKey == next.parentRatingKey } }
                        ?: seasons.firstOrNull { (it.index ?: 0) > 0 }
                        ?: seasons.firstOrNull()
                    val episodes = all.firstOrNull { (s, _) -> IptvKey.season(key.id, s.number) == season?.ratingKey }
                        ?.second.orEmpty()
                    val focus = episodes.firstOrNull { it.ratingKey == (route.episodeKey ?: upTo?.ratingKey) }
                        ?: episodes.firstOrNull()
                    val related = title?.let { iptv.related(VodItems.show(it), wins) }.orEmpty()
                    onThisPage {
                        it.copy(
                            detail = detail.copy(title = name),
                            seasons = seasons,
                            selectedSeason = season,
                            episodes = episodes,
                            focusedEpisode = focus,
                            related = related,
                            busy = false,
                        )
                    }
                }

                else -> onThisPage { it.copy(busy = false, error = "This title isn't available from your IPTV provider.") }
            }
        }
    }

    /** A season of an IPTV series, from what its page already read. */
    private fun selectIptvSeason(season: PlexItem, focusEpisodeKey: String?) {
        val key = IptvKey.parse(season.ratingKey) as? IptvKey.Season ?: return
        val page = _state.value.detail ?: return
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
            // As for Plex: the season chosen, and no other season's episodes under it meanwhile.
            _state.update {
                it.copy(detail = it.detail?.copy(selectedSeason = season, busy = true, episodes = emptyList(), focusedEpisode = null))
            }
            val episodes = iptvEpisodes(key.showId).firstOrNull { (s, _) -> s.number == key.number }?.second.orEmpty()
            val now = _state.value.detail
            if (now?.selectedSeason?.ratingKey != season.ratingKey || now.ratingKey != page.ratingKey ||
                now.serverBase != page.serverBase
            ) return@launch
            val landOn = focusEpisodeKey?.let { wanted -> episodes.firstOrNull { it.ratingKey == wanted } }
                ?: episodes.firstOrNull()
            _state.update { it.copy(detail = it.detail?.copy(episodes = episodes, focusedEpisode = landOn, busy = false)) }
        }
    }

    /** A film or episode from the provider: straight from its address, nothing to ask first. */
    private fun playIptv(item: PlexItem, queue: List<PlexItem>, resume: Boolean): Job? {
        val credentials = iptvCredentials()
        if (credentials == null) {
            reportPlaybackProblem("Sign in to your IPTV provider in Live TV to play this.")
            return null
        }
        val url = when (val key = IptvKey.parse(item.ratingKey)) {
            is IptvKey.Movie -> XtreamVod.movieUrl(credentials, key.id, key.extension)
            is IptvKey.Episode -> XtreamVod.episodeUrl(credentials, key.id, key.extension)
            else -> return null
        }
        return viewModelScope.launch {
            val marked = iptv.marked(item)
            val effectiveQueue = queue.ifEmpty { siblingQueue(item) }.ifEmpty {
                if (item.type != "episode") emptyList()
                else iptvShowId(item)?.let { show ->
                    iptvEpisodes(show).firstOrNull { (s, _) -> s.number == iptvSeasonNumber(item) }?.second
                }.orEmpty()
            }
            livePlayer.stop()
            silenceTheme()
            releaseTranscode()
            iptvPlaying = item
            iptvDurationMs = marked.durationMs
            _state.update {
                it.copy(
                    multiview = emptyList(),
                    upNext = null,
                    playback = Playback(
                        title = item.title,
                        subtitle = subtitleLineFor(item),
                        url = url,
                        isLive = false,
                        ratingKey = item.ratingKey,
                        startPositionMs = if (resume) marked.viewOffsetMs else 0,
                        durationMs = marked.durationMs,
                        serverBase = IPTV_SOURCE,
                        queue = effectiveQueue,
                        queueIndex = effectiveQueue.indexOfFirst { entry -> entry.ratingKey == item.ratingKey },
                    ),
                )
            }
        }
    }

    /** Where an IPTV film or episode is up to, kept here since the provider keeps nothing. */
    private fun keepIptvProgress(playback: Playback, positionMs: Long) {
        val item = iptvPlaying?.takeIf { it.ratingKey == playback.ratingKey } ?: return
        val duration = iptvDurationMs.takeIf { it > 0 } ?: playback.durationMs
        iptv.watch?.progress(item, positionMs, duration)
    }

    /** Watched or not, for an IPTV title: a show or season is all its episodes. */
    private fun toggleIptvWatched(item: PlexItem) {
        val watch = iptv.watch ?: return
        val watched = !item.isWatched
        if (item.type == "movie" || item.type == "episode") {
            watch.setWatched(item, watched)
            applyWatched(item.ratingKey, watched, IPTV_SOURCE)
            refreshIptvContinue()
            return
        }
        val show = iptvShowId(item) ?: return
        val season = iptvSeasonNumber(item)
        viewModelScope.launch {
            val episodes = iptvEpisodes(show)
                .filter { (s, _) -> season == null || s.number == season }
                .flatMap { it.second }
            if (episodes.isEmpty()) {
                reportPlaybackProblem("Couldn't mark that as ${if (watched) "watched" else "unwatched"}.")
                return@launch
            }
            watch.setWatched(episodes, watched)
            // The show or season marked marks the episodes on its page with it; anywhere
            // else an episode of it shows, Continue Watching, is worked out again below.
            applyWatched(item.ratingKey, watched, IPTV_SOURCE)
            refreshIptvContinue()
        }
    }

    /** A series' next episode, from its menu: as for Plex, see [PlexApi.nextEpisode]. */
    private fun playNextIptvEpisode(item: PlexItem) {
        val show = iptvShowId(item) ?: return
        val season = iptvSeasonNumber(item)
        viewModelScope.launch {
            val all = iptvEpisodes(show).filter { (s, _) -> season == null || s.number == season }
            val next = PlexApi.nextEpisode(all.flatMap { it.second }) ?: run {
                reportPlaybackProblem("There are no episodes to play.")
                return@launch
            }
            play(next, queue = all.firstOrNull { (_, list) -> next in list }?.second.orEmpty())
        }
    }

    /** The first episode of the season after the one playing, for Up Next at a season's end. */
    private suspend fun firstOfNextIptvSeason(playback: Playback): PlexItem? {
        val current = playback.queue.getOrNull(playback.queueIndex) ?: iptvPlaying ?: return null
        val show = iptvShowId(current) ?: return null
        val number = iptvSeasonNumber(current) ?: return null
        val seasons = iptvEpisodes(show)
        val at = seasons.indexOfFirst { (s, _) -> s.number == number }
        return seasons.getOrNull(at + 1)?.second?.firstOrNull()
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
        if (!BuildConfig.SELF_UPDATE || _state.value.update is UpdateStatus.Checking) return
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
            _state.update { it.copy(update = UpdateStatus.Handed(file)) }
        }
    }

    /** The installer again, for the file already downloaded. */
    fun openInstaller() {
        _state.update { current ->
            val handed = current.update as? UpdateStatus.Handed ?: return@update current
            current.copy(update = handed.copy(request = handed.request + 1, note = null))
        }
    }

    /** The screen has taken this request for the installer in hand. */
    fun installerRequestHandled(request: Int) {
        _state.update { current ->
            val handed = current.update as? UpdateStatus.Handed ?: return@update current
            current.copy(update = handed.copy(handled = maxOf(handed.handled, request)))
        }
    }

    /** The installer was asked for and didn't come up, or couldn't be asked for. */
    fun installerDidNotOpen(note: String) {
        _state.update { current ->
            val handed = current.update as? UpdateStatus.Handed ?: return@update current
            current.copy(update = handed.copy(note = note))
        }
    }

    // ---------------------------------------------------------------- Theme music

    fun toggleThemeMusic() {
        val next = !settings.themeMusic
        settings.themeMusic = next
        _state.update { it.copy(prefs = it.prefs.copy(themeMusic = next)) }
        if (!next) themePlayer.silence() else startTheme()
    }

    /** A row of Home on or off. */
    fun toggleHomeRow(row: HomeRow) {
        val next = settings.hiddenHomeRows.let { if (row.id in it) it - row.id else it + row.id }
        settings.hiddenHomeRows = next
        _state.update { it.copy(prefs = it.prefs.copy(hiddenHomeRows = next)) }
        if (row.fromReely && row.id !in next) loadRequests()
    }

    /** The tour finished or skipped: not shown again unless asked for. */
    fun finishTour() {
        settings.tourSeen = true
        _state.update { it.copy(prefs = it.prefs.copy(tourSeen = true)) }
    }

    /** The tour again, from Settings. */
    fun replayTour() {
        _state.update { it.copy(prefs = it.prefs.copy(tourSeen = false)) }
    }

    fun setScreensaverMinutes(minutes: Int) {
        settings.screensaverMinutes = minutes
        _state.update { it.copy(prefs = it.prefs.copy(screensaverMinutes = minutes)) }
    }

    /**
     * The screensaver's pictures: the artwork of what's on Home, in no particular order,
     * each once. Films and shows with wide artwork of their own; nothing without.
     */
    fun screensaverSlides(): List<tv.reely.ui.screens.SaverSlide> {
        val home = _state.value.home
        val items = home.continueWatching + home.recentMovies + home.recentEpisodes.map { it.newest } + home.watchlist
        return items
            .mapNotNull { item ->
                val art = item.art ?: return@mapNotNull null
                val url = plexImageUrl(item.serverBase, art, width = 1920, height = 1080) ?: return@mapNotNull null
                val isEpisode = item.type == "episode" && item.grandparentTitle != null
                tv.reely.ui.screens.SaverSlide(
                    url = url,
                    title = if (isEpisode) item.grandparentTitle!! else item.title,
                    caption = if (isEpisode) item.caption else item.year?.toString(),
                )
            }
            .distinctBy { it.url }
            .shuffled()
    }

    fun toggleSkipCredits() {
        val next = !settings.skipCredits
        settings.skipCredits = next
        _state.update { it.copy(prefs = it.prefs.copy(skipCredits = next)) }
    }

    fun toggleSkipIntros() {
        val next = !settings.skipIntros
        settings.skipIntros = next
        _state.update { it.copy(prefs = it.prefs.copy(skipIntros = next)) }
    }

    fun setAudioOutput(key: String?) {
        settings.audioOutput = key
        _state.update { it.copy(prefs = it.prefs.copy(audioOutput = key)) }
        // Back to the TV with headphones still connected: let go of them, or the remote's
        // volume buttons go on turning them up and down instead of the TV.
        if (tv.reely.core.AudioOutputs.releasesBluetooth(key)) {
            viewModelScope.launch { tv.reely.core.BluetoothAudio.release(getApplication()) }
        }
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
        const val PLEX_HOLDINGS_MS = 10 * 60_000L
    }
}

/**
 * A detail page already holds everything the player needs from an item — and which
 * server it's on. Without that it was taken to be the one connected, so a film from a
 * second server played whatever had its number on the first, and Watched marked that.
 */
private fun PlexDetail.asItem(serverBase: String?): PlexItem = PlexItem(
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
    viewedLeafCount = viewedLeafCount,
    viewCount = viewCount,
    addedAt = 0,
    librarySectionId = null,
    serverBase = serverBase,
)

/** What to say about a failure on screen; see [tv.reely.core.Friendly]. */
private fun Throwable.readable(): String = tv.reely.core.Friendly.error(this)

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
