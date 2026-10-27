package com.nextcraft.forge.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nextcraft.forge.model.stringOrEmpty
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AndroidBuildPhase {
    IDLE,
    PREPARING,
    BUILDING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class AndroidAction {
    NONE,
    BUILD,
    FORCE_BUILD,
    INSTALL,
    RUN,
    BUILD_AND_RUN,
    CLEAN,
}

enum class AndroidBuildStage {
    IDLE,
    PREPARING,
    RESOLVING,
    COMPILING,
    RESOURCES,
    PACKAGING,
    FINDING_APK,
    INSTALLING,
    LAUNCHING,
    COMPLETE,
    FAILED,
    CANCELLING,
    CANCELLED,
}

enum class AndroidIssueSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class AndroidBuildIssue(
    val severity: AndroidIssueSeverity = AndroidIssueSeverity.ERROR,
    val title: String,
    val detail: String,
    val suggestion: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class AndroidApkInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val builtAtMillis: Long,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val stale: Boolean = false,
    val installed: Boolean = false,
    val installedMatches: Boolean = false,
)

enum class AndroidHealthStatus {
    RUNNING,
    PASSED,
    WARNING,
    FAILED,
}

enum class AndroidHealthFix {
    NONE,
    INSTALL_TOOLS,
    OPEN_INSTALL_SETTINGS,
    OPEN_NETWORK_SETTINGS,
    OPEN_STORAGE_SETTINGS,
    CLEAN,
    COPY_LOGCAT_COMMAND,
    REFRESH,
}

data class AndroidHealthCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: AndroidHealthStatus,
    val fix: AndroidHealthFix = AndroidHealthFix.NONE,
)

enum class AndroidLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

data class AndroidLogLine(
    val id: Long,
    val text: String,
    val level: AndroidLogLevel,
    val filePath: String? = null,
    val line: Int? = null,
)

data class AndroidLogcatState(
    val available: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val message: String? = null,
    val packageName: String? = null,
    val lines: List<AndroidLogLine> = emptyList(),
    val minimumLevel: AndroidLogLevel = AndroidLogLevel.VERBOSE,
    val query: String = "",
)

data class AndroidBuildRecord(
    val phase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val message: String? = null,
    val log: String = "",
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val apkPath: String? = null,
    val apkSizeBytes: Long? = null,
    val action: AndroidAction = AndroidAction.NONE,
    val stage: AndroidBuildStage = AndroidBuildStage.IDLE,
    val issues: List<AndroidBuildIssue> = emptyList(),
    val recentDurationsMillis: List<Long> = emptyList(),
)

private val GRADLE_TASK_TOKEN_PATTERN = Regex("^[A-Za-z0-9_.:-]+(\\s+[A-Za-z0-9_.:-]+)*$")
private val SETTINGS_SCRIPT_NAMES = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
private val IGNORED_BUILD_DIRS = setOf("build", ".gradle", ".idea", ".git")
private val SOURCE_INPUT_EXTS = setOf("kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json", "jar", "aar")
private val COMPILER_DIAGNOSTIC_PATTERN = Regex(
    "(?m)^(.+?\\.(?:kt|java|xml|gradle|kts)):(\\d+)(?::(\\d+))?[: ]+(?:error: )?(.+)$",
    RegexOption.IGNORE_CASE,
)
private val DIAGNOSTIC_PREFIX_STRIP = Regex("^(?:e:|w:|error:|warning:)\\s*", RegexOption.IGNORE_CASE)
private val LOGCAT_TIMESTAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

internal fun androidGradleCommand(root: File, task: String, extraFlags: String = ""): String {
    val sanitizedTask = task.trim()
    require(sanitizedTask.isNotEmpty() && GRADLE_TASK_TOKEN_PATTERN.matches(sanitizedTask)) {
        "Unsafe Gradle task name: '$task'"
    }
    val wrapperFile = File(root, "gradlew")
    val binaryCmd = if (wrapperFile.isFile && !Files.isSymbolicLink(wrapperFile.toPath())) {
        "bash ./gradlew"
    } else {
        "gradle"
    }
    val extraPart = if (extraFlags.isBlank()) "" else " ${extraFlags.trim()}"
    return "$binaryCmd ${AndroidToolchain.GRADLE_BASE_FLAGS}$extraPart $sanitizedTask --console=plain --stacktrace"
}

private fun isConfinedDirectory(candidate: File, boundary: java.nio.file.Path): Boolean =
    !Files.isSymbolicLink(candidate.toPath()) &&
        runCatching { candidate.canonicalFile.toPath().startsWith(boundary) }.getOrDefault(false)

