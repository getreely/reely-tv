package tv.reely.xtream

import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.HomeCache
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexIndexEntry
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexRole
import java.io.File

/**
 * Where an item from the IPTV provider says it's from, in the place a Plex item names its
 * server. Rows, grids, pages and the player all carry items this way, so an IPTV film
 * goes everywhere a Plex one does; anything that would ask a Plex server about it finds
 * no server by this name and no token for it, and leaves it alone.
 */
const val IPTV_SOURCE = "iptv:"

val PlexItem.isIptv: Boolean get() = serverBase == IPTV_SOURCE

fun isIptvSource(serverBase: String?): Boolean = serverBase == IPTV_SOURCE

/** The word on an IPTV poster, so it's plain which copy it is. */
val PlexItem.sourceTag: String? get() = if (isIptv) "IPTV" else null

/**
 * What an IPTV item's key says it is. The key carries what playing it needs — a film's
 * or episode's file type is part of its address — so something in Continue Watching
 * plays without its series being looked up first.
 */
sealed interface IptvKey {
    data class Movie(val id: Int, val extension: String?) : IptvKey
    data class Show(val id: Int) : IptvKey
    data class Season(val showId: Int, val number: Int) : IptvKey
    data class Episode(val id: Int, val extension: String?) : IptvKey

    companion object {
        fun movie(id: Int, extension: String?) = "m$id" + (extension?.let { ".$it" } ?: "")
        fun show(id: Int) = "s$id"
        fun season(showId: Int, number: Int) = "s$showId:$number"
        fun episode(id: Int, extension: String?) = "e$id" + (extension?.let { ".$it" } ?: "")

        fun parse(key: String): IptvKey? {
            if (key.length < 2) return null
            val body = key.substring(1)
            fun idAndExtension(): Pair<Int, String?>? {
                val id = body.substringBefore('.').toIntOrNull() ?: return null
                return id to body.substringAfter('.', "").takeIf { it.isNotEmpty() }
            }
            return when (key[0]) {
                'm' -> idAndExtension()?.let { (id, ext) -> Movie(id, ext) }
                'e' -> idAndExtension()?.let { (id, ext) -> Episode(id, ext) }
                's' -> if (':' in body) {
                    val show = body.substringBefore(':').toIntOrNull()
                    val number = body.substringAfter(':').toIntOrNull()
                    if (show != null && number != null) Season(show, number) else null
                } else body.toIntOrNull()?.let(::Show)
                else -> null
            }
        }
    }
}

/** The provider's films, series, seasons and episodes as the items the screens show. */
object VodItems {

    fun of(title: VodTitle): PlexItem = if (title.series) show(title) else movie(title)

    fun movie(title: VodTitle): PlexItem = blank(
        ratingKey = IptvKey.movie(title.id, title.extension),
        title = title.name,
        type = "movie",
    ).copy(
        thumb = title.poster,
        summary = title.plot,
        year = title.year,
        addedAt = title.addedAt,
        qualities = listOfNotNull(title.tag),
        librarySectionId = title.categoryId,
        titleSort = VodNames.sortName(title.name),
    )

    fun show(title: VodTitle): PlexItem = blank(
        ratingKey = IptvKey.show(title.id),
        title = title.name,
        type = "show",
    ).copy(
        thumb = title.poster,
        summary = title.plot,
        year = title.year,
        addedAt = title.addedAt,
        qualities = listOfNotNull(title.tag),
        librarySectionId = title.categoryId,
        titleSort = VodNames.sortName(title.name),
    )

    fun seasons(showId: Int, showName: String, showPoster: String?, info: SeriesInfo): List<PlexItem> =
        info.seasons.map { season ->
            blank(IptvKey.season(showId, season.number), season.name, "season").copy(
                thumb = season.poster ?: showPoster,
                index = season.number,
                parentRatingKey = IptvKey.show(showId),
                parentTitle = showName,
                leafCount = season.episodes.size,
            )
        }

    fun episodes(showId: Int, showName: String, showPoster: String?, backdrop: String?, season: VodSeason): List<PlexItem> =
        season.episodes.map { episode ->
            blank(IptvKey.episode(episode.id, episode.extension), episode.title, "episode").copy(
                thumb = episode.still ?: showPoster,
                art = backdrop,
                summary = episode.plot,
                index = episode.number,
                // As Plex has it: a special belongs to no numbered season, which is how
                // "what's next" knows to pass over it.
                parentIndex = season.number.takeIf { it > 0 },
                parentRatingKey = IptvKey.season(showId, season.number),
                parentTitle = season.name,
                grandparentRatingKey = IptvKey.show(showId),
                grandparentTitle = showName,
                grandparentThumb = showPoster,
                durationMs = episode.durationMs,
                airDate = episode.airDate,
            )
        }

