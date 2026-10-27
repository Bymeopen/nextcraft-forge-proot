package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.Project
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class TargetStatus { PENDING, RUNNING, OK, FAILED }

data class BuildTarget(
    val id: String,
    val name: String,
    val command: String,
    /** Detected from the project files (not user-made); cannot be deleted, only switched off. */
    val detected: Boolean,
    val enabled: Boolean,
)

data class BuildMatrixState(
    val projectId: String? = null,
    val projectName: String = "",
    val targets: List<BuildTarget> = emptyList(),
    val statuses: Map<String, TargetStatus> = emptyMap(),
    val log: String = "",
    val running: Boolean = false,
    /** apt-get line for the cross-build tools the detected targets need; null when nothing needs installing. */
    val installCommand: String? = null,
    /** Name of the Xcode project in the folder, when the project is an iOS one (built on a macOS CI runner). */
    val iosProject: String? = null,
)

/** The slowest, most memory-hungry Gradle targets: off until the user switches them on. */
private val OPT_IN_TARGETS = setOf("gradle:assembleRelease", "gradle:lint", "gradle:clean", "gradle:screenshots")

/** A build target found in a project folder: stable id, label and the shell command that builds it. */
internal data class DetectedTarget(val id: String, val name: String, val command: String)

/** Looks at the files of [workspace] and proposes build targets for Android/Gradle, Node, Python, CMake and Make. */
internal fun detectBuildTargets(workspace: File): List<DetectedTarget> {
    val found = mutableListOf<DetectedTarget>()
    val androidRoot = findAndroidProjectRoot(workspace)
    if (androidRoot != null) {
        val relative = androidRoot.relativeTo(workspace).invariantSeparatorsPath
        for ((task, label) in listOf("assembleDebug" to "Android debug APK", "assembleRelease" to "Android release APK", "test" to "Android unit tests", "lint" to "Android lint", "clean" to "Gradle clean")) {
            val gradle = androidGradleCommand(androidRoot, task)
            val command = if (relative.isEmpty()) gradle else "cd ${ChrootRunner.quote(relative)} && $gradle"
            found += DetectedTarget("gradle:$task", label, command)
        }
        if (File(androidRoot, "app/src/screenshotTest").isDirectory || File(androidRoot, "src/screenshotTest").isDirectory) {
            val gradle = androidGradleCommand(
                androidRoot,
                ":app:testDebugUnitTest",
                "-PforgeScreenshots -PforgeRoboDir=${AndroidToolchain.ROBOLECTRIC_GUEST_DIR}",
            )
            val command = if (relative.isEmpty()) gradle else "cd ${ChrootRunner.quote(relative)} && $gradle"
            found += DetectedTarget("gradle:screenshots", "Android Robolectric UI screenshots", command)
        }
    }
    val packageJson = File(workspace, "package.json")
    if (packageJson.isFile) {
        found += DetectedTarget("npm:install", "npm install", "npm install --no-audit --no-fund")
        val scripts = runCatching { JSONObject(packageJson.readText()).optJSONObject("scripts") }.getOrNull()
        scripts?.keys()?.asSequence()?.filter { Regex("[A-Za-z0-9:_-]{1,40}").matches(it) }?.take(8)?.forEach { key ->
            found += DetectedTarget("npm:run:$key", "npm run $key", "npm run ${ChrootRunner.quote(key)}")
        }
    }
    if (File(workspace, "requirements.txt").isFile) found += DetectedTarget("pip:requirements", "pip install -r requirements.txt", "pip install -r requirements.txt")
    if (File(workspace, "pyproject.toml").isFile || File(workspace, "pytest.ini").isFile || File(workspace, "tests").isDirectory) {
        found += DetectedTarget("python:pytest", "pytest", "python3 -m pytest -q")
    }
    if (File(workspace, "CMakeLists.txt").isFile) {
        found += DetectedTarget("cmake:build", "CMake build", "cmake -S . -B build && cmake --build build -j2")
    }
    if (File(workspace, "Makefile").isFile) found += DetectedTarget("make:all", "make", "make -j2")
    // Windows and Linux builds for other CPUs/systems that the chroot can cross-compile. iOS has no on-device build.
    for (target in detectCrossTargets(workspace)) {
        if (target.onDevice) found += DetectedTarget(target.id, target.name, withToolCheck(target.command, target.tools))
    }
    return found
}

