package tv.reely.ui

import tv.reely.core.SearchMatch
import tv.reely.plex.PlexGenre
import tv.reely.plex.PlexIndexEntry
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexLetter
import tv.reely.plex.PlexSection
import tv.reely.xtream.IptvWatch
import tv.reely.xtream.SeriesInfo
import tv.reely.xtream.TitleIndex
import tv.reely.xtream.VodCatalog
import tv.reely.xtream.VodItems
import tv.reely.xtream.VodNames
import tv.reely.xtream.VodTitle
import tv.reely.xtream.isIptv

/** How an IPTV library grid is being looked at: the same choices a Plex one offers. */
data class IptvBrowseOptions(
    val sort: LibrarySort = LibrarySort.TITLE,
    /** The provider's category, standing in for a genre. */
    val categoryId: String? = null,
    val unwatchedOnly: Boolean = false,
)

/** The IPTV side of the tabs, Home and search, as the screens show it. */
data class IptvState(
    /** Switched on in Settings, with an Xtream login to read from. */
    val on: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val movieCount: Int = 0,
    val showCount: Int = 0,
    /** When the catalogue was read from the provider, in epoch milliseconds. */
    val loadedAt: Long = 0,
    val movies: BrowseState = IptvLibrary.emptyBrowse(LibraryKind.MOVIES),
    val shows: BrowseState = IptvLibrary.emptyBrowse(LibraryKind.SHOWS),
) {
    fun browseFor(kind: LibraryKind): BrowseState = if (kind == LibraryKind.MOVIES) movies else shows
}

/**
 * The provider's films and series, kept whole, and every view of them the screens ask
 * for: a library grid sorted and filtered, the newest for Home, what matches a search.
 * Titles Plex also has are matched here, and whichever side wins is the one shown.
 */
class IptvLibrary {

    @Volatile var catalog: VodCatalog = VodCatalog()
        private set
    private var moviesById: Map<Int, VodTitle> = emptyMap()
    private var seriesById: Map<Int, VodTitle> = emptyMap()
    private var iptvMovies: TitleIndex = TitleIndex.EMPTY
    private var iptvShows: TitleIndex = TitleIndex.EMPTY

    @Volatile private var plexMovies: TitleIndex = TitleIndex.EMPTY
    @Volatile private var plexShows: TitleIndex = TitleIndex.EMPTY
    @Volatile private var plexEntries: Map<String, PlexIndexEntry> = emptyMap()

    /** Where things were left, for the login in use. */
    @Volatile var watch: IptvWatch? = null