    fun movieDetail(key: String, title: VodTitle?, info: VodInfo?): PlexDetail = PlexDetail(
        ratingKey = key,
        type = "movie",
        title = title?.name ?: info?.name?.let { VodNames.parse(it).name } ?: "Film",
        summary = info?.plot ?: title?.plot,
        tagline = null,
        year = title?.year ?: info?.releaseDate?.take(4)?.toIntOrNull(),
        durationMs = info?.durationMs ?: 0,
        viewOffsetMs = 0,
        contentRating = info?.ageRating,
        rating = info?.rating ?: title?.rating,
        audienceRating = null,
        airDate = info?.releaseDate,
        viewCount = 0,
        studio = null,
        thumb = info?.poster ?: title?.poster,
        art = info?.backdrop,
        theme = null,
        genres = info?.genres.orEmpty().ifEmpty { title?.genre?.let { listOf(it) }.orEmpty() },
        directors = info?.directors.orEmpty(),
        roles = info?.cast.orEmpty().take(24).map { PlexRole(name = it, role = null, thumb = null) },
        childCount = 0,
        leafCount = 0,
        grandparentTitle = null,
        index = null,
        parentIndex = null,
        qualities = listOfNotNull(title?.tag),
    )

    fun showDetail(key: String, title: VodTitle?, info: SeriesInfo?): PlexDetail = PlexDetail(
        ratingKey = key,
        type = "show",
        title = title?.name ?: info?.name?.let { VodNames.parse(it).name } ?: "Series",
        summary = info?.plot ?: title?.plot,
        tagline = null,
        year = title?.year ?: info?.releaseDate?.take(4)?.toIntOrNull(),
        durationMs = 0,
        viewOffsetMs = 0,
        contentRating = null,
        rating = info?.rating ?: title?.rating,
        audienceRating = null,
        airDate = info?.releaseDate,
        viewCount = 0,
        studio = null,
        thumb = info?.poster ?: title?.poster,
        art = info?.backdrop,
        theme = null,
        genres = info?.genres.orEmpty().ifEmpty { title?.genre?.let { listOf(it) }.orEmpty() },
        directors = info?.directors.orEmpty(),
        roles = info?.cast.orEmpty().take(24).map { PlexRole(name = it, role = null, thumb = null) },
        childCount = info?.seasons?.size ?: 0,
        leafCount = info?.seasons?.sumOf { it.episodes.size } ?: 0,
        grandparentTitle = null,
        index = null,
        parentIndex = null,
        qualities = listOfNotNull(title?.tag),
    )

    private fun blank(ratingKey: String, title: String, type: String) = PlexItem(
        ratingKey = ratingKey, title = title, type = type, thumb = null, art = null, summary = null,
        year = null, index = null, parentIndex = null, parentRatingKey = null, parentTitle = null,
        grandparentRatingKey = null, grandparentTitle = null, grandparentThumb = null,
        durationMs = 0, viewOffsetMs = 0, leafCount = 0, viewedLeafCount = 0, viewCount = 0,
        addedAt = 0, librarySectionId = null, serverBase = IPTV_SOURCE,
    )
}

/** Where one IPTV film or episode was left, kept on this television. */
data class IptvMark(
    val offsetMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    /** When, in epoch seconds. */
    val at: Long,
    /** Enough of the item to show it in Continue Watching and play it from there. */
    val item: PlexItem,
)

/**
 * What's been watched from the IPTV provider, and where each thing was left. A Plex
 * server keeps this for its own titles; the provider keeps nothing, so it's kept here.
 */
