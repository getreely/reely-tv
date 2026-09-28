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

/**
 * What one of a title's files is called when there's a choice of them: "4K Dolby Vision",
 * "1080p". The resolution as a number, which is how a person tells two copies apart.
 */
fun versionLabel(resolution: String?, dolbyVision: Boolean = false, transfer: String? = null): String {
    val size = when (val r = resolution?.lowercase()) {
        null, "" -> "Other"
        "4k", "2160" -> "4K"
        "sd" -> "SD"
        else -> if (r.all(Char::isDigit)) "${r}p" else r.uppercase()
    }
    val range = when {
        dolbyVision -> "Dolby Vision"
        transfer.equals("smpte2084", ignoreCase = true) -> "HDR10"
        transfer.equals("arib-std-b67", ignoreCase = true) -> "HLG"
        else -> null
    }
    return listOfNotNull(size, range).joinToString(" ")
}

/** The rest of what tells versions apart: "HEVC · TrueHD 7.1 · 42 Mbps · 58.1 GB". */
fun versionDetail(
    videoCodec: String?,
    audioCodec: String?,
    audioChannels: Int,
    bitrateKbps: Int,
    sizeBytes: Long,
): String? {
    val video = when (videoCodec?.lowercase()) {
        null, "" -> null
        "hevc", "h265" -> "HEVC"
        "h264" -> "H.264"
        "mpeg2video" -> "MPEG-2"
        "mpeg4" -> "MPEG-4"
        "vc1" -> "VC-1"
        else -> videoCodec.uppercase()
    }
    val sound = when (audioCodec?.lowercase()) {
        null, "" -> null
        "truehd" -> "TrueHD"
        "eac3" -> "Dolby Digital Plus"
        "ac3" -> "Dolby Digital"
        "dca", "dts" -> "DTS"
        "dca-ma", "dts-hd ma" -> "DTS-HD MA"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        else -> audioCodec.uppercase()
    }?.let { name ->
        val layout = when {
            audioChannels >= 8 -> "7.1"
            audioChannels >= 6 -> "5.1"
            audioChannels == 2 -> "Stereo"
            else -> null
        }
        listOfNotNull(name, layout).joinToString(" ")
    }
    val rate = bitrateKbps.takeIf { it > 0 }?.let {
        if (it >= 1000) "${(it + 500) / 1000} Mbps" else "$it kbps"
    }
    val size = sizeBytes.takeIf { it > 0 }?.let {
        val gb = it / 1_000_000_000.0
        if (gb >= 1) String.format(java.util.Locale.US, "%.1f GB", gb) else "${it / 1_000_000} MB"
    }
    return listOfNotNull(video, sound, rate, size).joinToString(" · ").ifEmpty { null }
}
