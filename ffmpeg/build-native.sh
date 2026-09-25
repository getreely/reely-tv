#!/bin/bash
#
# Builds libffmpegJNI.so for the two ABIs Fire TVs run, into src/main/jniLibs, where
# the module picks it up with no native toolchain needed at app build time.
#
# The Java and JNI sources beside this are media3's decoder_ffmpeg module, copied
# unchanged from androidx/media at tag 1.11.1 — the version the app plays with, which
# the extension has to match — apart from the experimental video renderer, which is left
# out. Google does not publish this module prebuilt (ExoPlayer issue 2781).
#
# FFmpeg is built as LGPL (no --enable-gpl, no --enable-nonfree), statically into the
# JNI library, with only the audio decoders below. To rebuild:
#
#   git clone --depth 1 --branch n6.0.1 https://github.com/FFmpeg/FFmpeg /tmp/ffmpeg
#   ffmpeg/build-native.sh /tmp/ffmpeg "$ANDROID_HOME/ndk/<version>"
#
set -euo pipefail

FFMPEG_SRC="$(cd "$1" && pwd)"
NDK="$(cd "$2" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
API=23
DECODERS=(ac3 eac3 dca truehd mlp mp2 mp3 aac_latm flac alac opus vorbis)
TOOLS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
JOBS="$(nproc)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

OPTIONS=(
    --target-os=android --enable-static --disable-shared --disable-doc --disable-programs
    --disable-everything --disable-avdevice --disable-avformat --disable-swscale
    --disable-postproc --disable-avfilter --disable-symver --enable-swresample
    --disable-v4l2-m2m --disable-vulkan
    --nm="$TOOLS/llvm-nm" --ar="$TOOLS/llvm-ar" --ranlib="$TOOLS/llvm-ranlib"
    --strip="$TOOLS/llvm-strip"
)
for decoder in "${DECODERS[@]}"; do OPTIONS+=(--enable-decoder="$decoder"); done

build_ffmpeg() {
    local abi="$1"; shift
    (
        cd "$FFMPEG_SRC"
        make distclean >/dev/null 2>&1 || true
        ./configure --prefix="$STAGE/$abi" --libdir="$STAGE/$abi/lib" \
            --incdir="$STAGE/$abi/include" "$@" "${OPTIONS[@]}" >/dev/null
        make -j"$JOBS" >/dev/null
        make install-libs install-headers >/dev/null
    )
}

build_jni() {
    local abi="$1"
    # The CMake file expects FFmpeg's headers and libraries under jni/ffmpeg.
    local src="$STAGE/jni-$abi"
    mkdir -p "$src/ffmpeg/android-libs"
    cp "$HERE/src/main/jni/CMakeLists.txt" "$HERE/src/main/jni/ffmpeg_jni.cc" "$src/"
    cp -r "$STAGE/$abi/include/." "$src/ffmpeg/"
    cp -r "$STAGE/$abi/lib" "$src/ffmpeg/android-libs/$abi"
    cmake -S "$src" -B "$src/out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release >/dev/null
    cmake --build "$src/out" >/dev/null
    mkdir -p "$HERE/src/main/jniLibs/$abi"
    "$TOOLS/llvm-strip" --strip-unneeded -o "$HERE/src/main/jniLibs/$abi/libffmpegJNI.so" \
        "$src/out/libffmpegJNI.so"
    echo "built $abi: $(stat -c %s "$HERE/src/main/jniLibs/$abi/libffmpegJNI.so") bytes"
}

build_ffmpeg armeabi-v7a --arch=arm --cpu=armv7-a \
    --cross-prefix="$TOOLS/armv7a-linux-androideabi$API-" \
    --extra-cflags="-march=armv7-a -mfloat-abi=softfp" --extra-ldflags="-Wl,--fix-cortex-a8"
build_jni armeabi-v7a

build_ffmpeg arm64-v8a --arch=aarch64 --cpu=armv8-a \
    --cross-prefix="$TOOLS/aarch64-linux-android$API-"
build_jni arm64-v8a

echo "FFmpeg $(git -C "$FFMPEG_SRC" describe --tags 2>/dev/null || echo '?'), decoders: ${DECODERS[*]}"