/** Build matrix: the project's build targets (detected plus your own), run one after another inside the chroot. */
class BuildMatrixController(
    context: Context,
    private val activeProject: () -> Project?,
    private val workspaceFor: (String) -> File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onWorkspaceChanged: (String) -> Unit = {},
    private val onBuildSucceeded: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val prefs = appContext.getSharedPreferences("forge_tools", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(BuildMatrixState())
    val state: StateFlow<BuildMatrixState> = mutable.asStateFlow()
    private var job: Job? = null

    private fun prefKey(projectId: String) = "buildmatrix_${projectId.filter { it.isLetterOrDigit() || it == '-' }}"

    /** Reloads the targets for the active project; keeps the log while a run is in progress. */
    fun load() {
        val project = activeProject() ?: run {
            mutable.update { BuildMatrixState() }
            return
        }
        if (mutable.value.running && mutable.value.projectId == project.id) return
        scope.launch {
            val workspace = withContext(Dispatchers.IO) { runCatching { workspaceFor(project.id) }.getOrNull() }
            val detected = withContext(Dispatchers.IO) { workspace?.let { runCatching { detectBuildTargets(it) }.getOrDefault(emptyList()) }.orEmpty() }
            val stored = readStored(project.id)
            val targets = detected.map {
                BuildTarget(it.id, it.name, it.command, detected = true, enabled = stored.isEnabled(it.id))
            } + stored.custom.map { BuildTarget(it.id, it.name, it.command, detected = false, enabled = it.id !in stored.disabled) }
            val cross = withContext(Dispatchers.IO) { workspace?.let { runCatching { detectCrossTargets(it) }.getOrDefault(emptyList()) }.orEmpty() }
            val iosProject = withContext(Dispatchers.IO) { workspace?.let { runCatching { findXcodeProject(it) }.getOrNull() } }
            mutable.update {
                BuildMatrixState(
                    projectId = project.id,
                    projectName = project.name,
                    targets = targets,
                    installCommand = crossToolchainInstallCommand(cross),
                    iosProject = iosProject,
                )
            }
        }
    }

    /** Installs the cross-build tools (MinGW, x86_64 gcc, Go, ...) the detected targets need, with the output in the log. */
    fun installCrossTools() {
        val snapshot = mutable.value
        val project = activeProject()?.takeIf { it.id == snapshot.projectId } ?: return
        val command = snapshot.installCommand ?: return
        if (snapshot.running) return
        mutable.update { it.copy(running = true, log = "▶ $command\n") }
        job = scope.launch {
            try {
                val workspace = withContext(Dispatchers.IO) { workspaceFor(project.id) }
                val result = runCatching {
                    runner.run(workspace, project.slug, command, timeoutMillis = 30 * 60_000L) { chunk ->
                        mutable.update { it.copy(log = appendLog(it.log, chunk)) }
                    }
                }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val note = when {
                    result.isFailure -> "✕ ${result.exceptionOrNull()?.message ?: "failed to start"}\n"
                    result.getOrNull()?.timedOut == true -> "✕ timed out\n"
                    result.getOrNull()?.ok == true -> "✓ cross-build tools installed\n"
                    else -> "✕ install failed (exit ${result.getOrNull()?.exitCode})\n"
                }
                mutable.update { it.copy(log = appendLog(it.log, note)) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(log = appendLog(it.log, "\n■ stopped\n")) }
                throw cancelled
            } finally {
                mutable.update { it.copy(running = false) }
                onWorkspaceChanged(project.id)
            }
        }
    }

    /** Writes `.github/workflows/build-ios.yml` for the project's Xcode project (never overwrites an existing one). */
    fun createIosWorkflow() {
        val project = activeProject()?.takeIf { it.id == mutable.value.projectId } ?: return
        scope.launch {
            var created = false
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val workspace = workspaceFor(project.id)
                    if (writeIosWorkflow(workspace)) {
                        created = true
                        "✓ $IOS_WORKFLOW_PATH created. Commit and push it, then run it from the CI/CD tool (macOS runner).\n"
                    } else {
                        "$IOS_WORKFLOW_PATH already exists or no Xcode project was found; nothing written.\n"
                    }
                }.getOrElse { "✕ ${it.message ?: "could not write the workflow"}\n" }
            }
            mutable.update { it.copy(log = appendLog(it.log, message)) }
            if (created) onWorkspaceChanged(project.id)
        }
    }

    fun toggle(id: String) {
        val project = mutable.value.projectId ?: return
        val stored = readStored(project)
        writeStored(
            project,
            if (id in OPT_IN_TARGETS) stored.copy(optIn = stored.optIn.toMutableSet().also { if (!it.add(id)) it.remove(id) })
            else stored.copy(disabled = stored.disabled.toMutableSet().also { if (!it.add(id)) it.remove(id) }),
        )
        mutable.update { s -> s.copy(targets = s.targets.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }) }
    }

    fun addCustom(name: String, command: String) {
        val project = mutable.value.projectId ?: return
        val cleanName = name.trim().take(60)
        val cleanCommand = command.trim().take(2_000)
        if (cleanName.isEmpty() || cleanCommand.isEmpty()) return
        val target = StoredTarget("custom:${UUID.randomUUID()}", cleanName, cleanCommand)
        val stored = readStored(project)
        writeStored(project, stored.copy(custom = stored.custom + target))
        mutable.update { it.copy(targets = it.targets + BuildTarget(target.id, target.name, target.command, detected = false, enabled = true)) }
    }

    fun remove(id: String) {
        val project = mutable.value.projectId ?: return
        val stored = readStored(project)
        writeStored(project, stored.copy(custom = stored.custom.filterNot { it.id == id }))
        mutable.update { s -> s.copy(targets = s.targets.filterNot { it.id == id && !it.detected }) }
    }

    fun runSelected() {
        val snapshot = mutable.value
        val project = activeProject()?.takeIf { it.id == snapshot.projectId } ?: return
        val selected = snapshot.targets.filter { it.enabled }
        if (snapshot.running || selected.isEmpty()) return
        mutable.update { it.copy(running = true, log = "", statuses = selected.associate { t -> t.id to TargetStatus.PENDING }) }
        job = scope.launch {
            var allOk = true
            try {
                val workspace = withContext(Dispatchers.IO) { workspaceFor(project.id) }
                for (step in withContext(Dispatchers.IO) { withGradleBatched(workspace, selected) }) {
                    val target = step.target
                    mutable.update { s -> s.copy(statuses = s.statuses + step.ids.associateWith { TargetStatus.RUNNING }, log = appendLog(s.log, "\n▶ ${target.name}\n")) }
                    val result = runCatching {
                        runner.run(workspace, project.slug, target.command, timeoutMillis = 45 * 60_000L) { chunk ->
                            mutable.update { it.copy(log = appendLog(it.log, chunk)) }
                        }
                    }
                    // runCatching also swallows cancellation; rethrow so Stop really stops the matrix.
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    val ok = result.getOrNull()?.ok == true
                    if (!ok) allOk = false
                    val note = when {
                        result.isFailure -> "✕ ${result.exceptionOrNull()?.message ?: "failed to start"}\n"
                        result.getOrNull()?.timedOut == true -> "✕ timed out\n"
                        ok -> "✓ ${target.name}\n"
                        else -> "✕ ${target.name} (exit ${result.getOrNull()?.exitCode})\n"
                    }
                    mutable.update { s -> s.copy(statuses = s.statuses + step.ids.associateWith { if (ok) TargetStatus.OK else TargetStatus.FAILED }, log = appendLog(s.log, note)) }
                }
            } catch (cancelled: CancellationException) {
                allOk = false
                mutable.update { it.copy(log = appendLog(it.log, "\n■ stopped\n")) }
                throw cancelled
            } finally {
                mutable.update { it.copy(running = false) }
                onWorkspaceChanged(project.id)
                if (allOk) onBuildSucceeded(project.id)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private data class StoredTarget(val id: String, val name: String, val command: String)
    /** [optIn]: the [OPT_IN_TARGETS] the user switched on; every other target is on unless it is in [disabled]. */
    private data class Stored(val custom: List<StoredTarget>, val disabled: Set<String>, val optIn: Set<String> = emptySet()) {
        fun isEnabled(id: String) = if (id in OPT_IN_TARGETS) id in optIn else id !in disabled
    }

    private fun readStored(projectId: String): Stored = runCatching {
        val json = JSONObject(prefs.getString(prefKey(projectId), "{}") ?: "{}")
        val custom = json.optJSONArray("custom") ?: JSONArray()
        val disabled = json.optJSONArray("disabled") ?: JSONArray()
        val optIn = json.optJSONArray("optIn") ?: JSONArray()
        Stored(
            custom = (0 until custom.length()).mapNotNull { i ->
                custom.optJSONObject(i)?.let { StoredTarget(it.optString("id"), it.optString("name"), it.optString("command")) }
                    ?.takeIf { it.id.isNotBlank() && it.command.isNotBlank() }
            },
            disabled = (0 until disabled.length()).map { disabled.optString(it) }.toSet(),
            optIn = (0 until optIn.length()).map { optIn.optString(it) }.toSet(),
        )
    }.getOrDefault(Stored(emptyList(), emptySet()))

    private fun writeStored(projectId: String, stored: Stored) {
        val json = JSONObject()
            .put("custom", JSONArray(stored.custom.map { JSONObject().put("id", it.id).put("name", it.name).put("command", it.command) }))
            .put("disabled", JSONArray(stored.disabled.toList()))
            .put("optIn", JSONArray(stored.optIn.toList()))
        prefs.edit().putString(prefKey(projectId), json.toString()).apply()
    }

    private fun appendLog(current: String, text: String): String = (current + text).takeLast(MAX_LOG_CHARS)

    private companion object {
        const val MAX_LOG_CHARS = 80_000
    }
}

/** One run of the matrix: a target, or several Gradle targets merged into it ([ids] all get its result). */
internal data class MatrixStep(val target: BuildTarget, val ids: List<String>)

/**
 * The selected targets in run order, with the detected Gradle tasks (except clean, which must run alone) merged into
 * one Gradle invocation at the place of the first: Gradle start-up and configuration take minutes on a phone, so
 * paying them once instead of once per task is most of the time saved.
 */
internal fun withGradleBatched(workspace: File, selected: List<BuildTarget>): List<MatrixStep> {
    val gradle = selected.filter { it.detected && it.id.startsWith("gradle:") && it.id != "gradle:clean" }
    val root = if (gradle.size < 2) null else findAndroidProjectRoot(workspace)
    if (root == null) return selected.map { MatrixStep(it, listOf(it.id)) }
    val relative = root.relativeTo(workspace).invariantSeparatorsPath
    val tasks = gradle.joinToString(" ") { it.id.removePrefix("gradle:") }
    val command = androidGradleCommand(root, tasks).let { if (relative.isEmpty()) it else "cd ${ChrootRunner.quote(relative)} && $it" }
    val merged = MatrixStep(
        BuildTarget(gradle.first().id, gradle.joinToString(" + ") { it.name }, command, detected = true, enabled = true),
        gradle.map { it.id },
    )
    return selected.mapNotNull { target ->
        when {
            target == gradle.first() -> merged
            target in gradle -> null
            else -> MatrixStep(target, listOf(target.id))
        }
    }
}
