package tv.reely.core

/**
 * The badges under a title that say what the file is: resolution, HDR, and sound.
 *
 * Plex reports resolution as "4k", "1080", "720", "sd" and so on; a television viewer
 * thinks in 4K, HD and SD, so that is what is shown. HDR comes from the video stream's
 * transfer function — SMPTE 2084 is HDR10, ARIB STD-B67 is HLG — and Dolby Vision from
 * its own flag, which wins because a Dolby Vision file usually carries HDR10 as well.
 * Plex only sends stream details on a full item, so a title seen in a list may simply
 * not know its HDR yet, and says nothing rather than guessing.
 */
fun qualityBadges(
    resolution: String?,
    audioChannels: Int,
    dolbyVision: Boolean = false,
    transfer: String? = null,
): List<String> = listOfNotNull(
    when (resolution?.lowercase()) {
        "4k", "2160" -> "4K"
        "1080", "720" -> "HD"
        "sd", "576", "480" -> "SD"
        else -> null
    },
    when {
        dolbyVision -> "Dolby Vision"
        transfer.equals("smpte2084", ignoreCase = true) -> "HDR10"
        transfer.equals("arib-std-b67", ignoreCase = true) -> "HLG"
        else -> null
    },
    when {
        audioChannels >= 8 -> "7.1"
        audioChannels >= 6 -> "5.1"
        audioChannels == 2 -> "Stereo"
        audioChannels == 1 -> "Mono"
        else -> null
    },
)
