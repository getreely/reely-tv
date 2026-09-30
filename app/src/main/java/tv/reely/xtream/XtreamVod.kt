package tv.reely.xtream

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.Http
import tv.reely.core.SearchMatch
import java.io.File
import java.io.Reader

/**
 * A film or series the provider offers on demand, as its list gives it: enough for a
 * poster, sorting, filtering and matching against Plex. What's on its page is asked for
 * when the page opens; see [VodInfo] and [SeriesInfo].
 */
data class VodTitle(
    val series: Boolean,
    /** The film's stream id, or the series' id. */
    val id: Int,
    /** The name without the year or language tag the provider folds into it. */
    val name: String,
    val year: Int?,
    /** What the provider put in front of the name: "EN", "4K", "NF". */
    val tag: String?,
    val poster: String?,
    /** Out of ten. */
    val rating: Double?,
    /** When the provider added it, in epoch seconds; 0 when it doesn't say. */
    val addedAt: Long,
    val categoryId: String?,
    val tmdbId: String?,
    /** A film's file type, which its address ends in. */
    val extension: String?,
    val plot: String? = null,
    val genre: String? = null,
) {
    /** The name's words run together, lower case, accents off: for search and matching. */
    val key: String = VodNames.key(name)
}

/** The provider's films and series, and the categories it files them under. */
data class VodCatalog(
    val movies: List<VodTitle> = emptyList(),
    val series: List<VodTitle> = emptyList(),
    val movieCategories: List<XtreamCategory> = emptyList(),
    val seriesCategories: List<XtreamCategory> = emptyList(),
    /** When it was read from the provider, in epoch milliseconds. */
    val loadedAt: Long = 0,
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
}

/** A film's page: what the provider knows about it beyond its list entry. */
data class VodInfo(
    val name: String?,
    val plot: String?,
    val cast: List<String>,
    val directors: List<String>,
    val genres: List<String>,
    val durationMs: Long,
    val backdrop: String?,
    val poster: String?,
    val releaseDate: String?,
    val rating: Double?,
    val tmdbId: String?,
    val extension: String?,
    val ageRating: String?,
)

data class VodEpisode(
    val id: Int,
    val season: Int,
    val number: Int?,
    val title: String,
    val extension: String?,
    val plot: String?,
    val still: String?,
    val durationMs: Long,
    val airDate: String?,
)

data class VodSeason(val number: Int, val name: String, val poster: String?, val episodes: List<VodEpisode>)

/** A series' page: its seasons, each with its episodes, in order. */
data class SeriesInfo(
    val name: String?,
    val plot: String?,
    val cast: List<String>,
    val directors: List<String>,
    val genres: List<String>,
    val backdrop: String?,
    val poster: String?,
    val releaseDate: String?,
    val rating: Double?,
    val tmdbId: String?,
    val seasons: List<VodSeason>,
)

/**
 * Tidying the names providers give their films: "EN - The Matrix (1999)", "|4K| Dune",
 * "[FR] Amélie - 2001". The name shown is the title; the year goes where a year goes;
 * the tag is kept to tell two copies of one film apart.
 */
object VodNames {

    private val bracketed = Regex("""^\s*[\[|(]\s*([A-Za-z0-9+ ]{1,8})\s*[]|)]\s*[-:|]?\s*""")

    /*
     * An unbracketed tag has to look like one: two letters, a language, or one of the
     * words providers use for quality or service, and then a separator. Anything
     * longer or else is left alone — "CSI: Miami" is a title, not a tag and a title.
     */
    private val bare = Regex("""^\s*([A-Z]{2}|4K|UHD|FHD|HD|SD|HEVC|VIP|MULTI|NF|AMZ|DSNP|ATV|HBO|D\+)\s*[-:|]\s+""")

    private val trailingYear = Regex("""\s*(?:[(\[]\s*((?:19|20)\d{2})\s*[)\]]|[-–]\s*((?:19|20)\d{2}))\s*$""")

