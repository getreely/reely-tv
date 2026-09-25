package tv.reely.core

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory

/**
 * The players' decoders: the device's own first, then the app's FFmpeg decoder for any
 * sound the device cannot decode or pass through — Dolby Digital and Digital Plus,
 * TrueHD, DTS, MP2. Video is always the device's: its hardware is the only thing fast
 * enough, and it plays everything the app is asked to.
 *
 * "On" rather than "prefer": a device that can decode Dolby, or pass it to a receiver
 * that can, keeps doing so. FFmpeg only takes what would otherwise play silent.
 */
fun renderersFor(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
