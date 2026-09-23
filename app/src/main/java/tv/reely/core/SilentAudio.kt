package tv.reely.core

/** What to do about a file whose sound this device cannot play. */
enum class SilentAudio {
    /** There is sound, or nothing to play: nothing to do. */
    FINE,

    /** Ask the Plex server to convert the audio and leave the picture alone. */
    CONVERT_ON_SERVER,

    /** Nobody can convert it from here. Say so, rather than play on in silence. */
    EXPLAIN,
}

/**
 * Decides what to do when a file has sound the player could not select.
 *
 * That case raises no error: the player picks no audio track and plays the picture in
 * silence, so the fallback that handles undecodable files never fires. Dolby Digital Plus
 * is the common cause — a stick with no decoder for it, on a television that will not
 * take it over HDMI.
 *
 * The server can only help with something it is serving. A live channel comes straight
 * from the provider, and a stream the server is already converting has had its chance;
 * asking again would loop. Direct-only means the viewer said not to ask. In all three the
 * useful thing is to explain, because silence on its own looks like a broken remote or
 * a muted television.
 */
fun silentAudio(
    hasAudio: Boolean,
    audioSelected: Boolean,
    isLive: Boolean,
    fromPlex: Boolean,
    alreadyTranscoding: Boolean,
    directOnly: Boolean,
): SilentAudio = when {
    !hasAudio || audioSelected -> SilentAudio.FINE
    isLive || !fromPlex || alreadyTranscoding || directOnly -> SilentAudio.EXPLAIN
    else -> SilentAudio.CONVERT_ON_SERVER
}