class IptvWatch(private val file: File, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {

    private val marks: LinkedHashMap<String, IptvMark> by lazy { load() }

    @Synchronized fun mark(key: String): IptvMark? = marks[key]

    /** Stopped at [positionMs] of [durationMs]. Near enough the end is watched, from the top next time. */
    @Synchronized fun progress(item: PlexItem, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) return
        val done = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
        put(item, IptvMark(
            offsetMs = if (done) 0 else positionMs,
            durationMs = durationMs,
            watched = done || marks[item.ratingKey]?.watched == true,
            at = now(),
            item = item,
        ))
    }

    @Synchronized fun setWatched(item: PlexItem, watched: Boolean) = setWatched(listOf(item), watched)

    /** A whole season or show at once, written down once. */
    @Synchronized fun setWatched(items: List<PlexItem>, watched: Boolean) {
        for (item in items) {
            val previous = marks[item.ratingKey]
            put(item, IptvMark(
                offsetMs = 0,
                durationMs = previous?.durationMs ?: item.durationMs,
                watched = watched,
                at = if (watched) now() else previous?.at ?: now(),
                item = item,
            ), save = false)
        }
        save()
    }

    /** Off Continue Watching: where it was left is forgotten, whether it was watched isn't. */
    @Synchronized fun forgetProgress(key: String) {
        val mark = marks[key] ?: return
        marks[key] = mark.copy(offsetMs = 0)
        save()
    }

    /** [item] with where it was left and whether it's been watched. */
    @Synchronized fun apply(item: PlexItem): PlexItem {
        if (item.type == "show" || item.type == "season") {
            val watched = watchedUnder(item.ratingKey, season = item.type == "season")
            return if (watched == item.viewedLeafCount) item else item.copy(viewedLeafCount = watched)
        }
        val mark = marks[item.ratingKey] ?: return item
        return item.copy(
            viewOffsetMs = mark.offsetMs,
            viewCount = if (mark.watched) maxOf(1, item.viewCount) else 0,
            durationMs = item.durationMs.takeIf { it > 0 } ?: mark.durationMs,
            lastViewedAt = mark.at,
        )
    }

    /** How many episodes of a show or season have been watched. */
    @Synchronized fun watchedUnder(key: String, season: Boolean): Int = marks.values.count {
        it.watched && (if (season) it.item.parentRatingKey == key else it.item.grandparentRatingKey == key)
    }

    /** Part-way through, most recent first. */
    @Synchronized fun continueWatching(): List<PlexItem> = marks.values
        .filter { it.offsetMs > 0 && !it.watched }
        .sortedByDescending { it.at }
        .map { apply(it.item) }

    private fun put(item: PlexItem, mark: IptvMark, save: Boolean = true) {
        marks.remove(item.ratingKey)
        // Kept without its progress: that's the mark's to say.
        marks[item.ratingKey] = mark.copy(item = item.copy(viewOffsetMs = 0, viewCount = 0, lastViewedAt = 0))
        while (marks.size > LIMIT) marks.remove(marks.keys.first())
        if (save) save()
    }

    private fun load(): LinkedHashMap<String, IptvMark> {
        val kept = LinkedHashMap<String, IptvMark>()
        runCatching {
            if (!file.exists()) return kept
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("item")?.let(HomeCache::decode) ?: continue
                kept[item.ratingKey] = IptvMark(
                    offsetMs = entry.optLong("offset"),
                    durationMs = entry.optLong("duration"),
                    watched = entry.optBoolean("watched"),
                    at = entry.optLong("at"),
                    item = item,
                )
            }
        }
        return kept
    }

    private fun save() {
        runCatching {
            val array = JSONArray()
            marks.values.forEach { mark ->
                array.put(
                    JSONObject()
                        .put("offset", mark.offsetMs)
                        .put("duration", mark.durationMs)
                        .put("watched", mark.watched)
                        .put("at", mark.at)
                        .put("item", HomeCache.encode(mark.item))
                )
            }
            val temp = File(file.parentFile, file.name + ".part")
            temp.writeText(array.toString())
            temp.renameTo(file)
        }
    }

    companion object {
        /** As Plex counts it by default. */
        const val WATCHED_FRACTION = 0.9

        /** Plenty for years of watching; the oldest go first. */
        private const val LIMIT = 3000
    }
}

/**
 * Titles on one side, for telling whether a title on the other is the same one. By its
 * TMDB id when both know it, which is exact; otherwise by name and year, a year either
 * way since a provider and Plex don't always agree on it; by name alone when the
 * provider gives no year.
 */
class TitleIndex private constructor(
    private val tmdb: Set<String>,
    private val named: Set<String>,
    private val names: Set<String>,
) {
    fun has(tmdbId: String?, key: String, year: Int?): Boolean = when {
        tmdbId != null && tmdbId in tmdb -> true
        key.isEmpty() -> false
        year != null -> "$key|$year" in named
        else -> key in names
    }

    val isEmpty: Boolean get() = tmdb.isEmpty() && names.isEmpty()

    companion object {
        val EMPTY = TitleIndex(emptySet(), emptySet(), emptySet())

        fun ofPlex(entries: List<PlexIndexEntry>): TitleIndex {
            val tmdb = HashSet<String>()
            val named = HashSet<String>()
            val names = HashSet<String>()
            for (entry in entries) {
                entry.guids.firstNotNullOfOrNull { tmdbOf(it) }?.let(tmdb::add)
                for (title in listOfNotNull(entry.title, entry.originalTitle)) {
                    val key = VodNames.key(title)
                    if (key.isEmpty()) continue
                    names += key
                    entry.year?.let { year -> for (y in year - 1..year + 1) named += "$key|$y" }
                }
            }
            return TitleIndex(tmdb, named, names)
        }

        fun ofIptv(titles: List<VodTitle>): TitleIndex {
            val tmdb = HashSet<String>()
            val named = HashSet<String>()
            val names = HashSet<String>()
            for (title in titles) {
                title.tmdbId?.let(tmdb::add)
                if (title.key.isEmpty()) continue
                names += title.key
                title.year?.let { year -> for (y in year - 1..year + 1) named += "${title.key}|$y" }
            }
            return TitleIndex(tmdb, named, names)
        }

        /** "603" from "tmdb://603". */
        fun tmdbOf(guid: String): String? =
            guid.takeIf { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.takeIf { it.isNotBlank() }
    }
}
