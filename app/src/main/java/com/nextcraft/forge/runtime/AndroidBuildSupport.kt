package com.nextcraft.forge.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.nextcraft.forge.model.stringOrEmpty
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AndroidBuildPhase { IDLE, PREPARING, BUILDING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
enum class AndroidAction { NONE, BUILD, FORCE_BUILD, INSTALL, RUN, BUILD_AND_RUN, CLEAN }
enum class AndroidBuildStage {
    IDLE, PREPARING, RESOLVING, COMPILING, RESOURCES, PACKAGING, FINDING_APK,
    INSTALLING, LAUNCHING, COMPLETE, FAILED, CANCELLING, CANCELLED,
}
enum class AndroidIssueSeverity { INFO, WARNING, ERROR }

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

enum class AndroidHealthStatus { RUNNING, PASSED, WARNING, FAILED }
enum class AndroidHealthFix {
    NONE, INSTALL_TOOLS, OPEN_INSTALL_SETTINGS, OPEN_NETWORK_SETTINGS,
    OPEN_STORAGE_SETTINGS, CLEAN, COPY_LOGCAT_COMMAND, REFRESH,
}

data class AndroidHealthCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: AndroidHealthStatus,
    val fix: AndroidHealthFix = AndroidHealthFix.NONE,
)

enum class AndroidLogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR, FATAL }

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

private val SAFE_GRADLE_TASK_REGEX = Regex("^[A-Za-z0-9_.:-]+(\\s+[A-Za-z0-9_.:-]+)*$")

/** [extraFlags] is a fixed app constant such as [BuildVerification.LOW_MEMORY_FLAGS], never user text. */
internal fun androidGradleCommand(root: File, task: String, extraFlags: String = ""): String {
    val cleanTask = task.trim()
    require(cleanTask.isNotEmpty() && SAFE_GRADLE_TASK_REGEX.matches(cleanTask)) {
        "Unsafe Gradle task name: '$task'"
    }
    val launcher = File(root, "gradlew")
        .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        ?.let { "bash ./gradlew" }
        ?: "gradle"
    val flags = if (extraFlags.isBlank()) "" else " ${extraFlags.trim()}"
    return "$launcher ${AndroidToolchain.GRADLE_BASE_FLAGS}$flags $cleanTask --console=plain --stacktrace"
}

internal fun findAndroidProjectRoot(workspace: File): File? {
    if (!workspace.isDirectory) return null
    val workspacePath = runCatching { workspace.canonicalFile.toPath() }.getOrNull() ?: return null
    val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
    return workspace.walkTopDown()
        .onEnter { dir ->
            dir == workspace || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                )
        }
        .maxDepth(4)
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name in settingsNames }
        .mapNotNull(File::getParentFile)
        .sortedBy { it.absolutePath.length }
        .firstOrNull { root ->
            val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return@firstOrNull false
            root.walkTopDown()
                .onEnter { dir ->
                    dir == root || (
                        !Files.isSymbolicLink(dir.toPath()) &&
                            runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                        )
                }
                .maxDepth(5)
                .any {
                    it.isFile &&
                        !Files.isSymbolicLink(it.toPath()) &&
                        it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml")
                }
        }
}

internal fun findDebugApk(projectRoot: File): File? {
    if (!projectRoot.isDirectory) return null
    val rootPath = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val metadata = projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .maxDepth(8)
        .filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.name == "output-metadata.json" &&
                "/outputs/apk/" in it.invariantSeparatorsPath
        }
        .maxByOrNull(File::lastModified)
    if (metadata != null) {
        runCatching {
            val json = JSONObject(metadata.readText())
            val elements = json.optJSONArray("elements") ?: return@runCatching null
            (0 until elements.length()).asSequence()
                .mapNotNull { elements.optJSONObject(it)?.stringOrEmpty("outputFile") }
                .filter { it.isNotBlank() && !it.contains('\u0000') }
                .map { File(metadata.parentFile, it) }
                .firstOrNull { candidate ->
                    candidate.isFile &&
                        !Files.isSymbolicLink(candidate.toPath()) &&
                        candidate.length() > 0L &&
                        runCatching { candidate.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                }
        }.getOrNull()?.let { return it }
    }
    return projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .maxDepth(10)
        .filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.extension.equals("apk", true) &&
                "/outputs/apk/debug/" in it.invariantSeparatorsPath &&
                runCatching { it.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        }
        .maxByOrNull(File::lastModified)
}

