package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.PriorityQueue;

/**
 * Old video decoded by FFmpeg, in the app: MPEG-4 part 2 (Xvid, DivX), its Microsoft
 * variants and H.263. Reely's own; media3's module leaves video out. MPEG-2 is left to
 * the device: its decoders play it, live channels come in it, and they take the
 * interlacing out, which this doesn't.
 *
 * <p>A Fire TV's own decoders for these are unreliable: a 720 by 480 Xvid episode failed
 * on the stick's and, copied into a stream by the server, played green, where the same
 * show at 384 by 288 played. Decoded here, the file plays as it is, as Plex's own app
 * plays it. The pictures are small, which is what makes decoding them without the
 * stick's hardware fast enough.
 *
 * <p>One packet in, at most one picture out. With B-frames the picture that comes out is
 * the next to show, not the one just put in, so each takes the earliest time still
 * waiting: right whether the file gave times in the order shown (MKV, MP4) or the order
 * stored (AVI).
 */
@UnstableApi
public final class FfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  // Kept in step with ffmpeg_video_jni.cc.
  private static final int VIDEO_FRAME_READY = 0;
  private static final int VIDEO_NO_FRAME = 1;
  private static final int VIDEO_ERROR_PICTURE_FORMAT = -2;

  /** More times waiting than any decoder holds pictures back: something's stuck, start again. */
  private static final int MAX_PENDING_TIMES = 64;

  private final String codecName;
  private final PriorityQueue<Long> pendingTimesUs = new PriorityQueue<>();
  private long nativeContext;
  private volatile @C.VideoOutputMode int outputMode;

  /** FFmpeg's name for the decoder of [mimeType], or null when this doesn't decode it. */
  @Nullable
  public static String codecNameFor(@Nullable String mimeType) {
    if (mimeType == null) {
      return null;
    }
    switch (mimeType) {
      case "video/mp4v-es":
      case "video/divx":
        return "mpeg4";
      case "video/mp43":
        // FFmpeg's name for version 3 has no number.
        return "msmpeg4";
      case "video/mp42":
        return "msmpeg4v2";
      case "video/3gpp":
        return "h263";
      default:
        return null;
    }
  }

  /** Whether the library is there and has a decoder for [mimeType]. */
  public static boolean decodes(@Nullable String mimeType) {
    String name = codecNameFor(mimeType);
    if (name == null || !FfmpegLibrary.isAvailable()) {
      return false;
    }
    try {
      return ffmpegVideoHasDecoder(name);
    } catch (UnsatisfiedLinkError e) {
      // An older build of the library, with sound only.
      return false;
    }
  }

  public FfmpegVideoDecoder(
      int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, Format format, int threads)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    @Nullable String name = codecNameFor(format.sampleMimeType);
    if (name == null) {
      throw new FfmpegDecoderException("No decoder for " + format.sampleMimeType);
    }
    codecName = name;
    @Nullable byte[] extraData = format.initializationData.isEmpty() ? null : format.initializationData.get(0);
    nativeContext =
        ffmpegVideoInit(
            name,
            extraData,
            format.width != Format.NO_VALUE ? format.width : 0,
            format.height != Format.NO_VALUE ? format.height : 0,
            threads);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize the " + name + " decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  /** Sets what the pictures are for: a surface, or planes handed over. */
  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      ffmpegVideoFlush(nativeContext);
      pendingTimesUs.clear();
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    if (pendingTimesUs.size() >= MAX_PENDING_TIMES) {
      pendingTimesUs.clear();
    }
    pendingTimesUs.add(inputBuffer.timeUs);

    int result = ffmpegVideoDecode(nativeContext, inputData, inputSize);
    if (result == VIDEO_ERROR_PICTURE_FORMAT) {
      return new FfmpegDecoderException("The picture isn't 8-bit 4:2:0, which is all this draws.");
    }
    if (result < 0) {
      return new FfmpegDecoderException("The " + codecName + " decoder stopped.");
    }
    if (result == VIDEO_NO_FRAME) {
      outputBuffer.shouldBeSkipped = true;
      return null;
    }

    @Nullable Long earliest = pendingTimesUs.poll();
    long timeUs = earliest != null ? earliest : inputBuffer.timeUs;
    if (!isAtLeastOutputStartTimeUs(timeUs)) {
      // Decoded for the pictures after it to build on, after a jump; not one to show.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    int width = ffmpegVideoWidth(nativeContext);
    int height = ffmpegVideoHeight(nativeContext);
    if (width <= 0 || height <= 0) {
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    int yStride = (width + 15) & ~15;
    int uvStride = (((width + 1) / 2) + 15) & ~15;
    outputBuffer.init(timeUs, outputMode, /* supplementalData= */ null);
    if (!outputBuffer.initForYuvFrame(
        width, height, yStride, uvStride, VideoDecoderOutputBuffer.COLORSPACE_BT601)) {
      return new FfmpegDecoderException("The picture is too big to hold: " + width + "x" + height);
    }
    if (ffmpegVideoCopyFrame(nativeContext, Util.castNonNull(outputBuffer.data), yStride, uvStride)
        != VIDEO_FRAME_READY) {
      return new FfmpegDecoderException("The picture couldn't be copied out.");
    }
    // The picture as stored, for drawing; the width it's shown at is what the size said to
    // the screen goes by. A DVD's 720 by 480 has pixels narrower than they're tall, and the
    // AVI around it doesn't say so: only the stream does.
    outputBuffer.decoderPrivate = width;
    int sarNum = ffmpegVideoSarNum(nativeContext);
    int sarDen = ffmpegVideoSarDen(nativeContext);
    if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
      outputBuffer.width = Math.max(1, Math.round(width * (float) sarNum / sarDen));
    }
    outputBuffer.format = inputBuffer.format;
    return null;
  }

  @Override
  public void release() {
    super.release();
    ffmpegVideoRelease(nativeContext);
    nativeContext = 0;
  }

  private static native boolean ffmpegVideoHasDecoder(String codecName);

  private static native long ffmpegVideoInit(
      String codecName, @Nullable byte[] extraData, int width, int height, int threads);

  private static native void ffmpegVideoFlush(long context);

  private static native void ffmpegVideoRelease(long context);

  private static native int ffmpegVideoDecode(long context, ByteBuffer data, int size);

  private static native int ffmpegVideoWidth(long context);

  private static native int ffmpegVideoHeight(long context);

  private static native int ffmpegVideoSarNum(long context);

  private static native int ffmpegVideoSarDen(long context);

  private static native int ffmpegVideoCopyFrame(
      long context, ByteBuffer out, int yStride, int uvStride);
}
