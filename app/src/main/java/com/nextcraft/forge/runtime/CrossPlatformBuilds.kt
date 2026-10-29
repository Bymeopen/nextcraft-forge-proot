package com.nextcraft.forge.runtime

import java.io.File

/** Operating systems a project can be built for besides Android. */
enum class BuildPlatform(val label: String) {
    WINDOWS("Windows"),
    LINUX("Linux"),
    IOS("iOS"),
}

/**
 * A cross-build found in a project folder.
 *
 * [onDevice] is true when the chroot (Ubuntu arm64) can produce the artifact itself; false when it cannot (iOS needs
 * Xcode, which only runs on macOS), in which case the build has to go through a macOS CI runner.
 * [tools] maps a binary the command needs to the apt package that provides it, so a missing tool fails with an
 * instruction instead of "command not found".
 */
internal data class CrossTarget(
    val id: String,
    val name: String,
    val platform: BuildPlatform,
    val command: String,
    val onDevice: Boolean,
    val tools: List<Pair<String, String>> = emptyList(),
)

private val MINGW = "x86_64-w64-mingw32-gcc" to "gcc-mingw-w64-x86-64"
private val MINGW_CXX = "x86_64-w64-mingw32-g++" to "g++-mingw-w64-x86-64"
private val GCC_X64 = "x86_64-linux-gnu-gcc" to "gcc-x86-64-linux-gnu"
private val GXX_X64 = "x86_64-linux-gnu-g++" to "g++-x86-64-linux-gnu"

/** Prefixes [command] with a check that every tool exists; the message names the apt package to install. */
internal fun withToolCheck(command: String, tools: List<Pair<String, String>>): String {
    if (tools.isEmpty()) return command
    val checks = tools.joinToString("; ") { (binary, pkg) ->
        "command -v $binary >/dev/null 2>&1 || { echo \"$binary is missing. Install it in the terminal: apt-get install -y $pkg\"; exit 127; }"
    }
    return "$checks; $command"
}

/** apt-get line that installs every package the detected on-device cross-builds need (deduplicated). */
internal fun crossToolchainInstallCommand(targets: List<CrossTarget>): String? {
    val packages = targets.filter { it.onDevice }.flatMap { it.tools.map { tool -> tool.second } }.distinct()
    return if (packages.isEmpty()) null else "apt-get update && apt-get install -y ${packages.joinToString(" ")}"
}

private fun hasFileWithExtension(dir: File, vararg extensions: String): Boolean =
    dir.listFiles()?.any { f -> extensions.any { f.name.endsWith(it) } } == true

