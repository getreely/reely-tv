import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A release keystore is optional. Without one, release builds are signed with the
// debug key, which is all a sideloaded spike build needs.
val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull

android {
    namespace = "tv.reely"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.reely"
        // Fire OS 6 and later (API 25+). Fire OS 5 sticks are excluded because the
        // Keystore-backed credential store needs API 23 and Compose is painful below it.
        minSdk = 23
        targetSdk = 34
        versionCode = 1110
        versionName = "0.34.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
            optIn.addAll(
                "androidx.tv.material3.ExperimentalTvMaterial3Api",
                "androidx.compose.foundation.ExperimentalFoundationApi",
                // focusProperties { enter = ... }, which is how a row remembers where the
                // cursor was. Nothing else in this app needs it.
                "androidx.compose.ui.ExperimentalComposeUiApi",
            )
        }
    }

    buildFeatures {
        compose = true
        // The update check compares the published build against this one, which means
        // the running app has to know its own version number.
        buildConfig = true
    }

    lint {
        // Nearly the whole of ExoPlayer's surface is marked @UnstableApi in media3 1.x —
        // PlayerView, DefaultLoadControl, the shutter colour, the lot. This app is a video
        // player, so it uses that surface everywhere on purpose, and the version is pinned.
        // Flagging each of those 25 call sites tells us nothing we do not already know and
        // would bury a real finding. Every other check stays an error.
        disable += "UnsafeOptInUsageError"
        abortOnError = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs the real resources — fonts above all — to draw screens.
            isIncludeAndroidResources = true
            all { test ->
                // Screenshots render only when asked for (-Pscreenshots): they take a
                // while, and what they are for is looking at, not passing or failing.
                val wanted = project.hasProperty("screenshots")
                test.systemProperty("reely.screenshots", wanted.toString())
                test.systemProperty("roborazzi.test.record", wanted.toString())
                test.systemProperty(
                    "roborazzi.output.dir",
                    layout.buildDirectory.dir("screenshots").get().asFile.absolutePath,
                )
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Software decoding for the sound the device cannot decode itself — Dolby Digital,
    // Dolby Digital Plus, TrueHD, DTS, MP2 — so it plays instead of playing silent.
    implementation(project(":ffmpeg"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Reads the main colour out of the artwork, for the glow behind a browse screen.
    implementation("androidx.palette:palette-ktx:1.0.0")

    testImplementation("junit:junit:4.13.2")

    // Screens drawn on the build machine, so layout can be looked at without a device.
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.75.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Writes the manifest the update check reads, next to the APK it describes.
 *
 * Without it the app can only ask the server how big the file is and when it changed,
 * which cannot tell a new build from the one already running. Generating it here means
 * the two are always published together and can never disagree.
 */
val writeUpdateManifest = tasks.register("writeUpdateManifest") {
    val outputDir = layout.buildDirectory.dir("outputs/apk/release")
    val versionCode = android.defaultConfig.versionCode
    val versionName = android.defaultConfig.versionName
    outputs.upToDateWhen { false }
    doLast {
        val published = Instant.now().toString()
        val folder = outputDir.get().asFile
        val apk = folder.resolve("app-release.apk")
        if (!apk.exists()) return@doLast
        folder.resolve("reely-tv.json").writeText(
            """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "sizeBytes": ${apk.length()},
              "published": "$published"
            }
            """.trimIndent()
        )
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(writeUpdateManifest)
}