internal fun findAndroidProjectRoot(workspace: File): File? {
    if (!workspace.isDirectory) return null
    val boundary = runCatching { workspace.canonicalFile.toPath() }.getOrNull() ?: return null

    val candidateRoots = workspace.walkTopDown()
        .onEnter { dir -> dir == workspace || isConfinedDirectory(dir, boundary) }
        .maxDepth(4)
        .filter { f -> f.isFile && !Files.isSymbolicLink(f.toPath()) && f.name in SETTINGS_SCRIPT_NAMES }
        .mapNotNull { it.parentFile }
        .sortedBy { it.absolutePath.length }

    return candidateRoots.firstOrNull { candidateRoot ->
        val rootBoundary = runCatching { candidateRoot.canonicalFile.toPath() }.getOrNull() ?: return@firstOrNull false
        candidateRoot.walkTopDown()
            .onEnter { dir -> dir == candidateRoot || isConfinedDirectory(dir, rootBoundary) }
            .maxDepth(5)
            .any { f ->
                f.isFile &&
                    !Files.isSymbolicLink(f.toPath()) &&
                    f.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml")
            }
    }
}

internal fun findDebugApk(projectRoot: File): File? {
    if (!projectRoot.isDirectory) return null
    val rootBoundary = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null

    val newestMetadata = projectRoot.walkTopDown()
        .onEnter { dir -> dir == projectRoot || isConfinedDirectory(dir, rootBoundary) }
        .maxDepth(8)
        .filter { f ->
            f.isFile &&
                !Files.isSymbolicLink(f.toPath()) &&
                f.name == "output-metadata.json" &&
                "/outputs/apk/" in f.invariantSeparatorsPath
        }
        .maxByOrNull { it.lastModified() }

    if (newestMetadata != null) {
        val fromMeta = runCatching {
            val rootJson = JSONObject(newestMetadata.readText())
            val elementsArr = rootJson.optJSONArray("elements") ?: return@runCatching null
            for (i in 0 until elementsArr.length()) {
                val relOut = elementsArr.optJSONObject(i)?.stringOrEmpty("outputFile").orEmpty()
                if (relOut.isBlank() || '\u0000' in relOut) continue
                val apkCandidate = File(newestMetadata.parentFile, relOut)
                if (apkCandidate.isFile &&
                    !Files.isSymbolicLink(apkCandidate.toPath()) &&
                    apkCandidate.length() > 0L &&
                    isConfinedDirectory(apkCandidate, rootBoundary)
                ) {
                    return@runCatching apkCandidate
                }
            }
            null
        }.getOrNull()
        if (fromMeta != null) return fromMeta
    }

    return projectRoot.walkTopDown()
        .onEnter { dir -> dir == projectRoot || isConfinedDirectory(dir, rootBoundary) }
        .maxDepth(10)
        .filter { f ->
            f.isFile &&
                !Files.isSymbolicLink(f.toPath()) &&
                f.extension.equals("apk", ignoreCase = true) &&
                "/outputs/apk/debug/" in f.invariantSeparatorsPath &&
                isConfinedDirectory(f, rootBoundary)
        }
        .maxByOrNull { it.lastModified() }
}

internal fun findReusableDebugApk(projectRoot: File): File? {
    val builtApk = findDebugApk(projectRoot) ?: return null
    val rootBoundary = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val apkMtime = builtApk.lastModified()

    val anySourceNewer = projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (dir.name !in IGNORED_BUILD_DIRS && isConfinedDirectory(dir, rootBoundary))
        }
        .any { f ->
            if (!f.isFile || Files.isSymbolicLink(f.toPath()) || f.lastModified() <= apkMtime) {
                false
            } else {
                val relPath = f.relativeTo(projectRoot).invariantSeparatorsPath
                relPath.startsWith("src/") ||
                    "/src/" in relPath ||
                    f.extension.lowercase(Locale.ROOT) in SOURCE_INPUT_EXTS ||
                    f.name == "gradlew" ||
                    f.name == "gradlew.bat"
            }
        }
    return if (anySourceNewer) null else builtApk
}

internal fun isDebugApkStale(projectRoot: File, apk: File): Boolean =
    findReusableDebugApk(projectRoot) != apk

internal fun inferAndroidBuildStage(output: String, current: AndroidBuildStage): AndroidBuildStage {
    val low = output.lowercase(Locale.US)
    return when {
        "packagedebug" in low || ("assembledebug" in low && "> task" in low) -> AndroidBuildStage.PACKAGING
        ("merge" in low && "resource" in low) || "processdebugresources" in low || "aapt2" in low -> AndroidBuildStage.RESOURCES
        "compiledebugkotlin" in low || "compiledebugjava" in low || "kotlinc" in low || "javac" in low -> AndroidBuildStage.COMPILING
        "download" in low || "resolve" in low || "dependency" in low -> AndroidBuildStage.RESOLVING
        else -> current
    }
}

private fun isWarningDiagnosticLine(rawLine: String): Boolean {
    val trimmed = rawLine.trimStart()
    return trimmed.startsWith("w:", ignoreCase = true) ||
        trimmed.startsWith("warning:", ignoreCase = true) ||
        rawLine.contains(" warning:", ignoreCase = true)
}

