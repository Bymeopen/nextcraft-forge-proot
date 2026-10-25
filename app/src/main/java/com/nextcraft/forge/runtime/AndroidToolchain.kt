package com.nextcraft.forge.runtime

/**
 * Versions and guest paths of the Android toolchain overlay (`nextcraft-android-arm64-*.tar.zst`).
 *
 * Every path the installer, the build commands, the agent prompts, and `scripts/runtime-bundles/build-android-bundle.sh`
 * use is derived from these constants; nothing else should spell out a Build-Tools, Gradle, JDK, or platform version.
 * Keep them in sync with the bundle builder script and `scripts/runtime-bundles/chroot-health.sh`.
 */
internal object AndroidToolchain {
    /** Android SDK platform directory name under `platforms/` (`compileSdk 37`, minor API level 2). */
    const val PLATFORM = "android-37.2"
    const val COMPILE_SDK = 37
    const val COMPILE_SDK_MINOR = 2
    const val TARGET_SDK = 37
    /** AGP 9.4 insists on its default Build-Tools revision being installed (a newer one is not accepted in its place). */
    const val BUILD_TOOLS_VERSION = "36.0.0"
    const val GRADLE_VERSION = "9.8.1"
    const val AGP_VERSION = "9.4.1"
    const val KOTLIN_VERSION = "2.4.21"
    const val COMPOSE_BOM_VERSION = "2026.09.00"
    /** Temurin JDK major version shipped in the bundle; Gradle and AGP run on it, projects target Java 17 bytecode. */
    const val JDK_MAJOR = 21
    const val JDK_DIR = "jdk-21.0.12.1+1"
    const val JAVA_TARGET = 17

    const val SDK_GUEST_DIR = "/root/android-sdk"
    const val GRADLE_HOME_GUEST_DIR = "/opt/gradle/gradle-$GRADLE_VERSION"
    const val BUILD_TOOLS_GUEST_DIR = "$SDK_GUEST_DIR/build-tools/$BUILD_TOOLS_VERSION"
    const val AAPT2_GUEST_PATH = "$BUILD_TOOLS_GUEST_DIR/aapt2"
    const val ANDROID_JAR_GUEST_PATH = "$SDK_GUEST_DIR/platforms/$PLATFORM/android.jar"
    const val MAVEN_GUEST_DIR = "/root/maven/localMvnRepository"
    const val GRADLE_INIT_SCRIPT_GUEST_PATH = "/root/.gradle/init.d/nextcraft-android.gradle"
    const val GUEST_PATH_PREFIX = "$GRADLE_HOME_GUEST_DIR/bin:$BUILD_TOOLS_GUEST_DIR:$SDK_GUEST_DIR/cmdline-tools/latest/bin"

    /** Same paths relative to the rootfs directory on the host side. */
    const val SDK_HOST_DIR = "root/android-sdk"
    const val GRADLE_HOST_DIR = "opt/gradle"
    const val JDK_HOST_DIR = "opt/$JDK_DIR"
    const val BUILD_TOOLS_HOST_DIR = "$SDK_HOST_DIR/build-tools/$BUILD_TOOLS_VERSION"
    const val AAPT2_HOST_PATH = "$BUILD_TOOLS_HOST_DIR/aapt2"
    const val ANDROID_JAR_HOST_PATH = "$SDK_HOST_DIR/platforms/$PLATFORM/android.jar"
    const val GRADLE_LAUNCHER_HOST_PATH = "$GRADLE_HOST_DIR/gradle-$GRADLE_VERSION/bin/gradle"
    const val MAVEN_HOST_DIR = "root/maven/localMvnRepository"

    /** Bumping this makes every installed runtime re-download the overlay; required whenever the layout changes. */
    const val TOOLS_VERSION = "sdk37.2-build-tools36-gradle9.8.1-jdk21-maven-2026.10"

