import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A release keystore is optional. Without one, release builds are signed with the
// debug key, which is all a sideloaded spike build needs.
val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull

android {
    namespace = "tv.reely"
    compileSdk = 35

    defaultConfig {
        applicationId = "tv.reely"
        // Fire OS 6 and later (API 25+). Fire OS 5 sticks are excluded because the
        // Keystore-backed credential store needs API 23 and Compose is painful below it.
        minSdk = 23
        targetSdk = 34
        versionCode = 44
        versionName = "0.19.2"
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

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            // focusProperties { enter = ... }, which is how a row remembers where the
            // cursor was. Nothing else in this app needs it.
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }

    buildFeatures {
        compose = true
        // The update check compares the published build against this one, which means
        // the running app has to know its own version number.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}

/**
 * Writes the manifest the update check reads, next to the APK it describes.
 *
 * Without it the app can only ask the server how big the file is and when it changed,
 * which cannot tell a new build from the one already running. Generating it here means
 * the two are always published together and can never disagree.
 */
val writeUpdateManifest by tasks.registering {
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
