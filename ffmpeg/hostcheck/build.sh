#!/bin/bash
#
# Builds the FFmpeg decoder for this Linux machine, plus a short 5.1 E-AC3 clip, so the
# JNI glue and the decoders can be exercised by JVM tests (FfmpegDecodeTest,
# FfmpegVideoDecodeTest) without
# an Android device. The APK's own libraries are ARM and are built by ../build-native.sh
# from the same sources and FFmpeg version.
#
#   ffmpeg/hostcheck/build.sh /tmp/ffmpeg /tmp/ffmpeg-host
#   ./gradlew :ffmpeg:testDebugUnitTest -PffmpegHost=/tmp/ffmpeg-host
#
set -euo pipefail
FFMPEG_SRC="$(cd "$1" && pwd)"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
DECODERS=(ac3 eac3 dca truehd mlp mp2 mp3 aac_latm flac alac opus vorbis)
# The old video the app decodes itself, as ../build-native.sh has them.
DECODERS+=(mpeg4 msmpeg4v2 msmpeg4v3 h263)

OPTIONS=(--enable-static --disable-shared --enable-pic --disable-doc --disable-programs
    --disable-everything --disable-avdevice --disable-avformat --disable-swscale
    --disable-postproc --disable-avfilter --enable-swresample --enable-encoder=eac3 --enable-encoder=mpeg4
    --disable-x86asm)
for decoder in "${DECODERS[@]}"; do OPTIONS+=(--enable-decoder="$decoder"); done
(
    cd "$FFMPEG_SRC"
    make distclean >/dev/null 2>&1 || true
    ./configure --prefix="$OUT" "${OPTIONS[@]}" >/dev/null
    make -j"$(nproc)" >/dev/null
    make install-libs install-headers >/dev/null
    make distclean >/dev/null 2>&1 || true
)
LIBS=(-L"$OUT/lib" -lavcodec -lswresample -lavutil -lm -lpthread)

# The JNI library, with Android's log header stood in for.
g++ -shared -fPIC -O2 -std=c++11 -I"$HERE/shim" -I"$OUT/include" \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$HERE/../src/main/jni/ffmpeg_jni.cc" "$HERE/../src/main/jni/ffmpeg_video_jni.cc" -Wl,-Bsymbolic "${LIBS[@]}" -o "$OUT/libffmpegJNI.so"

# One second of a 5.1 tone, E-AC3 encoded, as length-prefixed frames.
gcc -O2 -I"$OUT/include" "$HERE/make_clip.c" "${LIBS[@]}" -o "$OUT/make_clip"
"$OUT/make_clip" "$OUT/tone.eac3frames"
# Two seconds of 720 by 480 MPEG-4 part 2 with B-frames, as an old DVD rip is.
gcc -O2 -I"$OUT/include" "$HERE/make_video_clip.c" "${LIBS[@]}" -o "$OUT/make_video_clip"
"$OUT/make_video_clip" "$OUT/picture.mpeg4frames"
echo "host check ready in $OUT"