/** Returns the last debug APK only when every build input is older than it. */
internal fun findReusableDebugApk(projectRoot: File): File? {
    val apk = findDebugApk(projectRoot) ?: return null
    val rootPath = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val ignoredDirectories = setOf("build", ".gradle", ".idea", ".git")
    val buildInputExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json", "jar", "aar",
    )
    val changedAfterApk = projectRoot.walkTopDown()
        .onEnter { directory ->
            directory == projectRoot || (
                directory.name !in ignoredDirectories &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .filter { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@filter false
            val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
            val isAndroidSource = relative.startsWith("src/") || "/src/" in relative
            isAndroidSource || file.extension.lowercase() in buildInputExtensions ||
                file.name in setOf("gradlew", "gradlew.bat")
        }
        .any { it.lastModified() > apk.lastModified() }
    return apk.takeUnless { changedAfterApk }
}

internal fun isDebugApkStale(projectRoot: File, apk: File): Boolean = findReusableDebugApk(projectRoot) != apk

internal fun inferAndroidBuildStage(output: String, current: AndroidBuildStage): AndroidBuildStage {
    val text = output.lowercase(Locale.US)
    return when {
        "packagedebug" in text || ("assembledebug" in text && "> task" in text) -> AndroidBuildStage.PACKAGING
        ("merge" in text && "resource" in text) || "processdebugresources" in text || "aapt2" in text -> AndroidBuildStage.RESOURCES
        "compiledebugkotlin" in text || "compiledebugjava" in text || "kotlinc" in text || "javac" in text -> AndroidBuildStage.COMPILING
        "download" in text || "resolve" in text || "dependency" in text -> AndroidBuildStage.RESOLVING
        else -> current
    }
}

internal fun parseAndroidBuildIssues(output: String, projectRoot: File, exitCode: Int): List<AndroidBuildIssue> {
    val issues = mutableListOf<AndroidBuildIssue>()
    val location = Regex("(?m)^(.+?\\.(?:kt|java|xml|gradle|kts)):(\\d+)(?::(\\d+))?[: ]+(?:error: )?(.+)$", RegexOption.IGNORE_CASE)
    val rootCanonical = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)
    // Warnings are not the failure; counting them first pushed the real error past the 12-issue cap.
    val isWarning = { line: String ->
        val t = line.trimStart()
        t.startsWith("w:", ignoreCase = true) || t.startsWith("warning:", ignoreCase = true) ||
            line.contains(" warning:", ignoreCase = true)
    }
    location.findAll(output).filterNot { isWarning(it.value) }.take(12).forEach { match ->
        val rawPath = match.groupValues[1].trim()
            .replace(Regex("^(?:e:|w:|error:|warning:)\\s*", RegexOption.IGNORE_CASE), "")
            .removePrefix("file://")
            .trim()
        val file = File(rawPath)
        val relative = when {
            file.isAbsolute && runCatching { file.canonicalFile.toPath().startsWith(rootCanonical.toPath()) }.getOrDefault(false) ->
                file.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
            rawPath.startsWith("/workspace/") -> rawPath.substringAfter("/workspace/").substringAfter('/', "")
            else -> rawPath.takeIf {
                val candidate = File(projectRoot, it)
                candidate.isFile && runCatching { candidate.canonicalFile.toPath().startsWith(rootCanonical.toPath()) }.getOrDefault(false)
            }
        }
        issues += AndroidBuildIssue(
            title = "Source error",
            detail = match.groupValues[4].trim().take(400),
            suggestion = "Open the file and correct the reported line.",
            filePath = relative,
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull(),
        )
    }
    if (issues.isNotEmpty()) return issues.distinctBy { Triple(it.filePath, it.line, it.detail) }
    val summary = diagnoseAndroidBuildFailure(output, exitCode)
    val lower = output.lowercase(Locale.US)
    val suggestion = when {
        "no space left" in lower -> "Free device storage, then retry the build."
        "could not resolve" in lower || "could not get resource" in lower -> "Check the network connection or use a cached dependency version."
        "manifest merger failed" in lower -> "Review duplicate or conflicting manifest declarations."
        "aapt2" in lower || "resource linking failed" in lower -> "Review resource names and XML diagnostics in the build output."
        "outofmemory" in lower || "heap space" in lower -> "Close other apps and retry."
        else -> "Open the build details for the complete Gradle output."
    }
    return listOf(AndroidBuildIssue(title = "Build failed", detail = summary, suggestion = suggestion))
}

internal fun diagnoseAndroidBuildFailure(output: String, exitCode: Int): String {
    val text = output.lowercase(Locale.US)
    return when {
        "no space left on device" in text -> "The device does not have enough free storage for this build."
        "outofmemoryerror" in text || "java heap space" in text -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in text || "could not get resource" in text -> "A project dependency is unavailable. Connect to the internet or use an offline-cached version."
        "manifest merger failed" in text -> "Android manifest merge failed. Open the build log for the conflicting declaration."
        "aapt2" in text || "android resource linking failed" in text -> "Android resources could not be compiled. Open the build log for the file and line."
        "compilation error" in text || "compilation failed" in text || "unresolved reference" in text -> "Kotlin or Java compilation failed. Open the build log for source errors."
        "minimum supported gradle version" in text || "maximum supported gradle" in text -> "This project's Gradle and Android plugin versions are incompatible."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}

/**
 * Checks whether the host has either `READ_LOGS` permission or working Root (`su`) access
 * for streaming package-scoped `logcat`.
 */
internal fun hasAndroidLogcatAccess(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    return runCatching {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val finished = process.waitFor(1_500L, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}

/**
 * Starts a UID-scoped `logcat` process for [packageName] using Root (`su -c`) when
 * `READ_LOGS` is not held directly by the app.
 */
internal fun startPackageLogcatProcess(
    context: Context,
    packageName: String,
    sinceMillis: Long? = null,
): Process {
    val uid = try {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        throw IllegalStateException("App '$packageName' is not installed on this device yet. Build & Install the APK first.")
    }
    require(uid > 0) { "Invalid application UID for $packageName" }
    val since = sinceMillis?.takeIf { it > 0L }?.let {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(it))
    } ?: "1"
    val useRoot = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED
    return if (useRoot) {
        val safeSince = since.replace("'", "")
        val logCommand = "/system/bin/logcat --uid=$uid -v threadtime -T '$safeSince'"
        ProcessBuilder("su", "-c", logCommand).redirectErrorStream(true).start()
    } else {
        ProcessBuilder("/system/bin/logcat", "--uid=$uid", "-v", "threadtime", "-T", since)
            .redirectErrorStream(true)
            .start()
    }
}
