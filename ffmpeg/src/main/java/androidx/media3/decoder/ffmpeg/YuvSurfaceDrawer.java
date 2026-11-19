package androidx.media3.decoder.ffmpeg;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws a decoded picture, three planes of YUV 4:2:0, onto a surface with OpenGL.
 *
 * <p>Through OpenGL rather than by writing into the surface's own buffers, as media3's
 * software decoders do: writing into them ties the surface to that way of drawing for as
 * long as it lives, and the device's own decoder, wanted on the same surface for the next
 * thing played (or for the server's conversion if this fails), is then turned away. An
 * OpenGL connection is let go when its window surface is destroyed ([release]).
 *
 * <p>Everything here runs on the player's playback thread, which is the only one the
 * renderer draws and lets go from.
 */
@UnstableApi
/* package */ final class YuvSurfaceDrawer {

  private static final String VERTEX_SHADER =
      "attribute vec4 aPosition;\n"
          + "attribute vec2 aTexCoord;\n"
          + "uniform vec2 uScale;\n"
          + "varying vec2 vY;\n"
          + "varying vec2 vUv;\n"
          + "uniform vec2 uUvScale;\n"
          + "void main() {\n"
          + "  gl_Position = aPosition;\n"
          + "  vY = aTexCoord * uScale;\n"
          + "  vUv = aTexCoord * uUvScale;\n"
          + "}\n";

  // BT.601, limited range: what standard-definition video is, and what these formats carry.
  private static final String FRAGMENT_SHADER =
      "precision mediump float;\n"
          + "varying vec2 vY;\n"
          + "varying vec2 vUv;\n"
          + "uniform sampler2D uTexY;\n"
          + "uniform sampler2D uTexU;\n"
          + "uniform sampler2D uTexV;\n"
          + "void main() {\n"
          + "  float y = 1.1643 * (texture2D(uTexY, vY).r - 0.0627);\n"
          + "  float u = texture2D(uTexU, vUv).r - 0.5;\n"
          + "  float v = texture2D(uTexV, vUv).r - 0.5;\n"
          + "  gl_FragColor = vec4(y + 1.5958 * v, y - 0.39173 * u - 0.8129 * v, y + 2.017 * u, 1.0);\n"
          + "}\n";

  private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private EGLContext context = EGL14.EGL_NO_CONTEXT;
  private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
  @Nullable private EGLConfig config;
  @Nullable private Surface surface;

  private int program;
  private int positionLocation;
  private int texCoordLocation;
  private int scaleLocation;
  private int uvScaleLocation;
  private final int[] textures = new int[3];
  private final int[] textureWidths = new int[3];
  private final int[] textureHeights = new int[3];
  private final FloatBuffer positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f);
  // The picture's first row is its top.
  private final FloatBuffer texCoords = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f);

  /** Draws [buffer], as FfmpegVideoDecoder fills it, onto [target] and shows it. */
  void draw(Surface target, VideoDecoderOutputBuffer buffer) {
    ByteBuffer[] planes = buffer.yuvPlanes;
    int[] strides = buffer.yuvStrides;
    if (planes == null || strides == null || !target.isValid()) {
      return;
    }
    if (target != surface || eglSurface == EGL14.EGL_NO_SURFACE) {
      release();
      open(target);
    }
    if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
      throw new IllegalStateException("eglMakeCurrent failed: " + EGL14.eglGetError());
    }

    // The picture as stored: FfmpegVideoDecoder keeps its width here, and puts the width
    // it's shown at in the buffer's own.
    int width = buffer.decoderPrivate > 0 ? (int) buffer.decoderPrivate : buffer.width;
    int height = buffer.height;
    int uvWidth = (width + 1) / 2;
    int uvHeight = (height + 1) / 2;

    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
    upload(0, planes[0], strides[0], height);
    upload(1, planes[1], strides[1], uvHeight);
    upload(2, planes[2], strides[2], uvHeight);

    int[] size = new int[2];
    EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, size, 0);
    EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, size, 1);
    GLES20.glViewport(0, 0, size[0], size[1]);
    GLES20.glUseProgram(program);
    // Each plane's rows are wider than the picture; only the picture's part is drawn, less
    // half a pixel so the padding beyond it never bleeds into the edge.
    GLES20.glUniform2f(scaleLocation, (width - 0.5f) / strides[0], 1f);
    GLES20.glUniform2f(uvScaleLocation, (uvWidth - 0.5f) / strides[1], 1f);
    positions.position(0);
    texCoords.position(0);
    GLES20.glEnableVertexAttribArray(positionLocation);
    GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions);
    GLES20.glEnableVertexAttribArray(texCoordLocation);
    GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoords);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
      int error = EGL14.eglGetError();
      // The surface went as it was being drawn on: nothing to show it on, and no fault.
      release();
      if (error != EGL14.EGL_BAD_SURFACE && error != EGL14.EGL_BAD_NATIVE_WINDOW) {
        throw new IllegalStateException("eglSwapBuffers failed: " + error);
      }
    }
  }

  /** Lets go of the surface and everything made for it. Safe to call when there's nothing. */
  void release() {
    if (display != EGL14.EGL_NO_DISPLAY) {
      if (context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE
          && EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
        GLES20.glDeleteTextures(3, textures, 0);
        GLES20.glDeleteProgram(program);
      }
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (eglSurface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglDestroySurface(display, eglSurface);
      }
      if (context != EGL14.EGL_NO_CONTEXT) {
        EGL14.eglDestroyContext(display, context);
      }
      // The display is the whole app's: it isn't this class's to shut.
    }
    eglSurface = EGL14.EGL_NO_SURFACE;
    context = EGL14.EGL_NO_CONTEXT;
    display = EGL14.EGL_NO_DISPLAY;
    config = null;
    surface = null;
    program = 0;
    for (int i = 0; i < 3; i++) {
      textures[i] = 0;
      textureWidths[i] = 0;
      textureHeights[i] = 0;
    }
  }

  private void open(Surface target) {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (display == EGL14.EGL_NO_DISPLAY) {
      throw new IllegalStateException("No EGL display.");
    }
    int[] version = new int[2];
    if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
      display = EGL14.EGL_NO_DISPLAY;
      throw new IllegalStateException("eglInitialize failed: " + EGL14.eglGetError());
    }
    int[] attributes = {
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_NONE
    };
    EGLConfig[] configs = new EGLConfig[1];
    int[] count = new int[1];
    if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1 || configs[0] == null) {
      throw new IllegalStateException("No EGL config: " + EGL14.eglGetError());
    }
    config = configs[0];
    context =
        EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
    if (context == null || context == EGL14.EGL_NO_CONTEXT) {
      context = EGL14.EGL_NO_CONTEXT;
      throw new IllegalStateException("eglCreateContext failed: " + EGL14.eglGetError());
    }
    eglSurface = EGL14.eglCreateWindowSurface(display, config, target, new int[] {EGL14.EGL_NONE}, 0);
    if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
      eglSurface = EGL14.EGL_NO_SURFACE;
      int error = EGL14.eglGetError();
      release();
      throw new IllegalStateException("eglCreateWindowSurface failed: " + error);
    }
    surface = target;
    if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
      int error = EGL14.eglGetError();
      release();
      throw new IllegalStateException("eglMakeCurrent failed: " + error);
    }
    // Shown as soon as it's drawn: the renderer has already timed it, and waiting for the
    // screen here would hold up the thread the sound is fed from.
    EGL14.eglSwapInterval(display, 0);

    program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
    positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
    texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
    scaleLocation = GLES20.glGetUniformLocation(program, "uScale");
    uvScaleLocation = GLES20.glGetUniformLocation(program, "uUvScale");
    GLES20.glUseProgram(program);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexY"), 0);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexU"), 1);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexV"), 2);
    GLES20.glGenTextures(3, textures, 0);
    for (int i = 0; i < 3; i++) {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      textureWidths[i] = 0;
      textureHeights[i] = 0;
    }
  }

  /** One plane into its texture, a row's whole stride wide. */
  private void upload(int index, ByteBuffer plane, int stride, int height) {
    ByteBuffer pixels = plane.duplicate();
    pixels.position(0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index]);
    if (textureWidths[index] != stride || textureHeights[index] != height) {
      GLES20.glTexImage2D(
          GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, stride, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels);
      textureWidths[index] = stride;
      textureHeights[index] = height;
    } else {
      GLES20.glTexSubImage2D(
          GLES20.GL_TEXTURE_2D, 0, 0, 0, stride, height, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels);
    }
  }

  private static int compile(int type, String source) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    int[] status = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
    if (status[0] == 0) {
      String log = GLES20.glGetShaderInfoLog(shader);
      GLES20.glDeleteShader(shader);
      throw new IllegalStateException("Shader didn't compile: " + log);
    }
    return shader;
  }

  private static int link(int vertex, int fragment) {
    int made = GLES20.glCreateProgram();
    GLES20.glAttachShader(made, vertex);
    GLES20.glAttachShader(made, fragment);
    GLES20.glLinkProgram(made);
    int[] status = new int[1];
    GLES20.glGetProgramiv(made, GLES20.GL_LINK_STATUS, status, 0);
    GLES20.glDeleteShader(vertex);
    GLES20.glDeleteShader(fragment);
    if (status[0] == 0) {
      String log = GLES20.glGetProgramInfoLog(made);
      GLES20.glDeleteProgram(made);
      throw new IllegalStateException("Program didn't link: " + log);
    }
    return made;
  }

  private static FloatBuffer floats(float... values) {
    FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    buffer.put(values).position(0);
    return buffer;
  }
}
