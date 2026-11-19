package androidx.media3.decoder.ffmpeg;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.Objects;

/**
 * Plays old video through {@link FfmpegVideoDecoder}, drawn onto the player's surface by
 * {@link YuvSurfaceDrawer}. Reely's own, in place of media3's unfinished one.
 *
 * <p>It takes only what the decoder decodes, at sizes a stick's processor keeps up with;
 * everything else is left to the device's own decoders, which are the only thing fast
 * enough for modern video.
 */
@UnstableApi
public final class FfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "FfmpegVideoRenderer";

  /** The biggest picture taken: past DVD and PAL sizes, with room for a 720p Xvid. */
  private static final int MAX_PIXELS = 1280 * 720;

  private static final int NUM_INPUT_BUFFERS = 8;
  private static final int NUM_OUTPUT_BUFFERS = 4;
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 512 * 1024;

  private final int threads;
  private final YuvSurfaceDrawer drawer = new YuvSurfaceDrawer();
  @Nullable private FfmpegVideoDecoder decoder;

  public FfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    // A couple of cores for the picture leaves the rest for the sound and the screen.
    threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
  }

  @Override
  public String getName() {
    return TAG;
  }

  /** Whether this renderer would take [format]: for the app to say so, or to ask first. */
  public static boolean takes(Format format) {
    if (!FfmpegVideoDecoder.decodes(format.sampleMimeType)) {
      return false;
    }
    boolean sized = format.width != Format.NO_VALUE && format.height != Format.NO_VALUE;
    return !sized || (long) format.width * format.height <= MAX_PIXELS;
  }

  @Override
  public @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    if (FfmpegVideoDecoder.codecNameFor(format.sampleMimeType) == null) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (!takes(format)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    return RendererCapabilities.create(C.FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegVideoDecoder");
    int inputSize = format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    FfmpegVideoDecoder created =
        new FfmpegVideoDecoder(NUM_INPUT_BUFFERS, NUM_OUTPUT_BUFFERS, inputSize, format, threads);
    decoder = created;
    TraceUtil.endSection();
    return created;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    try {
      drawer.draw(surface, outputBuffer);
    } catch (RuntimeException e) {
      throw new FfmpegDecoderException("The picture couldn't be drawn.", e);
    } finally {
      outputBuffer.release();
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  /**
   * The surface let go of before it changes or goes: drawing holds a connection to it, and
   * while that's held nothing else can draw there. The device's own decoder is next on the
   * same surface whenever the next thing playing is modern video.
   */
  @Override
  public void handleMessage(@MessageType int messageType, @Nullable Object message)
      throws ExoPlaybackException {
    if (messageType == MSG_SET_VIDEO_OUTPUT) {
      drawer.release();
    }
    super.handleMessage(messageType, message);
  }

  @Override
  protected void onDisabled() {
    try {
      super.onDisabled();
    } finally {
      drawer.release();
      decoder = null;
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(String decoderName, Format oldFormat, Format newFormat) {
    boolean same =
        Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType)
            && oldFormat.initializationDataEquals(newFormat);
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        same ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : REUSE_RESULT_NO,
        same ? 0 : DISCARD_REASON_MIME_TYPE_CHANGED);
  }
}
