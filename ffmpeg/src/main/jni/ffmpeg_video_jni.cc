/*
 * Video through FFmpeg, for the old formats a Fire TV's own decoders play badly or not
 * at all: MPEG-4 part 2 (Xvid, DivX), the Microsoft MPEG-4 variants and H.263. Reely's
 * own, beside media3's audio glue (ffmpeg_jni.cc), which is left as it
 * was copied.
 *
 * One packet in, at most one picture out, in the order pictures are shown. The picture
 * is copied out as planar YUV 4:2:0 into a buffer the Java side owns, and drawn there.
 */
#include <android/log.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
}

#define LOG_TAG "ffmpeg_video_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

#define VIDEO_FUNC(RETURN_TYPE, NAME, ...)                                     \
  extern "C" JNIEXPORT RETURN_TYPE JNICALL                                     \
      Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_##NAME(           \
          JNIEnv* env, jclass clazz, ##__VA_ARGS__)

// Kept in step with FfmpegVideoDecoder.java.
static const int VIDEO_FRAME_READY = 0;
static const int VIDEO_NO_FRAME = 1;
static const int VIDEO_ERROR = -1;
static const int VIDEO_ERROR_PICTURE_FORMAT = -2;

struct VideoContext {
  AVCodecContext* codec;
  AVPacket* packet;
  AVFrame* frame;
  bool hasFrame;
};

static void logAvError(const char* what, int error) {
  char buffer[256];
  av_strerror(error, buffer, sizeof(buffer));
  LOGE("%s: %s", what, buffer);
}

static bool isYuv420(int format) {
  return format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P;
}

VIDEO_FUNC(jboolean, ffmpegVideoHasDecoder, jstring codecName) {
  if (!codecName) return JNI_FALSE;
  const char* name = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(name);
  env->ReleaseStringUTFChars(codecName, name);
  return codec != NULL && codec->type == AVMEDIA_TYPE_VIDEO ? JNI_TRUE : JNI_FALSE;
}

VIDEO_FUNC(jlong, ffmpegVideoInit, jstring codecName, jbyteArray extraData,
           jint width, jint height, jint threads) {
  if (!codecName) return 0L;
  const char* name = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(name);
  env->ReleaseStringUTFChars(codecName, name);
  if (!codec || codec->type != AVMEDIA_TYPE_VIDEO) {
    LOGE("No video decoder of that name.");
    return 0L;
  }
  VideoContext* context = (VideoContext*)calloc(1, sizeof(VideoContext));
  if (!context) return 0L;
  context->codec = avcodec_alloc_context3(codec);
  context->packet = av_packet_alloc();
  context->frame = av_frame_alloc();
  if (!context->codec || !context->packet || !context->frame) {
    LOGE("Out of memory.");
    goto fail;
  }
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    if (size > 0) {
      context->codec->extradata =
          (uint8_t*)av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
      if (!context->codec->extradata) goto fail;
      context->codec->extradata_size = size;
      env->GetByteArrayRegion(extraData, 0, size,
                              (jbyte*)context->codec->extradata);
    }
  }
  if (width > 0 && height > 0) {
    context->codec->coded_width = width;
    context->codec->coded_height = height;
    context->codec->width = width;
    context->codec->height = height;
  }
  // A damaged picture is shown as well as it can be rather than ending playback.
  context->codec->err_recognition = AV_EF_IGNORE_ERR;
  context->codec->thread_count = threads > 0 ? threads : 1;
  context->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  {
    int result = avcodec_open2(context->codec, codec, NULL);
    if (result < 0) {
      logAvError("avcodec_open2", result);
      goto fail;
    }
  }
  return (jlong)context;

fail:
  if (context->codec) avcodec_free_context(&context->codec);
  if (context->packet) av_packet_free(&context->packet);
  if (context->frame) av_frame_free(&context->frame);
  free(context);
  return 0L;
}

VIDEO_FUNC(void, ffmpegVideoFlush, jlong jContext) {
  VideoContext* context = (VideoContext*)jContext;
  if (!context) return;
  avcodec_flush_buffers(context->codec);
  av_frame_unref(context->frame);
  context->hasFrame = false;
}

VIDEO_FUNC(void, ffmpegVideoRelease, jlong jContext) {
  VideoContext* context = (VideoContext*)jContext;
  if (!context) return;
  avcodec_free_context(&context->codec);
  av_packet_free(&context->packet);
  av_frame_free(&context->frame);
  free(context);
}

/**
 * Decodes one packet. VIDEO_FRAME_READY when a picture came out (the next one to show,
 * which with B-frames isn't this packet's), VIDEO_NO_FRAME when none did yet, negative
 * when the decoder can't go on.
 */