    data class Parsed(val name: String, val year: Int?, val tag: String?)

    fun parse(raw: String, knownYear: Int? = null): Parsed {
        var name = raw.trim()
        var tag: String? = null
        // One tag at most: "EN - 4K - Title" keeps "4K - Title" as the name's own business.
        (bracketed.find(name) ?: bare.find(name))?.let { found ->
            val rest = name.substring(found.range.last + 1).trim()
            if (rest.isNotEmpty()) {
                tag = found.groupValues[1].trim().uppercase()
                name = rest
            }
        }
        var year = knownYear
        trailingYear.find(name)?.let { found ->
            val rest = name.substring(0, found.range.first).trim()
            if (rest.isNotEmpty()) {
                year = year ?: (found.groupValues[1].ifEmpty { found.groupValues[2] }).toIntOrNull()
                name = rest
            }
        }
        return Parsed(name = name.ifEmpty { raw.trim() }, year = year, tag = tag)
    }

    fun key(name: String): String = SearchMatch.words(name).joinToString("")
}

/** The provider's on-demand side: its films and series, their pages, and their addresses. */
object XtreamVod {

    suspend fun catalog(credentials: XtreamCredentials): VodCatalog = withContext(Dispatchers.IO) {
        require(!credentials.isPlaylist) { "Films and series need an Xtream login, not a playlist." }
        VodCatalog(
            movieCategories = categories(credentials, "get_vod_categories"),
            seriesCategories = categories(credentials, "get_series_categories"),
            movies = stream(credentials, "get_vod_streams") { readTitles(it, series = false) },
            series = stream(credentials, "get_series") { readTitles(it, series = true) },
            loadedAt = System.currentTimeMillis(),
        )
    }

