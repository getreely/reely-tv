#!/bin/bash
#
# Builds the FFmpeg decoder for this Linux machine, plus a short 5.1 E-AC3 clip, so the
# JNI glue and the decoders can be exercised by a JVM test (FfmpegDecodeTest) without
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

OPTIONS=(--enable-static --disable-shared --enable-pic --disable-doc --disable-programs
    --disable-everything --disable-avdevice --disable-avformat --disable-swscale
    --disable-postproc --disable-avfilter --enable-swresample --enable-encoder=eac3
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
    "$HERE/../src/main/jni/ffmpeg_jni.cc" -Wl,-Bsymbolic "${LIBS[@]}" -o "$OUT/libffmpegJNI.so"

# One second of a 5.1 tone, E-AC3 encoded, as length-prefixed frames.
gcc -O2 -I"$OUT/include" "$HERE/make_clip.c" "${LIBS[@]}" -o "$OUT/make_clip"
"$OUT/make_clip" "$OUT/tone.eac3frames"
echo "host check ready in $OUT"