VIDEO_FUNC(jint, ffmpegVideoDecode, jlong jContext, jobject data, jint size) {
  VideoContext* context = (VideoContext*)jContext;
  if (!context || !data || size < 0) return VIDEO_ERROR;
  uint8_t* bytes = (uint8_t*)env->GetDirectBufferAddress(data);
  if (!bytes) return VIDEO_ERROR;
  av_frame_unref(context->frame);
  context->hasFrame = false;

  context->packet->data = bytes;
  context->packet->size = size;
  int result = avcodec_send_packet(context->codec, context->packet);
  if (result == AVERROR(EAGAIN)) {
    // A picture is waiting to be taken first: take it, and this packet goes in after.
    int taken = avcodec_receive_frame(context->codec, context->frame);
    int again = avcodec_send_packet(context->codec, context->packet);
    if (again < 0 && again != AVERROR_INVALIDDATA) {
      logAvError("avcodec_send_packet", again);
      return VIDEO_ERROR;
    }
    if (taken == 0) {
      if (!isYuv420(context->frame->format)) return VIDEO_ERROR_PICTURE_FORMAT;
      context->hasFrame = true;
      return VIDEO_FRAME_READY;
    }
  } else if (result == AVERROR_INVALIDDATA) {
    // One bad packet in an old file isn't the end of it.
    return VIDEO_NO_FRAME;
  } else if (result < 0) {
    logAvError("avcodec_send_packet", result);
    return VIDEO_ERROR;
  }

  result = avcodec_receive_frame(context->codec, context->frame);
  if (result == AVERROR(EAGAIN) || result == AVERROR_EOF ||
      result == AVERROR_INVALIDDATA) {
    return VIDEO_NO_FRAME;
  }
  if (result < 0) {
    logAvError("avcodec_receive_frame", result);
    return VIDEO_ERROR;
  }
  if (!isYuv420(context->frame->format)) {
    LOGE("Picture format %d isn't planar 4:2:0.", context->frame->format);
    return VIDEO_ERROR_PICTURE_FORMAT;
  }
  context->hasFrame = true;
  return VIDEO_FRAME_READY;
}

VIDEO_FUNC(jint, ffmpegVideoWidth, jlong jContext) {
  VideoContext* context = (VideoContext*)jContext;
  return context && context->hasFrame ? context->frame->width : 0;
}

VIDEO_FUNC(jint, ffmpegVideoHeight, jlong jContext) {
  VideoContext* context = (VideoContext*)jContext;
  return context && context->hasFrame ? context->frame->height : 0;
}

/** The shape of the picture's pixels, as the stream says: 0 when it doesn't. */
VIDEO_FUNC(jint, ffmpegVideoSarNum, jlong jContext) {
  VideoContext* context = (VideoContext*)jContext;
  return context && context->hasFrame ? context->frame->sample_aspect_ratio.num : 0;
}

VIDEO_FUNC(jint, ffmpegVideoSarDen, jlong jContext) {
  VideoContext* context = (VideoContext*)jContext;
  return context && context->hasFrame ? context->frame->sample_aspect_ratio.den : 0;
}

static void copyPlane(const uint8_t* source, int sourceStride, uint8_t* target,
                      int targetStride, int width, int height) {
  for (int row = 0; row < height; row++) {
    memcpy(target, source, width);
    source += sourceStride;
    target += targetStride;
  }
}

/**
 * The picture just decoded, copied into [out] as Y then U then V, each row [yStride] or
 * [uvStride] bytes apart: the layout VideoDecoderOutputBuffer.initForYuvFrame makes.
 */
VIDEO_FUNC(jint, ffmpegVideoCopyFrame, jlong jContext, jobject out,
           jint yStride, jint uvStride) {
  VideoContext* context = (VideoContext*)jContext;
  if (!context || !context->hasFrame || !out) return VIDEO_ERROR;
  AVFrame* frame = context->frame;
  const int width = frame->width;
  const int height = frame->height;
  const int uvWidth = (width + 1) / 2;
  const int uvHeight = (height + 1) / 2;
  if (yStride < width || uvStride < uvWidth) return VIDEO_ERROR;
  uint8_t* target = (uint8_t*)env->GetDirectBufferAddress(out);
  const jlong capacity = env->GetDirectBufferCapacity(out);
  const jlong needed =
      (jlong)yStride * height + 2 * (jlong)uvStride * uvHeight;
  if (!target || capacity < needed) return VIDEO_ERROR;
  copyPlane(frame->data[0], frame->linesize[0], target, yStride, width, height);
  target += (size_t)yStride * height;
  copyPlane(frame->data[1], frame->linesize[1], target, uvStride, uvWidth,
            uvHeight);
  target += (size_t)uvStride * uvHeight;
  copyPlane(frame->data[2], frame->linesize[2], target, uvStride, uvWidth,
            uvHeight);
  return VIDEO_FRAME_READY;
}
