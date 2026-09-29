package tv.reely.core

import org.json.JSONArray
import org.json.JSONObject
import tv.reely.plex.PlexItem
import java.io.File
import java.security.MessageDigest

/**
 * Home's rows as they last were, kept on the device so the next start has something to
 * show straight away instead of an empty screen while every server is asked again. The
 * rows are replaced as soon as fresh ones arrive.
 *
 * Kept per Plex account and profile: the file says whose rows they are, and anybody
 * else's start begins empty rather than showing them what somebody else was watching.
 */
object HomeCache {
    private const val FILE = "home-rows.json"
    private const val VERSION = 1

    /** What's kept: the three rows that are Home at a glance. */
    data class Rows(
        val continueWatching: List<PlexItem>,
        val recentMovies: List<PlexItem>,
        /** The newest of each show's recent episodes, with how many arrived. */
        val recentEpisodes: List<Pair<PlexItem, Int>>,
    )

    fun save(dir: File, owner: String, rows: Rows) {
        val json = JSONObject()
            .put("version", VERSION)
            .put("owner", ownerKey(owner))
            .put("continueWatching", JSONArray(rows.continueWatching.map(::encode)))
            .put("recentMovies", JSONArray(rows.recentMovies.map(::encode)))
            .put(
                "recentEpisodes",
                JSONArray(rows.recentEpisodes.map { (item, count) -> encode(item).put("groupCount", count) }),
            )
        runCatching {
            val file = File(dir, FILE)
            val temp = File(dir, "$FILE.tmp")
            temp.writeText(json.toString())
            temp.renameTo(file)
        }
    }

    /** The rows kept for [owner], or null when there are none, or they're somebody else's. */
    fun load(dir: File, owner: String): Rows? = runCatching {
        val json = JSONObject(File(dir, FILE).takeIf { it.exists() }?.readText() ?: return null)
        if (json.optInt("version") != VERSION || json.optString("owner") != ownerKey(owner)) return null
        fun list(name: String) = json.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::decode) } }.orEmpty()
        val episodes = json.optJSONArray("recentEpisodes")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val entry = a.optJSONObject(i) ?: return@mapNotNull null
                decode(entry)?.let { it to entry.optInt("groupCount", 1) }
            }
        }.orEmpty()
        Rows(list("continueWatching"), list("recentMovies"), episodes)
    }.getOrNull()

    fun clear(dir: File) {
        File(dir, FILE).delete()
    }

    /** Whose rows: a hash, so the account's token itself is never written down here. */
    private fun ownerKey(owner: String): String =
        MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun encode(item: PlexItem): JSONObject = JSONObject()
        .put("ratingKey", item.ratingKey)
        .put("title", item.title)
        .put("type", item.type)
        .putOpt("thumb", item.thumb)
        .putOpt("art", item.art)
        .putOpt("summary", item.summary)
        .putOpt("year", item.year)
        .putOpt("index", item.index)
        .putOpt("parentIndex", item.parentIndex)
        .putOpt("parentRatingKey", item.parentRatingKey)
        .putOpt("parentTitle", item.parentTitle)
        .putOpt("grandparentRatingKey", item.grandparentRatingKey)
        .putOpt("grandparentTitle", item.grandparentTitle)
        .putOpt("grandparentThumb", item.grandparentThumb)
        .put("durationMs", item.durationMs)
        .put("viewOffsetMs", item.viewOffsetMs)
        .put("leafCount", item.leafCount)
        .put("viewedLeafCount", item.viewedLeafCount)
        .put("viewCount", item.viewCount)
        .put("addedAt", item.addedAt)
        .put("lastViewedAt", item.lastViewedAt)
        .putOpt("logo", item.logo)
        .put("qualities", JSONArray(item.qualities))
        .putOpt("airDate", item.airDate)
        .putOpt("titleSort", item.titleSort)
        .putOpt("librarySectionId", item.librarySectionId)
        .putOpt("serverBase", item.serverBase)

    internal fun decode(o: JSONObject): PlexItem? {
        val key = o.optString("ratingKey").takeIf { it.isNotBlank() } ?: return null
        fun str(name: String) = if (o.has(name) && !o.isNull(name)) o.optString(name) else null
        fun int(name: String) = if (o.has(name) && !o.isNull(name)) o.optInt(name) else null
        return PlexItem(
            ratingKey = key,
            title = o.optString("title"),
            type = o.optString("type"),
            thumb = str("thumb"),
            art = str("art"),
            summary = str("summary"),
            year = int("year"),
            index = int("index"),
            parentIndex = int("parentIndex"),
            parentRatingKey = str("parentRatingKey"),
            parentTitle = str("parentTitle"),
            grandparentRatingKey = str("grandparentRatingKey"),
            grandparentTitle = str("grandparentTitle"),
            grandparentThumb = str("grandparentThumb"),
            durationMs = o.optLong("durationMs"),
            viewOffsetMs = o.optLong("viewOffsetMs"),
            leafCount = o.optInt("leafCount"),
            viewedLeafCount = o.optInt("viewedLeafCount"),
            viewCount = o.optInt("viewCount"),
            addedAt = o.optLong("addedAt"),
            lastViewedAt = o.optLong("lastViewedAt"),
            logo = str("logo"),
            qualities = o.optJSONArray("qualities")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            airDate = str("airDate"),
            titleSort = str("titleSort"),
            librarySectionId = str("librarySectionId"),
            serverBase = str("serverBase"),
        )
    }
}