    private fun categories(credentials: XtreamCredentials, action: String): List<XtreamCategory> =
        runCatching {
            val array = JSONArray(fetch(credentials, action))
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
                XtreamCategory(
                    id = it.optString("category_id"),
                    name = it.optString("category_name").ifEmpty { "Unnamed" },
                )
            }.filter { it.id.isNotEmpty() }.distinctBy { it.id }
        }.getOrDefault(emptyList())

    /**
     * A whole list read as it arrives. A big provider's films run to tens of thousands
     * and tens of megabytes: read into one string first, then into objects, it would be
     * three copies of all that at once, which a stick hasn't the memory for.
     */
    internal fun readTitles(reader: Reader, series: Boolean): List<VodTitle> {
        val json = JsonReader(reader).apply { isLenient = true }
        // A refused login comes back as an object, not a list: nothing to show.
        if (json.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
        val titles = ArrayList<VodTitle>()
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue()
                continue
            }
            val fields = HashMap<String, String>()
            json.beginObject()
            while (json.hasNext()) {
                val field = json.nextName()
                if (field in WANTED) json.stringOrNull()?.let { fields[field] = it } else json.skipValue()
            }
            json.endObject()
            titleOf(fields, series)?.let(titles::add)
        }
        json.endArray()
        return titles.distinctBy { it.id }
    }

    private val WANTED = setOf(
        "stream_id", "series_id", "name", "title", "year", "stream_icon", "cover", "rating",
        "added", "last_modified", "category_id", "tmdb", "tmdb_id", "container_extension",
        "plot", "genre", "releaseDate", "release_date",
    )

    internal fun titleOf(fields: Map<String, String>, series: Boolean): VodTitle? {
        val id = fields[if (series) "series_id" else "stream_id"]?.toIntOrNull() ?: return null
        val raw = fields["name"]?.takeIf { it.isNotBlank() } ?: fields["title"] ?: return null
        val listedYear = fields["year"]?.take(4)?.toIntOrNull()
            ?: (fields["releaseDate"] ?: fields["release_date"])?.take(4)?.toIntOrNull()
        val parsed = VodNames.parse(raw, listedYear?.takeIf { it in 1900..2100 })
        return VodTitle(
            series = series,
            id = id,
            name = parsed.name,
            year = parsed.year,
            tag = parsed.tag,
            poster = (fields[if (series) "cover" else "stream_icon"])?.takeIf { it.startsWith("http") },
            rating = fields["rating"]?.toDoubleOrNull()?.takeIf { it > 0 },
            addedAt = (fields[if (series) "last_modified" else "added"])?.toLongOrNull() ?: 0,
            categoryId = fields["category_id"]?.takeIf { it.isNotBlank() },
            tmdbId = (fields["tmdb"] ?: fields["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
            extension = fields["container_extension"]?.takeIf { it.isNotBlank() },
            plot = fields["plot"]?.takeIf { it.isNotBlank() },
            genre = fields["genre"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Numbers and strings alike as text; anything else skipped. */
    private fun JsonReader.stringOrNull(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    suspend fun movieInfo(credentials: XtreamCredentials, streamId: Int): VodInfo? = withContext(Dispatchers.IO) {
        parseMovieInfo(fetch(credentials, "get_vod_info", "vod_id" to streamId.toString()))
    }

    internal fun parseMovieInfo(body: String): VodInfo? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // A panel with nothing to say sends "info": [] rather than leaving it out.
        val info = root.optJSONObject("info") ?: JSONObject()
        val movie = root.optJSONObject("movie_data") ?: JSONObject()
        return VodInfo(
            name = info.text("name") ?: movie.text("name"),
            plot = info.text("plot") ?: info.text("description"),
            cast = info.names("cast").ifEmpty { info.names("actors") },
            directors = info.names("director"),
            genres = info.names("genre"),
            durationMs = (info.optString("duration_secs").toLongOrNull() ?: durationOf(info.text("duration")))
                ?.times(1000) ?: 0,
            backdrop = info.firstUrl("backdrop_path"),
            poster = info.text("movie_image")?.takeIf { it.startsWith("http") }
                ?: info.text("cover_big")?.takeIf { it.startsWith("http") },
            releaseDate = info.text("releasedate") ?: info.text("release_date"),
            rating = info.text("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
            tmdbId = info.text("tmdb_id")?.takeIf { it != "0" },
            extension = movie.text("container_extension"),
            ageRating = info.text("age") ?: info.text("mpaa"),
        )
    }

    suspend fun seriesInfo(credentials: XtreamCredentials, seriesId: Int): SeriesInfo? = withContext(Dispatchers.IO) {
        parseSeriesInfo(fetch(credentials, "get_series_info", "series_id" to seriesId.toString()))
    }

    internal fun parseSeriesInfo(body: String): SeriesInfo? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val info = root.optJSONObject("info") ?: JSONObject()
        // Episodes come keyed by season number, or on some panels as a list of lists.
        val episodes = mutableListOf<VodEpisode>()
        fun readSeason(key: String?, list: JSONArray) {
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val id = entry.optString("id").toIntOrNull() ?: continue
                val details = entry.optJSONObject("info") ?: JSONObject()
                val season = entry.optString("season").toIntOrNull() ?: key?.toIntOrNull() ?: 1
                episodes += VodEpisode(
                    id = id,
                    season = season,
                    number = entry.optString("episode_num").toIntOrNull(),
                    title = entry.text("title")?.let { VodNames.parse(it).name } ?: "Episode ${entry.optString("episode_num")}",
                    extension = entry.text("container_extension"),
                    plot = details.text("plot"),
                    still = details.text("movie_image")?.takeIf { it.startsWith("http") },
                    durationMs = (details.optString("duration_secs").toLongOrNull() ?: durationOf(details.text("duration")))
                        ?.times(1000) ?: 0,
                    airDate = details.text("releasedate") ?: details.text("air_date"),
                )
            }
        }
        when (val all = root.opt("episodes")) {
            is JSONObject -> all.keys().forEach { key -> all.optJSONArray(key)?.let { readSeason(key, it) } }
            is JSONArray -> for (i in 0 until all.length()) all.optJSONArray(i)?.let { readSeason(null, it) }
        }
        val listed = root.optJSONArray("seasons") ?: JSONArray()
        val named = (0 until listed.length()).mapNotNull { listed.optJSONObject(it) }.associateBy {
            it.optString("season_number").toIntOrNull()
        }
        val seasons = episodes.groupBy { it.season }.toSortedMap().map { (number, list) ->
            val about = named[number]
            VodSeason(
                number = number,
                name = if (number == 0) "Specials" else "Season $number",
                poster = about?.text("cover_big")?.takeIf { it.startsWith("http") }
                    ?: about?.text("cover")?.takeIf { it.startsWith("http") },
                episodes = list.sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.id })),
            )
        }
        return SeriesInfo(
            name = info.text("name"),
            plot = info.text("plot"),
            cast = info.names("cast"),
            directors = info.names("director"),
            genres = info.names("genre"),
            backdrop = info.firstUrl("backdrop_path"),
            poster = info.text("cover")?.takeIf { it.startsWith("http") },
            releaseDate = info.text("releaseDate") ?: info.text("release_date"),
            rating = info.text("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
            tmdbId = info.text("tmdb")?.takeIf { it != "0" } ?: info.text("tmdb_id")?.takeIf { it != "0" },
            seasons = seasons,
        )
    }

    fun movieUrl(credentials: XtreamCredentials, streamId: Int, extension: String?): String = with(credentials) {
        "$base/movie/${Uri.encode(username)}/${Uri.encode(password)}/$streamId.${extension ?: "mp4"}"
    }

    fun episodeUrl(credentials: XtreamCredentials, episodeId: Int, extension: String?): String = with(credentials) {
        "$base/series/${Uri.encode(username)}/${Uri.encode(password)}/$episodeId.${extension ?: "mp4"}"
    }

    private fun JSONObject.text(name: String): String? =
        optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    /** "Keanu Reeves, Carrie-Anne Moss" as a list. */
    private fun JSONObject.names(name: String): List<String> =
        text(name)?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    private fun JSONObject.firstUrl(name: String): String? = when (val value = opt(name)) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.firstOrNull { it.startsWith("http") }
        is String -> value.takeIf { it.startsWith("http") }
        else -> null
    }

    /** "01:52:10" in seconds. */
    private fun durationOf(text: String?): Long? {
        val parts = text?.split(':')?.map { it.trim().toLongOrNull() ?: return null } ?: return null
        return parts.fold(0L) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    private fun url(credentials: XtreamCredentials, action: String, extras: Array<out Pair<String, String>>): String =
        Uri.parse("${credentials.base}/player_api.php").buildUpon()
            .appendQueryParameter("username", credentials.username)
            .appendQueryParameter("password", credentials.password)
            .appendQueryParameter("action", action)
            .apply { extras.forEach { (key, value) -> appendQueryParameter(key, value) } }
            .build()
            .toString()

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "ReelyTV/${tv.reely.BuildConfig.VERSION_NAME} (Android TV)")
        .get()
        .build()

    private fun fetch(credentials: XtreamCredentials, action: String, vararg extras: Pair<String, String>): String =
        Http.client.newCall(request(url(credentials, action, extras))).execute().use { response ->
            require(response.isSuccessful) { "Your provider couldn't do that. Try again." }
            response.body?.string().orEmpty()
        }

    private fun <T> stream(credentials: XtreamCredentials, action: String, read: (Reader) -> T): T =
        Http.bulk.newCall(request(url(credentials, action, emptyArray()))).execute().use { response ->
            require(response.isSuccessful) { "Your provider couldn't send its films and series. Try again." }
            val body = response.body ?: error("Your provider didn't respond. Try again.")
            read(body.charStream())
        }
}

