package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
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
 * The FFmpeg decoder, end to end: the Java classes, the JNI glue, and FFmpeg's E-AC3
 * decoder, fed one second of a 5.1 tone. Runs only against a desktop build of the same
 * sources (hostcheck/build.sh), passed with -PffmpegHost; the APK's libraries are ARM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FfmpegDecodeTest {

  private String clip;

  @Before
  public void onlyWithTheHostBuild() {
    clip = System.getProperty("reely.ffmpegClip");
    assumeTrue("run with -PffmpegHost=<dir from hostcheck/build.sh>", clip != null);
  }

  @Test
  public void decodesDolbyDigitalPlusToSixChannels() throws Exception {
    assertTrue("library loads", FfmpegLibrary.isAvailable());
    assertTrue("E-AC3 built in", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3));
    assertTrue("AC3 built in", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3));
    assertTrue("DTS built in", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_DTS));
    assertTrue("TrueHD built in", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_TRUEHD));

    List<byte[]> frames = readFrames(clip);
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build();
    FfmpegAudioDecoder decoder = new FfmpegAudioDecoder(format, 16, 16, 8192, false);

    int queued = 0;
    int decoded = 0;
    long samples = 0;
    int peak = 0;
    long deadline = System.currentTimeMillis() + 10_000;
    while (decoded < frames.size() && System.currentTimeMillis() < deadline) {
      DecoderInputBuffer input = queued < frames.size() ? decoder.dequeueInputBuffer() : null;
      if (input != null) {
        byte[] frame = frames.get(queued);
        input.ensureSpaceForWrite(frame.length);
        input.data.put(frame);
        input.flip();
        input.timeUs = queued * 32_000L;
        decoder.queueInputBuffer(input);
        queued++;
      }
      SimpleDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
      if (output == null) {
        Thread.sleep(2);
        continue;
      }
      ByteBuffer pcm = output.data.duplicate().order(ByteOrder.nativeOrder());
      int shorts = pcm.remaining() / 2;
      samples += shorts / 6;
      for (int i = 0; i < shorts; i++) peak = Math.max(peak, Math.abs(pcm.getShort()));
      output.release();
      decoded++;
    }
    decoder.release();

    assertEquals("every frame decoded", frames.size(), decoded);
    assertEquals("5.1 out", 6, decoder.getChannelCount());
    assertEquals(48_000, decoder.getSampleRate());
    // 1536 samples a frame, about a second of them.
    assertTrue("about a second of sound, got " + samples, samples > 40_000);
    // The tone is at half scale; silence, or noise from a broken decode, would not be.
    assertTrue("the tone comes through, peak " + peak, peak > 12_000 && peak < 20_000);
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
