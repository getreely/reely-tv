// media3's FFmpeg audio decoder, with FFmpeg prebuilt into src/main/jniLibs by
// build-native.sh. Nothing native is compiled here, so building the app needs no NDK.
plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { test ->
            // The decode test needs a desktop build of the decoder; see hostcheck/build.sh.
            project.findProperty("ffmpegHost")?.toString()?.let { dir ->
                test.systemProperty("java.library.path", dir)
                test.systemProperty("reely.ffmpegClip", "$dir/tone.eac3frames")
            }
        }
    }

    lint {
        // Upstream code, copied unchanged; its findings are media3's to make.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Has to match the app's media3 exactly: the renderer is built against its internals.
    api("androidx.media3:media3-decoder:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.49.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