internal fun parseAndroidBuildIssues(output: String, projectRoot: File, exitCode: Int): List<AndroidBuildIssue> {
    val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)
    val extractedIssues = COMPILER_DIAGNOSTIC_PATTERN.findAll(output)
        .filterNot { isWarningDiagnosticLine(it.value) }
        .take(12)
        .map { m ->
            val cleanedPath = m.groupValues[1].trim()
                .replace(DIAGNOSTIC_PREFIX_STRIP, "")
                .removePrefix("file://")
                .trim()
            val candidateFile = File(cleanedPath)
            val resolvedRelPath = when {
                candidateFile.isAbsolute && isConfinedDirectory(candidateFile, canonicalRoot.toPath()) ->
                    candidateFile.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                cleanedPath.startsWith("/workspace/") ->
                    cleanedPath.substringAfter("/workspace/").substringAfter('/', "")
                else -> cleanedPath.takeIf { rel ->
                    val localFile = File(projectRoot, rel)
                    localFile.isFile && isConfinedDirectory(localFile, canonicalRoot.toPath())
                }
            }
            AndroidBuildIssue(
                title = "Source error",
                detail = m.groupValues[4].trim().take(400),
                suggestion = "Open the file and correct the reported line.",
                filePath = resolvedRelPath,
                line = m.groupValues[2].toIntOrNull(),
                column = m.groupValues[3].toIntOrNull(),
            )
        }
        .toList()

    if (extractedIssues.isNotEmpty()) {
        return extractedIssues.distinctBy { Triple(it.filePath, it.line, it.detail) }
    }

    val low = output.lowercase(Locale.US)
    val hint = when {
        "no space left" in low -> "Free device storage, then retry the build."
        "could not resolve" in low || "could not get resource" in low -> "Check the network connection or use a cached dependency version."
        "manifest merger failed" in low -> "Review duplicate or conflicting manifest declarations."
        "aapt2" in low || "resource linking failed" in low -> "Review resource names and XML diagnostics in the build output."
        "outofmemory" in low || "heap space" in low -> "Close other apps and retry."
        else -> "Open the build details for the complete Gradle output."
    }
    return listOf(
        AndroidBuildIssue(
            title = "Build failed",
            detail = diagnoseAndroidBuildFailure(output, exitCode),
            suggestion = hint,
        ),
    )
}

internal fun diagnoseAndroidBuildFailure(output: String, exitCode: Int): String {
    val low = output.lowercase(Locale.US)
    return when {
        "no space left on device" in low -> "The device does not have enough free storage for this build."
        "outofmemoryerror" in low || "java heap space" in low -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in low || "could not get resource" in low -> "A project dependency is unavailable. Connect to the internet or use an offline-cached version."
        "manifest merger failed" in low -> "Android manifest merge failed. Open the build log for the conflicting declaration."
        "aapt2" in low || "android resource linking failed" in low -> "Android resources could not be compiled. Open the build log for the file and line."
        "compilation error" in low || "compilation failed" in low || "unresolved reference" in low -> "Kotlin or Java compilation failed. Open the build log for source errors."
        "minimum supported gradle version" in low || "maximum supported gradle" in low -> "This project's Gradle and Android plugin versions are incompatible."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}

internal fun hasAndroidLogcatAccess(context: Context): Boolean {
    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) return true
    if (!RuntimeEngines.isRootEngine()) return false

    return runCatching {
        val checkProc = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        if (!checkProc.waitFor(1_500L, TimeUnit.MILLISECONDS)) {
            checkProc.destroyForcibly()
            false
        } else {
            checkProc.exitValue() == 0
        }
    }.getOrDefault(false)
}

internal fun startPackageLogcatProcess(
    context: Context,
    packageName: String,
    sinceMillis: Long? = null,
): Process {
    val appUid = try {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        throw IllegalStateException("App '$packageName' is not installed on this device yet. Build & Install the APK first.")
    }
    require(appUid > 0) { "Invalid application UID for $packageName" }

    val timeArg = if (sinceMillis != null && sinceMillis > 0L) {
        Instant.ofEpochMilli(sinceMillis).atZone(ZoneId.systemDefault()).format(LOGCAT_TIMESTAMP_FMT)
    } else {
        "1"
    }

    val directPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    return if (!directPermission && RuntimeEngines.isRootEngine()) {
        val cleanTime = timeArg.replace("'", "")
        ProcessBuilder("su", "-c", "/system/bin/logcat --uid=$appUid -v threadtime -T '$cleanTime'")
            .redirectErrorStream(true)
            .start()
    } else {
        ProcessBuilder("/system/bin/logcat", "--uid=$appUid", "-v", "threadtime", "-T", timeArg)
            .redirectErrorStream(true)
            .start()
    }
}