    /** Short description shown in the stack picker and in agent prompts. */
    const val SUMMARY = "JDK $JDK_MAJOR, ARM64 Android SDK $COMPILE_SDK, Build Tools $BUILD_TOOLS_VERSION, " +
        "Gradle $GRADLE_VERSION, and an offline Maven cache"

    /**
     * `gradle` flags shared by the in-app build and the agent prompts. No -Dorg.gradle.jvmargs here: an empty value
     * dropped the device-sized heap/metaspace from ~/.gradle/gradle.properties (which already beats a project's own
     * gradle.properties) and left builds on Gradle's 512 MB default. Daemon and workers also come from there, sized to
     * the phone (RuntimeInstaller.gradleSpeedProperties).
     */
    const val GRADLE_BASE_FLAGS =
        "--init-script $GRADLE_INIT_SCRIPT_GUEST_PATH -Pandroid.aapt2FromMavenOverride=$AAPT2_GUEST_PATH"

    /** Guidance for agents on a runtime whose toolchain is installed. */
    val AGENT_PROMPT_LINES: List<String> = listOf(
        "If this is an Android project, the phone already provides JDK $JDK_MAJOR, Android SDK $PLATFORM, " +
            "ARM64 Build Tools $BUILD_TOOLS_VERSION, Gradle $GRADLE_VERSION, and an offline Maven repository.",
        "For newly created Android projects, use AGP $AGP_VERSION, Kotlin $KOTLIN_VERSION, compileSdk $COMPILE_SDK " +
            "(minorApiLevel $COMPILE_SDK_MINOR), and Java $JAVA_TARGET bytecode so the preinstalled offline toolchain can build immediately.",
        "AGP 9 has built-in Kotlin: declare org.jetbrains.kotlin.android version $KOTLIN_VERSION only in the root " +
            "build.gradle.kts with `apply false` (that selects the Kotlin version), never apply it to a module, do not use " +
            "kotlinOptions {} (use kotlin { compilerOptions {} } if needed), and enable Compose with the " +
            "org.jetbrains.kotlin.plugin.compose plugin instead of composeOptions.kotlinCompilerExtensionVersion.",
        "Projects created with older toolchains (AGP 8.x, Kotlin 1.9) do not resolve offline anymore: migrate them to " +
            "AGP $AGP_VERSION / Kotlin $KOTLIN_VERSION, or build them online once so Gradle can download the old artifacts.",
        "The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. " +
            "Set android.useAndroidX=true for AndroidX or Compose projects.",
        "NextCraft Forge globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, " +
            "investigate its architecture, or add android.aapt2FromMavenOverride to the project.",
        "Background jobs started from a tool call stop when the turn ends. For dev servers and long builds that must " +
            "outlive the turn use `keep-running <logfile> <command> [args...]` (own session, no hangup) and read the log later.",
        "Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, " +
            "Gradle, ADB, or Termux.",
    )

    /** Rootfs-relative paths removed by `removeAndroidStack`, for the current and all previous bundle layouts. */
    val REMOVABLE_HOST_PATHS: List<String> = listOf(
        SDK_HOST_DIR,
        MAVEN_HOST_DIR.substringBefore('/') + "/maven",
        "root/.pocket-android-tools-version",
        "root/.nextcraft-android-tools-version",
        "root/.gradle/caches",
        "root/.gradle/daemon",
        "root/.gradle/native",
        "root/.gradle/notifications",
        "root/.gradle/wrapper/dists",
        "root/.gradle/init.d/pocketdev-android.gradle",
        "root/.gradle/init.d/nextcraft-android.gradle",
        GRADLE_HOST_DIR,
        JDK_HOST_DIR,
        "opt/jdk-$JDK_MAJOR",
        // Layout of pocketdev-android-arm64-2026.09.1 (JDK 17).
        "opt/jdk-17.0.20.1+1",
        "opt/jdk-17",
        "usr/local/bin/jar",
        "usr/local/bin/jarsigner",
        "usr/local/bin/java",
        "usr/local/bin/javac",
        "usr/local/bin/javadoc",
        "usr/local/bin/keytool",
    )
}
