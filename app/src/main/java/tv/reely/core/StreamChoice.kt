package tv.reely.core

import androidx.media3.common.util.Util

/**
 * One of a file's sound or subtitle streams as Plex lists it, in the file's own order.
 *
 * `selected` is the server's choice for this account. It comes from the account's
 * language settings and from whatever was last picked for the title on any Plex app.
 */
data class PlexStream(
    val id: String,
    val language: String?,
    val selected: Boolean,
    /** A separate file next to the video, which the player loads as a sidecar. */
    val external: Boolean = false,
)

/** A track as the player sees it: its format's id and language, and whether it's a sidecar. */
data class PlayerTrack(val id: String?, val language: String?, val sidecar: Boolean)

/**
 * Sidecar subtitles are given this id, so the track can be matched to the stream it
 * came from.
 */
fun sidecarId(streamId: String) = "plex-$streamId"

/**
 * Which of the player's tracks is the stream the server has selected, or null when it
 * hasn't selected one or it can't be told apart.
 *
 * Streams inside the file are matched by position, since the file lists them in the
 * same order for both. When the counts disagree, the first track in the same language
 * is used instead. Sidecars are matched by the id they were given.
 */
fun playerTrackFor(streams: List<PlexStream>, tracks: List<PlayerTrack>): Int? {
    val chosen = streams.firstOrNull { it.selected } ?: return null
    if (chosen.external) {
        val id = sidecarId(chosen.id)
        return tracks.indexOfFirst { it.sidecar && it.id?.endsWith(id) == true }.takeIf { it >= 0 }
    }
    val inFile = streams.filter { !it.external }
    val fileTracks = tracks.withIndex().filter { !it.value.sidecar }
    if (fileTracks.size == inFile.size) return fileTracks[inFile.indexOf(chosen)].index
    val language = normalize(chosen.language) ?: return null
    return fileTracks.firstOrNull { normalize(it.value.language) == language }?.index
}

/**
 * The server's stream for a track the player has, so a choice made here can be saved
 * back to Plex. This is the reverse of [playerTrackFor].
 */
fun plexStreamFor(streams: List<PlexStream>, tracks: List<PlayerTrack>, trackIndex: Int): PlexStream? {
    val track = tracks.getOrNull(trackIndex) ?: return null
    if (track.sidecar) {
        return streams.firstOrNull { it.external && track.id?.endsWith(sidecarId(it.id)) == true }
    }
    val inFile = streams.filter { !it.external }
    val fileTracks = tracks.withIndex().filter { !it.value.sidecar }
    if (fileTracks.size == inFile.size) return inFile[fileTracks.indexOfFirst { it.index == trackIndex }]
    val language = normalize(track.language) ?: return null
    return inFile.singleOrNull { normalize(it.language) == language }
}

private fun normalize(language: String?): String? =
    language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Util.normalizeLanguageCode(it).substringBefore('-') }
