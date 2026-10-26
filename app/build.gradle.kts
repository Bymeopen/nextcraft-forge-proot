import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nextcraft.forge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nextcraft.forge"
        minSdk = 26
        targetSdk = 35
        // The release workflow passes -PversionCode=... -PversionName=...; local builds keep the defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 100
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0-forge"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "APP_VARIANT", "\"debug\"")
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // Manifest asset of the latest GitHub release; build with -PappUpdateManifestUrl=... to point elsewhere.
        val updateManifestUrl = (project.findProperty("appUpdateManifestUrl") as String?)
            ?: "https://github.com/bymeopen/nextcraft-forge/releases/latest/download/update-manifest.json"
        buildConfigField("String", "APP_UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    // Release signing comes from the environment so no key material lives in the repo.
    val releaseKeystore = System.getenv("FORGE_KEYSTORE_FILE")
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FORGE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FORGE_KEY_ALIAS")
                keyPassword = System.getenv("FORGE_KEY_PASSWORD")
            }
        }
    }

    signingConfigs {
        // A fixed, public debug key: CI runners otherwise sign each debug APK with a fresh random key, so a newer
        // build cannot be installed over an older one and the app (rootfs, chats, root grant) must be uninstalled.
        create("sharedDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Same applicationId as release (com.nextcraft.forge): one app, one data dir and rootfs. Debug is signed with
            // the shared public key below, release with the FORGE_KEYSTORE_* key, so a build of one kind cannot be
            // installed over the other without uninstalling (the updater checks the certificate and says so).
            signingConfig = signingConfigs.getByName("sharedDebug")
            isMinifyEnabled = false
            buildConfigField("String", "APP_VARIANT", "\"debug\"")
            // Side-by-side test build (gradle.properties: nextcraft.sideBySideSuffix=x): its own package, data dir and
            // rootfs (com.nextcraft.forge.x, "NextCraft Dev X"), so it installs from scratch next to the main app.
            (project.findProperty("nextcraft.sideBySideSuffix") as String?)?.takeIf { it.isNotBlank() }?.let {
                applicationIdSuffix = ".$it"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("String", "APP_VARIANT", "\"release\"")
        }
    }

    if ((project.findProperty("nextcraft.sideBySideSuffix") as String?).isNullOrBlank().not()) {
        sourceSets.getByName("debug").res.srcDir("src/sideBySide/res")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Build libforgespawn.so whenever an NDK can be located; without it NativeSpawnProcess
    // silently falls back to ProcessBuilder (no PTY, no process-group kill).
    val ndkCandidates = listOfNotNull(
        System.getenv("ANDROID_NDK_HOME")?.let(::File),
        System.getenv("ANDROID_NDK_ROOT")?.let(::File),
        System.getenv("ANDROID_HOME")?.let { File(it, "ndk") },
        System.getenv("ANDROID_SDK_ROOT")?.let { File(it, "ndk") },
        File("/root/android-sdk/ndk"),
    )
    val ndkAvailable = ndkCandidates.any { it.isDirectory && !it.listFiles().isNullOrEmpty() }
    if (ndkAvailable) {
        // AGP prefers its own default NDK and fails when only another version is installed,
        // so pin to the version found under <sdk>/ndk when there is exactly one.
        ndkCandidates
            .firstOrNull { it.name == "ndk" && it.listFiles()?.size == 1 }
            ?.listFiles()?.single()?.name
            ?.let { ndkVersion = it }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    // NextCraft Screenshot, opt-in layout screenshots across phone sizes: ./gradlew :app:testDebugUnitTest -PforgeScreenshots
    // (needs the Robolectric SDK download; default builds are unaffected).
    if (project.hasProperty("forgeScreenshots")) {
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
                all {
                    // -PforgeRoboDir=<dir with android-all-instrumented-*.jar> runs Robolectric without network access.
                    (project.findProperty("forgeRoboDir") as String?)?.let { dir ->
                        it.systemProperty("robolectric.offline", "true")
                        it.systemProperty("robolectric.dependency.dir", dir)
                    }
                    // -PforgeScreenshotMode=record|compare (see ScreenshotBaselines); default only renders the pictures.
                    (project.findProperty("forgeScreenshotMode") as String?)?.let { mode -> it.systemProperty("forge.screenshot.mode", mode) }
                    it.maxHeapSize = "2g"
                }
            }
        }
        sourceSets.getByName("test").java.srcDir("src/screenshotTest/java")
        // AGP 9 built-in Kotlin only compiles .kt files from the kotlin source set.
        sourceSets.getByName("test").kotlin.directories.add("src/screenshotTest/java")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // libproot.so (src/main/jniLibs) is executed, not dlopen'ed: it must be extracted to nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    // Liquid-glass backdrop (blur / vibrancy / lens) for the Glass theme; effects need Android 12+ (lens: 13+).
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Interactive terminal: VT100/xterm emulator and its Android view. Pinned; see docs/TERMINAL.md for licensing.
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // ZstdCompressorInputStream needs zstd-jni at runtime; rootfs bundles are .tar.zst.
    implementation("com.github.luben:zstd-jni:1.5.7-24@aar")
    testImplementation("junit:junit:4.13.2")
    // The real org.json: the android.jar stubs do nothing in unit tests (used by the REA result parser test).
    testImplementation("org.json:json:20240303")
    if (project.hasProperty("forgeScreenshots")) {
        testImplementation("org.robolectric:robolectric:4.12.2")
        testImplementation("androidx.compose.ui:ui-test-junit4")
    }
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
