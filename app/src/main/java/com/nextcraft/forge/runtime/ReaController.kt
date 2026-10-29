package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.R
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.ui.UiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaState(
    /** Version recorded by the installer, null when REA is not installed in the chroot. */
    val installedVersion: String? = null,
    val busy: Boolean = false,
    val log: String = "",
    /** "full-dynamic", "emulated-dynamic" or "static-only" after a doctor run. */
    val mode: String? = null,
    val checks: List<ReaCheck> = emptyList(),
    val message: UiText? = null,
    /** File being analysed (path relative to the project), and what `rea analyze` found in it. */
    val target: String? = null,
    val analysis: ReaAnalysis? = null,
    /** Markdown report written by `rea report` (path relative to the project, and its text). */
    val reportPath: String? = null,
    val reportMarkdown: String? = null,
    /** Learn mode: lesson list, the open lesson, its quiz, and the last answer check. */
    val lessons: List<ReaLesson> = emptyList(),
    val lesson: ReaLessonDetail? = null,
    val quiz: List<ReaQuizQuestion>? = null,
    val quizResult: ReaQuizResult? = null,
    val checkResult: ReaCheckResult? = null,
    val revealedAnswer: String? = null,
    /** Install progress: percent of the steps done, the step running, and what the network delivered meanwhile. */
    val progressPercent: Int? = null,
    val progressLabel: String? = null,
    val transfer: TransferStats? = null,
)

/**
 * REA (Reverse Engine Anything) inside the chroot: installs its Python package from the app's assets, removes it, and
 * runs `rea doctor --json`. Same pattern as the other Tools controllers: a [ChrootRunner] run per command, output
 * streamed into a log, results parsed from the one JSON line the command prints last.
 */
