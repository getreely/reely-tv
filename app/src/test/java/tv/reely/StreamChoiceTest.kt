package tv.reely

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.PlayerTrack
import tv.reely.core.PlexStream
import tv.reely.core.playerTrackFor
import tv.reely.core.plexStreamFor
import tv.reely.core.Settings
import tv.reely.core.sidecarId
import tv.reely.core.startsWithServerSubtitles
import tv.reely.plex.PlexApi

/** Plex's choice of sound and subtitles, found among the player's tracks and back again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamChoiceTest {
    private val japaneseChosen = listOf(
        PlexStream("11", "en", selected = false),
        PlexStream("12", "ja", selected = true),
    )
    private val twoInFile = listOf(
        PlayerTrack("1", "en", sidecar = false),
        PlayerTrack("2", "ja", sidecar = false),
    )

    @Test fun `the chosen sound is the track in the same place`() {
        assertEquals(1, playerTrackFor(japaneseChosen, twoInFile))
    }

    @Test fun `nothing chosen leaves the player to it`() {
        assertNull(playerTrackFor(japaneseChosen.map { it.copy(selected = false) }, twoInFile))
    }

    @Test fun `when the counts differ, the language decides`() {
        val three = listOf(
            PlayerTrack("1", "en", sidecar = false),
            PlayerTrack("2", "en", sidecar = false),
            PlayerTrack("3", "ja", sidecar = false),
        )
        assertEquals(2, playerTrackFor(listOf(PlexStream("12", "jpn", selected = true)), three))
    }

    @Test fun `a subtitle file next to the video is found by its id`() {
        val streams = listOf(
            PlexStream("20", "en", selected = false),
            PlexStream("21", "es", selected = true, external = true),
        )
        val tracks = listOf(
            PlayerTrack("3", "en", sidecar = false),
            PlayerTrack("1:${sidecarId("21")}", "es", sidecar = true),
        )
        assertEquals(1, playerTrackFor(streams, tracks))
        assertEquals("21", plexStreamFor(streams, tracks, 1)?.id)
        assertEquals("20", plexStreamFor(streams, tracks, 0)?.id)
    }

    @Test fun `a pick in the player is saved as the stream it came from`() {
        assertEquals("11", plexStreamFor(japaneseChosen, twoInFile, 0)?.id)
        assertEquals("12", plexStreamFor(japaneseChosen, twoInFile, 1)?.id)
        assertNull(plexStreamFor(japaneseChosen, twoInFile, 5))
    }

    @Test fun `the server's streams are read with its choice`() {
        val part = JSONObject(
            """
            {"id":7,"Stream":[
              {"id":1,"streamType":1,"codec":"h264"},
              {"id":2,"streamType":2,"languageCode":"eng","selected":true},
              {"id":3,"streamType":2,"languageTag":"ja"},
              {"id":4,"streamType":3,"languageTag":"en","selected":1},
              {"id":5,"streamType":3,"languageTag":"es","key":"/library/streams/5"}]}
            """.trimIndent()
        )
        val audio = PlexApi.streamsOf(part, PlexApi.AUDIO_STREAM)
        assertEquals(listOf("2", "3"), audio.map { it.id })
        assertEquals(listOf(true, false), audio.map { it.selected })
        assertEquals("eng", audio[0].language)
        val subtitles = PlexApi.streamsOf(part, PlexApi.SUBTITLE_STREAM)
        assertEquals(listOf(false, true), subtitles.map { it.external })
        assertEquals(true, subtitles[0].selected)
    }

    @Test fun `forced subtitles are read as forced`() {
        val part = JSONObject(
            """
            {"id":7,"Stream":[
              {"id":4,"streamType":3,"languageTag":"en","forced":true},
              {"id":5,"streamType":3,"languageTag":"en","forced":"1"},
              {"id":6,"streamType":3,"languageTag":"en"}]}
            """.trimIndent()
        )
        assertEquals(listOf(true, true, false), PlexApi.streamsOf(part, PlexApi.SUBTITLE_STREAM).map { it.forced })
    }

    @Test fun `with subtitles off at the start, only a forced choice of Plex's is kept`() {
        val chosen = listOf(PlexStream("4", "en", selected = true), PlexStream("5", "en", selected = false))
        val forced = listOf(PlexStream("4", "en", selected = true, forced = true))
        assertEquals(true, startsWithServerSubtitles(chosen, Settings.SUBTITLES_PLEX))
        assertEquals(false, startsWithServerSubtitles(chosen, Settings.SUBTITLES_OFF))
        assertEquals(true, startsWithServerSubtitles(forced, Settings.SUBTITLES_OFF))
        assertEquals(false, startsWithServerSubtitles(emptyList(), Settings.SUBTITLES_OFF))
    }
}
