package tv.reely.core

import org.json.JSONArray
import org.json.JSONObject

/** A programme somebody asked to be told about when it starts. */
data class Reminder(
    val streamId: Int,
    val channelName: String,
    val title: String,
    /** Epoch seconds. */
    val start: Long,
) {
    /** One reminder per programme: the same channel at the same time. */
    val key: String get() = "$streamId:$start"
}

/** How reminders are kept in settings: a JSON list, read back leniently. */
object Reminders {
    fun encode(reminders: List<Reminder>): String = JSONArray().apply {
        reminders.forEach {
            put(
                JSONObject()
                    .put("streamId", it.streamId)
                    .put("channel", it.channelName)
                    .put("title", it.title)
                    .put("start", it.start)
            )
        }
    }.toString()

    fun decode(raw: String?): List<Reminder> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull {
            Reminder(
                streamId = it.optInt("streamId", -1).takeIf { id -> id >= 0 } ?: return@mapNotNull null,
                channelName = it.optString("channel"),
                title = it.optString("title"),
                start = it.optLong("start").takeIf { s -> s > 0 } ?: return@mapNotNull null,
            )
        }
    }

    /**
     * What's still to come, soonest first. One that started a little while ago still
     * counts, so a television switched on just after the start still says so.
     */
    fun upcoming(reminders: List<Reminder>, nowSeconds: Long): List<Reminder> =
        reminders.filter { it.start >= nowSeconds - GRACE_SECONDS }.sortedBy { it.start }

    /** How long after a programme starts its reminder is still worth showing. */
    const val GRACE_SECONDS = 10L * 60
}