class ReaController(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onProjectChanged: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val mutable = MutableStateFlow(ReaState())
    val state: StateFlow<ReaState> = mutable.asStateFlow()
    private var job: Job? = null

    /** Bumped by every launch and by [cancel], so a cancelled task finishing late cannot clear `busy` of the task that replaced it. */
    private val taskGeneration = java.util.concurrent.atomic.AtomicInteger()

    fun refresh() {
        scope.launch {
            val version = withContext(Dispatchers.IO) { readInstalledVersion() }
            mutable.update { it.copy(installedVersion = version) }
        }
    }

    fun install() = launchTask(R.string.rea_installing, showTransfer = true) {
        val workspace = runner.toolWorkspace(TOOL_SLUG)
        val stage = File(workspace, "stage")
        withContext(Dispatchers.IO) {
            stage.deleteRecursively()
            copyAssetDirectory(ASSET_ROOT, stage)
        }
        try {
            val result = runner.run(
                workspaceDir = workspace,
                slug = TOOL_SLUG,
                command = installScript(),
                timeoutMillis = 15 * 60_000L,
                onChunk = ::appendLog,
            )
            val version = withContext(Dispatchers.IO) { readInstalledVersion() }
            mutable.update {
                it.copy(
                    installedVersion = version,
                    progressPercent = if (result.ok && version != null) 100 else it.progressPercent,
                    message = if (result.ok && version != null) UiText.res(R.string.rea_installed, version)
                    else UiText.res(R.string.rea_install_failed),
                )
            }
        } finally {
            withContext(Dispatchers.IO) { stage.deleteRecursively() }
        }
    }

    fun uninstall() = launchTask(R.string.rea_removing) {
        val result = runner.run(
            workspaceDir = runner.toolWorkspace(TOOL_SLUG),
            slug = TOOL_SLUG,
            command = "rm -rf /opt/rea /usr/local/bin/rea /root/.pocket-rea-version /root/.nextcraft-rea-version /root/.rea && echo removed",
            onChunk = ::appendLog,
        )
        val version = withContext(Dispatchers.IO) { readInstalledVersion() }
        mutable.update {
            it.copy(
                installedVersion = version,
                mode = null,
                checks = emptyList(),
                message = if (result.ok) UiText.res(R.string.rea_removed) else UiText.res(R.string.rea_remove_failed),
            )
        }
    }

    fun runDoctor() = launchTask(R.string.rea_checking) { refreshDoctor() }

    /** Installs the optional tools (strace, ltrace, qemu-user, binwalk, squashfs-tools, cpio, unicorn, frida) and then re-runs the doctor. */
    fun installOptionalTools() = launchTask(R.string.rea_installing_optional, showTransfer = true) {
        val result = runner.run(
            workspaceDir = runner.toolWorkspace(TOOL_SLUG),
            slug = TOOL_SLUG,
            command = optionalToolsScript(),
            timeoutMillis = 30 * 60_000L,
            onChunk = ::appendLog,
        )
        refreshDoctor()
        mutable.update {
            it.copy(progressPercent = if (result.ok) 100 else it.progressPercent, message = if (result.ok) UiText.res(R.string.rea_optional_done) else UiText.res(R.string.rea_optional_failed))
        }
    }

    private suspend fun refreshDoctor() {
        val result = runner.run(
            workspaceDir = runner.toolWorkspace(TOOL_SLUG),
            slug = TOOL_SLUG,
            command = "rea doctor --json",
            timeoutMillis = 2 * 60_000L,
        )
        val response = ReaJson.parse(result.output)
        mutable.update {
            when {
                response == null -> it.copy(
                    mode = null,
                    checks = emptyList(),
                    log = result.output.takeLast(MAX_LOG_CHARS),
                    message = UiText.res(R.string.rea_no_answer),
                )
                !response.ok -> it.copy(
                    mode = null,
                    checks = emptyList(),
                    message = UiText.Raw(listOfNotNull(response.error?.message, response.error?.hint?.takeIf(String::isNotBlank)).joinToString(" ")),
                )
                else -> it.copy(mode = response.mode, checks = response.checks, message = null)
            }
        }
    }

    /**
     * Identifies and analyses [path] (relative to the project): ELF summary, strings and, when [symbol] is not null, the
     * disassembly of that function (blank = entry point).
     */
    fun analyze(project: Project, path: String, strings: Boolean, interesting: Boolean, symbol: String?) =
        launchTask(R.string.rea_analyzing) {
            val command = analyzeCommand(path, strings, interesting, symbol)
            val response = runInProject(project, command)
            mutable.update { current ->
                val analysis = response?.takeIf { it.ok }?.analysis()
                when {
                    analysis != null -> current.copy(target = path, analysis = analysis, reportPath = null, reportMarkdown = null, message = null)
                    else -> current.copy(target = path, analysis = null, message = failureMessage(response))
                }
            }
        }

    /** Disassembles one function and keeps the rest of the current analysis. */
    fun disassemble(project: Project, path: String, symbol: String) = launchTask(R.string.rea_analyzing) {
        val response = runInProject(project, analyzeCommand(path, strings = false, interesting = false, symbol = symbol))
        val disasm = response?.takeIf { it.ok }?.analysis()?.disasm
        mutable.update { current ->
            if (disasm != null && current.analysis != null) current.copy(analysis = current.analysis.copy(disasm = disasm), message = null)
            else current.copy(message = failureMessage(response))
        }
    }

    /** Writes `.rea/<name>.report.md` in the project and loads its text. */
    fun report(project: Project, path: String) = launchTask(R.string.rea_reporting) {
        val response = runInProject(project, "rea report ${ChrootRunner.quote(path)} --json")
        val written = response?.takeIf { it.ok }?.data?.optString("path").orEmpty()
        val text = if (written.isNotEmpty()) withContext(Dispatchers.IO) { readProjectFile(project, written) } else null
        mutable.update {
            if (text != null) it.copy(reportPath = written, reportMarkdown = text, message = UiText.res(R.string.rea_report_saved, written))
            else it.copy(message = failureMessage(response))
        }
        if (text != null) onProjectChanged(project.id)
    }

    private suspend fun runInProject(project: Project, command: String): ReaResponse? {
        val workspace = WorkspaceCheckpoints(appContext.filesDir, appContext).ensureWorkspace(project.id)
        val result = runner.run(workspaceDir = workspace, slug = project.slug, command = command, timeoutMillis = 3 * 60_000L)
        val response = ReaJson.parse(result.output)
        if (response == null) appendLog(result.output)
        return response
    }

    private fun readProjectFile(project: Project, relative: String): String? = runCatching {
        val workspace = WorkspaceCheckpoints(appContext.filesDir, appContext).ensureWorkspace(project.id).canonicalFile
        val file = File(workspace, relative).canonicalFile
        // The report path comes from the command's output: never read outside the project folder.
        if (!file.path.startsWith(workspace.path + File.separator)) null else file.takeIf { it.isFile }?.readText()
    }.getOrNull()

    private fun failureMessage(response: ReaResponse?): UiText = when {
        response == null -> UiText.res(R.string.rea_no_answer)
        response.error != null -> UiText.Raw(
            listOf(response.error.message, response.error.hint).filter { it.isNotBlank() }.joinToString(" "),
        )
        else -> UiText.res(R.string.rea_no_answer)
    }

    fun selectTarget(path: String) {
        mutable.update { it.copy(target = path, analysis = null, reportPath = null, reportMarkdown = null, message = null) }
    }

    fun clearAnalysis() {
        mutable.update { it.copy(target = null, analysis = null, reportPath = null, reportMarkdown = null) }
    }

    fun cancel() {
        taskGeneration.incrementAndGet()
        job?.cancel()
        mutable.update { it.copy(busy = false) }
    }

    private fun launchTask(@androidx.annotation.StringRes label: Int, showTransfer: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        pendingLine = ""
        val generation = taskGeneration.incrementAndGet()
        mutable.update { it.copy(busy = true, message = UiText.res(label), log = "", progressPercent = null, progressLabel = null, transfer = null) }
        job = scope.launch {
            val meter = if (showTransfer) launchTransferMeter() else null
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(message = UiText.Raw(error.message ?: error.javaClass.simpleName)) }
            } finally {
                meter?.cancel()
                if (taskGeneration.get() == generation) {
                    flushPendingLog()
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    // --- Learn mode: `rea learn ...` runs in the tool workspace; progress lives in /root/.rea/progress.json ----------------

    private suspend fun learn(args: String): ReaResponse? {
        val result = runner.run(
            workspaceDir = runner.toolWorkspace(TOOL_SLUG),
            slug = TOOL_SLUG,
            command = "rea learn $args --json",
            timeoutMillis = 3 * 60_000L,
        )
        val response = ReaJson.parse(result.output)
        if (response == null) appendLog(result.output)
        return response
    }

    private suspend fun refreshLessons() {
        val response = learn("list")
        mutable.update { if (response?.ok == true) it.copy(lessons = response.lessons()) else it.copy(message = failureMessage(response)) }
    }

    private suspend fun refreshLesson(n: Int) {
        val response = learn("show $n")
        mutable.update { if (response?.ok == true) it.copy(lesson = response.lessonDetail()) else it.copy(message = failureMessage(response)) }
    }

    fun loadLessons() = launchTask(R.string.rea_loading_lessons) {
        refreshLessons()
        mutable.update { it.copy(message = if (it.lessons.isEmpty()) it.message else null) }
    }

    fun openLesson(n: Int) = launchTask(R.string.rea_loading_lessons) {
        mutable.update { it.copy(quiz = null, quizResult = null, checkResult = null, revealedAnswer = null) }
        refreshLesson(n)
        mutable.update { it.copy(message = null) }
    }

    fun closeLesson() {
        mutable.update { it.copy(lesson = null, quiz = null, quizResult = null, checkResult = null, revealedAnswer = null) }
    }

    fun startExercise(n: Int) = launchTask(R.string.rea_preparing_exercise) {
        val response = learn("start $n")
        if (response?.ok == true) refreshLesson(n)
        mutable.update { it.copy(message = if (response?.ok == true) null else failureMessage(response)) }
    }

    fun checkAnswer(n: Int, answer: String) = launchTask(R.string.rea_checking_answer) {
        val response = learn("check $n ${ChrootRunner.quote(answer.trim())}")
        val result = response?.takeIf { it.ok }?.checkResult()
        mutable.update { it.copy(checkResult = result, message = if (result != null) null else failureMessage(response)) }
        if (result != null) {
            refreshLesson(n)
            refreshLessons()
        }
    }

    fun loadQuiz(n: Int) = launchTask(R.string.rea_loading_lessons) {
        val response = learn("quiz $n")
        mutable.update {
            if (response?.ok == true) it.copy(quiz = response.quizQuestions(), quizResult = null, message = null)
            else it.copy(message = failureMessage(response))
        }
    }

    /** [choices] are the 1-based options picked for each question, in order. */
    fun gradeQuiz(n: Int, choices: List<Int>) = launchTask(R.string.rea_checking_answer) {
        val response = learn("quiz $n --answers ${choices.joinToString(",")}")
        val result = response?.takeIf { it.ok }?.quizResult()
        mutable.update { it.copy(quizResult = result, message = if (result != null) null else failureMessage(response)) }
        if (result != null) refreshLessons()
    }

    fun revealAnswer(n: Int) = launchTask(R.string.rea_checking_answer) {
        val response = learn("answer $n")
        val text = response?.takeIf { it.ok }?.revealedAnswer()
        mutable.update { it.copy(revealedAnswer = text, message = if (text != null) null else failureMessage(response)) }
        if (text != null) refreshLesson(n)
    }

    private var pendingLine = ""

    /** Adds output to the log; `##STEP` lines of the install scripts move the progress instead of being shown. */
    private fun appendLog(chunk: String) {
        val (shown, step, rest) = InstallProgress.consume(pendingLine, chunk)
        pendingLine = rest
        mutable.update {
            it.copy(
                log = (it.log + shown).takeLast(MAX_LOG_CHARS),
                progressPercent = step?.percentBefore ?: it.progressPercent,
                progressLabel = step?.label ?: it.progressLabel,
            )
        }
    }

    /** Output that ended without a newline is still waiting in [pendingLine]; show it when the task is over. */
    private fun flushPendingLog() {
        val tail = pendingLine
        if (tail.isEmpty()) return
        pendingLine = ""
        if (InstallProgress.parseStep(tail) != null) return
        mutable.update { it.copy(log = (it.log + tail).takeLast(MAX_LOG_CHARS)) }
    }

    /** Samples the device's received bytes once a second while an install runs (TrafficStats needs no permission). */
    private fun CoroutineScope.launchTransferMeter(): Job? {
        val start = android.net.TrafficStats.getTotalRxBytes()
        if (start == android.net.TrafficStats.UNSUPPORTED.toLong()) return null
        val smoother = SpeedSmoother()
        smoother.add(start, android.os.SystemClock.elapsedRealtime())
        mutable.update { it.copy(transfer = TransferStats()) }
        return launch {
            while (true) {
                delay(1000)
                val now = android.net.TrafficStats.getTotalRxBytes()
                val speed = smoother.add(now, android.os.SystemClock.elapsedRealtime())
                mutable.update { it.copy(transfer = TransferStats((now - start).coerceAtLeast(0L), speed)) }
            }
        }
    }

    private fun readInstalledVersion(): String? = runCatching {
        // /root can be locked to root after an apt install in the chroot; without access the marker looks missing.
        if (!File(RuntimeInstaller.rootfsDir(appContext), "root").canExecute()) RuntimeInstaller(appContext).ensureAppAccess()
        RuntimeMarkers.read(File(RuntimeInstaller.rootfsDir(appContext), "root/.nextcraft-rea-version"))?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Copies an asset directory (recursively) to the host [target]; Python bytecode caches are not shipped. */
    private fun copyAssetDirectory(assetPath: String, target: File) {
        val entries = appContext.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (name in entries) {
            if (name == "__pycache__") continue
            copyAssetDirectory("$assetPath/$name", File(target, name))
        }
    }

    companion object {
        /** Must equal `__version__` in the Python package (a unit test checks it). */
        const val REA_VERSION = "0.9.0"
        private const val TOOL_SLUG = "rea"
        private const val ASSET_ROOT = "rea"
        private const val MAX_LOG_CHARS = 60_000

        /**
         * Runs in the chroot as root. The package was staged in the tool workspace (/workspace/rea/stage) by the app,
         * so no file is written into the rootfs from the app's own uid. Python 3 is installed from apt only if missing.
         */
        /** The `rea analyze` command line for the options of the screen; [symbol] null = no disassembly, blank = entry. */
        internal fun analyzeCommand(path: String, strings: Boolean, interesting: Boolean, symbol: String?): String = buildList {
            // A relative path that starts with `-` would be parsed by rea as an option.
            val target = if (path.startsWith("-")) "./$path" else path
            add("rea analyze ${ChrootRunner.quote(target)} --json")
            if (strings) {
                add("--strings --max-strings 300")
                if (interesting) add("--interesting")
            }
            if (symbol != null) {
                add(if (symbol.isBlank()) "--disasm" else "--disasm ${ChrootRunner.quote(symbol)}")
                add("--count 80")
            }
        }.joinToString(" ")

        /**
         * Optional tools for the dynamic phases. Each step may fail without stopping the next one (a missing package
         * must not block unicorn). unicorn comes from apt when the release has it, otherwise from pip.
         */
        internal fun optionalToolsScript(): String = """
            export DEBIAN_FRONTEND=noninteractive
            echo "##STEP 1/4 Memperbarui daftar paket"
            apt-get update -qq >/dev/null 2>&1 || true
            echo "##STEP 2/4 Memasang strace, ltrace, qemu-user, binwalk, squashfs-tools, cpio"
            apt-get install -y -qq strace ltrace qemu-user binwalk squashfs-tools cpio || echo "Peringatan: sebagian alat apt gagal dipasang."
            echo "##STEP 3/4 Memasang unicorn"
            if ! python3 -c "import unicorn" >/dev/null 2>&1; then
              apt-get install -y -qq python3-unicorn >/dev/null 2>&1 \
                || { apt-get install -y -qq python3-pip && python3 -m pip install --break-system-packages unicorn; } \
                || echo "Peringatan: unicorn gagal dipasang."
            fi
            echo "##STEP 4/4 Memasang frida"
            if ! python3 -c "import frida" >/dev/null 2>&1; then
              { apt-get install -y -qq python3-pip && python3 -m pip install --break-system-packages frida; } \
                || echo "Peringatan: frida gagal dipasang (opsional)."
            fi
            true
        """.trimIndent()

        internal fun installScript(): String = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            echo "##STEP 1/4 Memeriksa python3"
            if ! command -v python3 >/dev/null 2>&1; then
              echo "Memasang python3 (apt)…"
              apt-get update -qq
              apt-get install -y -qq python3
            fi
            # Libraries for ELF parsing and disassembly (REA 0.2): from apt, so no pip or venv is needed. If they cannot
            # be installed the analysis commands say so and `rea doctor` shows what is missing.
            echo "##STEP 2/4 Memasang capstone dan pyelftools"
            apt-get update -qq >/dev/null 2>&1 || true
            apt-get install -y -qq python3-capstone python3-pyelftools || echo "Peringatan: capstone/pyelftools gagal dipasang."
            echo "##STEP 3/4 Menyalin paket REA"
            rm -rf /opt/rea/lib/rea
            mkdir -p /opt/rea/lib /usr/local/bin
            cp -a /workspace/rea/stage/rea /opt/rea/lib/rea
            printf '#!/bin/sh\nexec env PYTHONPATH=/opt/rea/lib PYTHONDONTWRITEBYTECODE=1 python3 -m rea "§@"\n' > /usr/local/bin/rea
            chmod 755 /usr/local/bin/rea
            echo "##STEP 4/4 Memverifikasi"
            echo "$REA_VERSION" > /root/.nextcraft-rea-version
            cp /root/.nextcraft-rea-version /root/.pocket-rea-version
            rea --version
        """.trimIndent().replace('§', '$')
    }
}