/** Looks at the files of [workspace] and proposes Windows, Linux and iOS builds for Go, Rust, CMake, .NET, Flutter and Xcode projects. */
internal fun detectCrossTargets(workspace: File): List<CrossTarget> {
    val found = mutableListOf<CrossTarget>()

    if (File(workspace, "go.mod").isFile) {
        val go = listOf("go" to "golang-go")
        // Pure Go (CGO off) cross-compiles with nothing but the Go toolchain.
        for ((os, arch, platform) in listOf(
            Triple("windows", "amd64", BuildPlatform.WINDOWS),
            Triple("linux", "amd64", BuildPlatform.LINUX),
            Triple("linux", "arm64", BuildPlatform.LINUX),
        )) {
            found += CrossTarget(
                id = "go:$os-$arch",
                name = "Go ${platform.label} ($arch)",
                platform = platform,
                command = "GOOS=$os GOARCH=$arch CGO_ENABLED=0 go build -o dist/$os-$arch/ ./...",
                onDevice = true,
                tools = go,
            )
        }
    }

    // libGDX starter (GameProjectTemplateGenerator): one runnable jar for Windows and Linux PCs.
    if (File(workspace, ".nextcraft/game-project.json").isFile && File(workspace, "desktop/build.gradle.kts").isFile) {
        found += CrossTarget(
            id = "gdx:desktop-jar",
            name = "Game PC (.jar, Windows/Linux)",
            platform = BuildPlatform.WINDOWS,
            command = "gradle --console=plain :desktop:dist",
            onDevice = true,
        )
    }

    if (File(workspace, "Cargo.toml").isFile) {
        val rust = listOf("cargo" to "cargo", "rustup" to "rustup")
        found += CrossTarget(
            id = "cargo:windows-x64",
            name = "Rust Windows (x86_64)",
            platform = BuildPlatform.WINDOWS,
            command = "rustup target add x86_64-pc-windows-gnu && cargo build --release --target x86_64-pc-windows-gnu",
            onDevice = true,
            tools = rust + MINGW,
        )
        found += CrossTarget(
            id = "cargo:linux-x64",
            name = "Rust Linux (x86_64)",
            platform = BuildPlatform.LINUX,
            command = "rustup target add x86_64-unknown-linux-gnu && " +
                "CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc cargo build --release --target x86_64-unknown-linux-gnu",
            onDevice = true,
            tools = rust + GCC_X64,
        )
    }

    if (File(workspace, "CMakeLists.txt").isFile) {
        found += CrossTarget(
            id = "cmake:windows-x64",
            name = "CMake Windows (MinGW, x86_64)",
            platform = BuildPlatform.WINDOWS,
            command = "cmake -S . -B build-windows -DCMAKE_SYSTEM_NAME=Windows " +
                "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ && " +
                "cmake --build build-windows -j2",
            onDevice = true,
            tools = listOf("cmake" to "cmake", MINGW, MINGW_CXX),
        )
        found += CrossTarget(
            id = "cmake:linux-x64",
            name = "CMake Linux (x86_64)",
            platform = BuildPlatform.LINUX,
            command = "cmake -S . -B build-linux-x64 -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=x86_64 " +
                "-DCMAKE_C_COMPILER=x86_64-linux-gnu-gcc -DCMAKE_CXX_COMPILER=x86_64-linux-gnu-g++ && " +
                "cmake --build build-linux-x64 -j2",
            onDevice = true,
            tools = listOf("cmake" to "cmake", GCC_X64, GXX_X64),
        )
    }

    if (hasFileWithExtension(workspace, ".csproj", ".fsproj", ".sln")) {
        val dotnet = listOf("dotnet" to "dotnet-sdk-8.0")
        for ((rid, platform) in listOf(
            "win-x64" to BuildPlatform.WINDOWS,
            "linux-x64" to BuildPlatform.LINUX,
            "linux-arm64" to BuildPlatform.LINUX,
        )) {
            found += CrossTarget(
                id = "dotnet:$rid",
                name = ".NET ${platform.label} ($rid)",
                platform = platform,
                command = "dotnet publish -c Release -r $rid --self-contained -o dist/$rid",
                onDevice = true,
                tools = dotnet,
            )
        }
    }

    // iOS: Xcode (and the iOS SDK) exist only on macOS and Apple's licence ties them to Apple hardware, so there is
    // no on-device build. The project is built on a macOS CI runner; see iosWorkflowYaml.
    val xcodeProject = workspace.listFiles()?.firstOrNull { it.name.endsWith(".xcodeproj") || it.name.endsWith(".xcworkspace") }
    val flutterIos = File(workspace, "pubspec.yaml").isFile && File(workspace, "ios").isDirectory
    if (xcodeProject != null || flutterIos) {
        found += CrossTarget(
            id = "ios:ci",
            name = "iOS (macOS CI)",
            platform = BuildPlatform.IOS,
            command = "",
            onDevice = false,
        )
    }

    // Flutter desktop targets need the Flutter SDK for the host; Linux arm64 hosts are not officially supported by
    // the Windows toolchain, so only the Linux build is offered on device.
    if (File(workspace, "pubspec.yaml").isFile) {
        found += CrossTarget(
            id = "flutter:linux",
            name = "Flutter Linux",
            platform = BuildPlatform.LINUX,
            // Flutter is not an apt package, so the check names where to get it instead of a package.
            command = "command -v flutter >/dev/null 2>&1 || { echo \"flutter is missing. Install the Flutter SDK from flutter.dev\"; exit 127; }; " +
                "flutter build linux --release",
            onDevice = true,
        )
    }

    return found
}

/** The Xcode workspace or project in the root of [workspace] (workspace preferred), or null. */
internal fun findXcodeProject(workspace: File): String? {
    val names = workspace.listFiles()?.map { it.name }.orEmpty().sorted()
    return names.firstOrNull { it.endsWith(".xcworkspace") } ?: names.firstOrNull { it.endsWith(".xcodeproj") }
}

/** Path of the generated iOS workflow, relative to the workspace. */
internal const val IOS_WORKFLOW_PATH = ".github/workflows/build-ios.yml"

/**
 * Writes [IOS_WORKFLOW_PATH] for the Xcode project in [workspace]. The scheme is assumed to share the project's name
 * (Xcode's default). Returns false when there is no Xcode project or the workflow already exists (never overwritten).
 */
internal fun writeIosWorkflow(workspace: File): Boolean {
    val project = findXcodeProject(workspace) ?: return false
    val target = File(workspace, IOS_WORKFLOW_PATH)
    if (target.exists()) return false
    target.parentFile?.mkdirs()
    target.writeText(iosWorkflowYaml(project, project.substringBeforeLast('.')))
    return true
}

/**
 * GitHub Actions workflow that builds an Xcode project on a macOS runner without signing (simulator build plus an
 * unsigned device archive). Signing needs the user's Apple certificates and is added by the user as secrets.
 */
internal fun iosWorkflowYaml(project: String, scheme: String): String {
    val flag = if (project.endsWith(".xcworkspace")) "-workspace" else "-project"
    // A project folder name with a quote would end the shell quotes in the workflow's run step.
    val shellProject = project.replace("'", "'\\''")
    val shellScheme = scheme.replace("'", "'\\''")
    return """
        name: Build iOS
        on:
          workflow_dispatch:
          push:
            branches: [main]
        jobs:
          ios:
            runs-on: macos-latest
            steps:
              - uses: actions/checkout@v4
              - name: Build for the simulator (no signing)
                run: |
                  xcodebuild $flag '$shellProject' -scheme '$shellScheme' -sdk iphonesimulator \
                    -configuration Debug -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
              - uses: actions/upload-artifact@v4
                with:
                  name: ios-simulator-app
                  path: build/Build/Products/Debug-iphonesimulator/*.app
    """.trimIndent() + "\n"
}
