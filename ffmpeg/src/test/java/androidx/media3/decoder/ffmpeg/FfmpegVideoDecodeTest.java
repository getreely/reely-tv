package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The video decoder, end to end: the Java class, the JNI glue and FFmpeg's MPEG-4 part 2
 * decoder, fed two seconds of 720 by 480 with B-frames and quarter-pixel motion, the kind
 * of file that played green through the stick's own decoder. Runs only against a desktop
 * build (hostcheck/build.sh), passed with -PffmpegHost; the APK's libraries are ARM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FfmpegVideoDecodeTest {

  private static final long FRAME_US = 33_367;

  private String clip;

  @Before
  public void onlyWithTheHostBuild() {
    clip = System.getProperty("reely.ffmpegVideoClip");
    assumeTrue("run with -PffmpegHost=<dir from hostcheck/build.sh>", clip != null);
  }

  @Test
  public void takesTheOldFormatsAndLeavesTheRest() {
    assertTrue("MPEG-4 part 2", FfmpegVideoDecoder.decodes("video/mp4v-es"));
    assertTrue("Microsoft MPEG-4 v3", FfmpegVideoDecoder.decodes("video/mp43"));
    assertFalse("H.264 is the device's", FfmpegVideoDecoder.decodes("video/avc"));
    assertFalse("MPEG-2 is the device's", FfmpegVideoDecoder.decodes("video/mpeg2"));
    assertTrue(FfmpegVideoRenderer.takes(format(720, 480)));
    assertFalse("too big to decode without the hardware", FfmpegVideoRenderer.takes(format(1920, 1080)));
  }

  @Test
  public void decodesAnOldDvdRipInOrderAndInItsOwnColours() throws Exception {
    List<byte[]> packets = readFrames(clip);
    assertTrue("a clip to decode", packets.size() >= 50);
    FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(8, 4, 256 * 1024, format(720, 480), 2);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);

    int queued = 0;
    int pictures = 0;
    long lastTimeUs = -1;
    // Until every packet's gone in and nothing more has come out for a while: a packet
    // that gave no picture gives nothing to wait for.
    long quietSince = System.currentTimeMillis();
    while (queued < packets.size() || System.currentTimeMillis() - quietSince < 500) {
      DecoderInputBuffer input = queued < packets.size() ? decoder.dequeueInputBuffer() : null;
      if (input != null) {
        byte[] packet = packets.get(queued);
        input.ensureSpaceForWrite(packet.length);
        input.data.put(packet);
        input.flip();
        // As an AVI gives them: one after another in the order stored.
        input.timeUs = queued * FRAME_US;
        decoder.queueInputBuffer(input);
        queued++;
      }
      VideoDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
      if (output == null) {
        Thread.sleep(2);
        continue;
      }
      quietSince = System.currentTimeMillis();
      pictures++;
      assertTrue("shown in order: " + output.timeUs + " after " + lastTimeUs, output.timeUs > lastTimeUs);
      lastTimeUs = output.timeUs;
      assertEquals("the picture as stored", 720, output.decoderPrivate);
      assertEquals(480, output.height);
      // Pixels 8:9, so 720 across is shown as 640: four by three.
      assertEquals("the width it's shown at", 640, output.width);
      // Red at the top left and blue at the top right, each plane where it belongs.
      assertNear("red, Y", 81, sample(output, 0, 180, 120));
      assertNear("red, U", 90, sample(output, 1, 90, 60));
      assertNear("red, V", 240, sample(output, 2, 90, 60));
      assertNear("blue, Y", 41, sample(output, 0, 540, 120));
      assertNear("blue, U", 240, sample(output, 1, 270, 60));
      assertNear("blue, V", 110, sample(output, 2, 270, 60));
      output.release();
    }
    decoder.release();

    // All but the few the decoder was still holding back for B-frames at the end.
    assertTrue("nearly every picture out, got " + pictures + " of " + packets.size(), pictures >= packets.size() - 4);
  }

  private static Format format(int width, int height) {
    return new Format.Builder().setSampleMimeType("video/mp4v-es").setWidth(width).setHeight(height).build();
  }

  private static int sample(VideoDecoderOutputBuffer buffer, int plane, int x, int y) {
    return buffer.yuvPlanes[plane].get(y * buffer.yuvStrides[plane] + x) & 0xFF;
  }

  private static void assertNear(String what, int expected, int actual) {
    assertTrue(what + ": wanted about " + expected + ", got " + actual, Math.abs(expected - actual) <= 12);
  }

  private static List<byte[]> readFrames(String path) throws Exception {
    List<byte[]> frames = new ArrayList<>();
    try (DataInputStream in = new DataInputStream(new FileInputStream(path))) {
      while (in.available() > 0) {
        byte[] size = new byte[4];
        in.readFully(size);
        int n = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] frame = new byte[n];
        in.readFully(frame);
        frames.add(frame);
      }
    }
    return frames;
  }
}
