package tv.reely

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.ui.screens.pictureSummary
import tv.reely.ui.screens.soundSummary

/** The plain-words lines at the top of Playback info. */
class PlaybackSummaryTest {
    @Test fun picture() {
        val hdr = Format.Builder().setWidth(3840).setHeight(2160).setFrameRate(23.976f)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()
        assertEquals("4K  ·  HDR10  ·  24.0 fps", pictureSummary(hdr))
        // A letterboxed film is narrower than 1080 lines but still 1080p.
        assertEquals("1080p", pictureSummary(Format.Builder().setWidth(1920).setHeight(800).build()))
        assertEquals(null, pictureSummary(Format.Builder().build()))
    }

    @Test fun sound() {
        val ddp = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).build()
        assertEquals("Dolby Digital Plus  ·  5.1", soundSummary(ddp))
        assertEquals("AAC  ·  Stereo", soundSummary(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).build()))
    }
}
