package tv.reely.core

import kotlin.math.min

/**
 * What to do about a file's sound before playing it. Codec names are Plex's own —
 * `aac`, `ac3`, `eac3`, `truehd`, `dca` — because that is what the file is described in
 * and what the server is asked for.
 */
sealed interface AudioPlan {
    /** The device plays the sound as it is. */
    data object PlayAsIs : AudioPlan

    /**
     * The device cannot play the sound, so the server converts it into the first of
     * [codecs] it can make. Every one of them is something this device plays.
     */
    data class Convert(val codecs: List<String>) : AudioPlan
}

/**
 * Decides before playback, from what the file says it is and what the device says it
 * plays, rather than finding out afterwards from a silence and restarting.
 *
 * [canPlay] answers for a codec at a channel count, because a device that passes 5.1 to
 * a soundbar may still refuse more channels than that. An unknown source is played as it
 * is: the check made during playback is still there to catch it.
 */
fun audioPlan(
    sourceCodec: String?,
    sourceChannels: Int,
    canPlay: (codec: String, channels: Int) -> Boolean,
): AudioPlan {
    val codec = sourceCodec?.lowercase()?.takeIf(String::isNotBlank) ?: return AudioPlan.PlayAsIs
    if (canPlay(codec, sourceChannels)) return AudioPlan.PlayAsIs
    return AudioPlan.Convert(conversionTargets(sourceChannels, canPlay))
}

/**
 * What the server may convert into, best first.
 *
 * Surround sound is kept when the device can take it: Dolby Digital Plus first, then
 * Dolby Digital, each only if this device actually plays it. AAC always ends the list,
 * because every Android device decodes it — so the list can never be made only of things
 * that would leave the viewer in silence again. A stereo source goes straight to AAC:
 * there are no extra channels to keep, and nothing to gain from Dolby.
 *
 * Neither Dolby format carries more than 5.1, so that is the most asked about.
 */
fun conversionTargets(
    sourceChannels: Int,
    canPlay: (codec: String, channels: Int) -> Boolean,
): List<String> {
    if (sourceChannels <= 2) return listOf(AAC)
    val channels = min(sourceChannels, SURROUND_CHANNELS)
    return SURROUND_TARGETS.filter { canPlay(it, channels) } + AAC
}

/** The one conversion every Android device plays. */
const val AAC = "aac"

private const val SURROUND_CHANNELS = 6
private val SURROUND_TARGETS = listOf("eac3", "ac3")