    private val seriesCache = object : LinkedHashMap<Int, SeriesInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SeriesInfo>?) = size > SERIES_KEPT
    }

    fun setCatalog(next: VodCatalog) {
        moviesById = next.movies.associateBy { it.id }
        seriesById = next.series.associateBy { it.id }
        iptvMovies = TitleIndex.ofIptv(next.movies)
        iptvShows = TitleIndex.ofIptv(next.series)
        catalog = next
    }

    fun setPlex(movies: List<PlexIndexEntry>, shows: List<PlexIndexEntry>) {
        plexMovies = TitleIndex.ofPlex(movies)
        plexShows = TitleIndex.ofPlex(shows)
        plexEntries = (movies + shows).associateBy { (it.serverBase ?: "") + "|" + it.ratingKey }
    }

    fun clear() {
        setCatalog(VodCatalog())
        synchronized(seriesCache) { seriesCache.clear() }
        watch = null
    }

    fun movie(id: Int): VodTitle? = moviesById[id]
    fun series(id: Int): VodTitle? = seriesById[id]

    fun cachedSeries(id: Int): SeriesInfo? = synchronized(seriesCache) { seriesCache[id] }
    fun keepSeries(id: Int, info: SeriesInfo) = synchronized(seriesCache) { seriesCache[id] = info }

    /** [item] with where it was left, when it's IPTV's. */
    fun marked(item: PlexItem): PlexItem = if (item.isIptv) watch?.apply(item) ?: item else item

    /** The provider's titles of one kind, less those Plex has when Plex wins. */
    fun shown(kind: LibraryKind, iptvWins: Boolean): List<VodTitle> {
        val titles = if (kind == LibraryKind.MOVIES) catalog.movies else catalog.series
        if (iptvWins) return titles
        val plex = if (kind == LibraryKind.MOVIES) plexMovies else plexShows
        if (plex.isEmpty) return titles
        return titles.filterNot { plex.has(it.tmdbId, it.key, it.year) }
    }

    /**
     * Whether a Plex title gives way to IPTV's copy of it. Only where the two are shown
     * together — Home's rows, search — and only when IPTV has been chosen to win.
     */
    fun hides(item: PlexItem, iptvWins: Boolean): Boolean {
        if (!iptvWins || item.isIptv) return false
        val index = when (item.type) {
            "movie" -> iptvMovies
            "show" -> iptvShows
            else -> return false
        }
        val entry = plexEntries[(item.serverBase ?: "") + "|" + item.ratingKey]
        val tmdb = entry?.guids?.firstNotNullOfOrNull(TitleIndex::tmdbOf)
        return index.has(tmdb, VodNames.key(item.title), item.year)
    }

    fun browse(kind: LibraryKind, options: IptvBrowseOptions, iptvWins: Boolean): BrowseState {
        val categories = if (kind == LibraryKind.MOVIES) catalog.movieCategories else catalog.seriesCategories
        val all = shown(kind, iptvWins)
        var titles = all
        options.categoryId?.let { id -> titles = titles.filter { it.categoryId == id } }
        var items = titles.map { marked(VodItems.of(it)) }
        if (options.unwatchedOnly) items = items.filterNot { it.isWatched }
        val rated = titles.associate { it.id to (it.rating ?: 0.0) }
        fun ratingOf(item: PlexItem) = rated[idOf(item)] ?: 0.0
        items = when (options.sort) {
            LibrarySort.TITLE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.titleSort ?: it.title })
            LibrarySort.ADDED -> items.sortedByDescending { it.addedAt }
            LibrarySort.RELEASED -> items.sortedByDescending { it.year ?: 0 }
            LibrarySort.OLDEST -> items.sortedBy { it.year ?: Int.MAX_VALUE }
            LibrarySort.RATED, LibrarySort.AUDIENCE -> items.sortedByDescending(::ratingOf)
            LibrarySort.WATCHED -> items.sortedByDescending { it.lastViewedAt }
        }
        // Categories with nothing in them after matching aren't worth a chip.
        val used = all.mapNotNullTo(HashSet()) { it.categoryId }
        return emptyBrowse(kind).copy(
            items = items,
            genres = categories.filter { it.id in used }.map { PlexGenre(it.id, it.name) },
            sort = options.sort,
            genreId = options.categoryId,
            unwatchedOnly = options.unwatchedOnly,
            letters = if (options.sort == LibrarySort.TITLE) letters(items) else emptyList(),
        )
    }

    /** The newest the provider has added, for Home. */
    fun newest(kind: LibraryKind, iptvWins: Boolean, count: Int = ROW_SIZE): List<PlexItem> =
        shown(kind, iptvWins).sortedByDescending { it.addedAt }.take(count).map { marked(VodItems.of(it)) }

    /** Films and series with [query] in their name, best first. */
    fun search(query: String, iptvWins: Boolean, count: Int = SEARCH_SIZE): List<PlexItem> {
        val wanted = SearchMatch.words(query)
        if (wanted.isEmpty()) return emptyList()
        val candidates = (shown(LibraryKind.MOVIES, iptvWins) + shown(LibraryKind.SHOWS, iptvWins))
            .asSequence()
            .filter { title -> wanted.all { it in title.key } }
            .take(count * 4)
            .map { marked(VodItems.of(it)) }
            .toList()
        return SearchMatch.relevant(query, candidates).take(count)
    }

    /** Others in the same category, for "More like this". */
    fun related(item: PlexItem, iptvWins: Boolean, count: Int = ROW_SIZE): List<PlexItem> {
        val kind = if (item.type == "show") LibraryKind.SHOWS else LibraryKind.MOVIES
        val id = idOf(item) ?: return emptyList()
        val own = (if (kind == LibraryKind.MOVIES) moviesById[id] else seriesById[id]) ?: return emptyList()
        val category = own.categoryId ?: return emptyList()
        return shown(kind, iptvWins)
            .filter { it.categoryId == category && it.id != id }
            .sortedByDescending { it.addedAt }
            .take(count)
            .map { marked(VodItems.of(it)) }
    }

    private fun idOf(item: PlexItem): Int? = when (val key = tv.reely.xtream.IptvKey.parse(item.ratingKey)) {
        is tv.reely.xtream.IptvKey.Movie -> key.id
        is tv.reely.xtream.IptvKey.Show -> key.id
        else -> null
    }

    private fun letters(items: List<PlexItem>): List<PlexLetter> {
        val counts = LinkedHashMap<String, Int>()
        for (item in items) {
            val letter = tv.reely.ui.screens.letterOf(item)
            counts[letter] = (counts[letter] ?: 0) + 1
        }
        return counts.map { (letter, count) -> PlexLetter(letter, count) }
    }

    companion object {
        const val SECTION_KEY = "iptv"

        fun emptyBrowse(kind: LibraryKind) = BrowseState(
            section = PlexSection(SECTION_KEY, "IPTV ${kind.title}", kind.plexType),
            complete = true,
        )

        private const val ROW_SIZE = 40
        private const val SEARCH_SIZE = 40
        private const val SERIES_KEPT = 12
    }
}
