package tv.reely.core

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import android.os.Handler
import androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The players' decoders: the device's own first, then the app's FFmpeg decoder for any
 * sound the device cannot decode or pass through — Dolby Digital and Digital Plus,
 * TrueHD, DTS, MP2.
 *
 * "On" rather than "prefer": a device that can decode Dolby, or pass it to a receiver
 * that can, keeps doing so. FFmpeg only takes what would otherwise play silent.
 *
 * Video is the device's too, its hardware being the only thing fast enough, but for one
 * family: MPEG-4 part 2 (Xvid, DivX) and its relations, the small pictures old television
 * is kept in. A Fire TV's decoders for those are unreliable — a 720 by 480 Xvid episode
 * failed on the stick's and played green once the server had copied it into a stream —
 * so the app's FFmpeg decoder is asked first, and takes only those. Plex's own app plays
 * them the same way, which is why the file is "Original" there.
 */
fun renderersFor(context: Context): RenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            // Ahead of the device's: the first renderer that takes a format plays it.
            out.add(
                FfmpegVideoRenderer(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                )
            )
            super.buildVideoRenderers(
                context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
            )
        }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