/**
 * The catalogue kept on the device, so the tabs have it at once when the app starts
 * rather than after a minute's download; it's read again from the provider in the
 * background when it's old.
 */
object VodCatalogCache {

    fun file(dir: File, credentials: XtreamCredentials): File =
        File(dir, "vod-" + (credentials.base + "|" + credentials.username).hashCode().toUInt().toString(16) + ".json")

    fun write(file: File, catalog: VodCatalog) {
        val temp = File(file.parentFile, file.name + ".part")
        temp.bufferedWriter().use { out ->
            JsonWriter(out).use { json ->
                json.beginObject()
                json.name("loadedAt").value(catalog.loadedAt)
                json.name("movieCategories"); writeCategories(json, catalog.movieCategories)
                json.name("seriesCategories"); writeCategories(json, catalog.seriesCategories)
                json.name("movies"); writeTitles(json, catalog.movies)
                json.name("series"); writeTitles(json, catalog.series)
                json.endObject()
            }
        }
        temp.renameTo(file)
    }

    fun read(file: File): VodCatalog? = runCatching {
        if (!file.exists()) return null
        file.bufferedReader().use { input ->
            val json = JsonReader(input)
            var catalog = VodCatalog()
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "loadedAt" -> catalog = catalog.copy(loadedAt = json.nextLong())
                    "movieCategories" -> catalog = catalog.copy(movieCategories = readCategories(json))
                    "seriesCategories" -> catalog = catalog.copy(seriesCategories = readCategories(json))
                    "movies" -> catalog = catalog.copy(movies = readTitles(json, series = false))
                    "series" -> catalog = catalog.copy(series = readTitles(json, series = true))
                    else -> json.skipValue()
                }
            }
            json.endObject()
            catalog
        }
    }.getOrNull()

    private fun writeCategories(json: JsonWriter, categories: List<XtreamCategory>) {
        json.beginArray()
        categories.forEach { json.beginArray().value(it.id).value(it.name).endArray() }
        json.endArray()
    }

    private fun readCategories(json: JsonReader): List<XtreamCategory> {
        val list = mutableListOf<XtreamCategory>()
        json.beginArray()
        while (json.hasNext()) {
            json.beginArray()
            list += XtreamCategory(json.nextString(), json.nextString())
            json.endArray()
        }
        json.endArray()
        return list
    }

    // Each title as a short array: the file is read at every start, so the less in it the better.
    private fun writeTitles(json: JsonWriter, titles: List<VodTitle>) {
        json.beginArray()
        titles.forEach { t ->
            json.beginArray()
            json.value(t.id.toLong())
            json.value(t.name)
            if (t.year != null) json.value(t.year.toLong()) else json.nullValue()
            json.value(t.tag)
            json.value(t.poster)
            if (t.rating != null) json.value(t.rating) else json.nullValue()
            json.value(t.addedAt)
            json.value(t.categoryId)
            json.value(t.tmdbId)
            json.value(t.extension)
            json.value(t.plot)
            json.value(t.genre)
            json.endArray()
        }
        json.endArray()
    }

    private fun readTitles(json: JsonReader, series: Boolean): List<VodTitle> {
        val list = ArrayList<VodTitle>()
        fun str(): String? = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else json.nextString()
        json.beginArray()
        while (json.hasNext()) {
            json.beginArray()
            val id = json.nextInt()
            val name = json.nextString()
            val year = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else json.nextInt()
            val tag = str()
            val poster = str()
            val rating = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else json.nextDouble()
            val added = json.nextLong()
            val category = str()
            val tmdb = str()
            val extension = str()
            val plot = str()
            val genre = str()
            while (json.hasNext()) json.skipValue()
            json.endArray()
            list += VodTitle(series, id, name, year, tag, poster, rating, added, category, tmdb, extension, plot, genre)
        }
        json.endArray()
        return list
    }
}
