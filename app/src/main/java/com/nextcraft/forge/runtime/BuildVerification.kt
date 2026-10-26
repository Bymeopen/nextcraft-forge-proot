package com.nextcraft.forge.runtime

/**
 * Local CI for Android projects: after an agent changed files, the project is built and unit-tested on the phone, and
 * a failure in the code goes back to the same agent to fix (a limited number of rounds), like a CI bot on a pull
 * request. Failures the code cannot fix (memory, missing downloads) are never sent to the agent.
 */
object BuildVerification {
    /** One Gradle run for both: JVM start-up and configuration (minutes on a phone) are paid once. */
    const val VERIFY_TASKS = "assembleDebug testDebugUnitTest"

    /** Fix rounds per chat before the loop gives up and leaves the rest to the user. */
    const val MAX_FIX_ROUNDS = 3

    /** Marks a prompt the loop sent, so a reply to it continues the loop instead of starting a new one. */
    const val AUTO_FIX_PREFIX = "[Auto-fix "

    /**
     * Gradle flags for a retry after the build ran out of memory: one worker, no parallel projects, and the Kotlin
     * compiler inside the Gradle JVM instead of a second, separate Kotlin daemon.
     */
    const val LOW_MEMORY_FLAGS = "--max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process"

    enum class FailureKind {
        /** The low-memory killer or the JVM heap ended the build; retried with [LOW_MEMORY_FLAGS]. */
        OUT_OF_MEMORY,

        /** A library or plugin could not be downloaded (offline, not in the cache); the user has to go online. */
        DEPENDENCY,

        /** The project's code or build files are wrong: the agent can fix this. */
        CODE,

        /** Anything else (crashed daemon, disk full…): reported, not sent to the agent. */
        OTHER,
    }

    fun classify(log: String, exitCode: Int, codeIssues: Int): FailureKind {
        val text = log.takeLast(200_000)
        return when {
            exitCode == 137 ||
                OOM_MARKERS.any { text.contains(it, ignoreCase = true) } -> FailureKind.OUT_OF_MEMORY
            DEPENDENCY_MARKERS.any { text.contains(it, ignoreCase = true) } -> FailureKind.DEPENDENCY
            codeIssues > 0 || CODE_MARKERS.any { text.contains(it) } -> FailureKind.CODE
            else -> FailureKind.OTHER
        }
    }

    /** True for a prompt sent by the loop itself. */
    fun isAutoFixPrompt(prompt: String): Boolean = prompt.startsWith(AUTO_FIX_PREFIX)

    /**
     * The prompt for fix round [round]: the parsed errors (or the end of the log when none were parsed) and the rules
     * a CI fix must follow. Sent to the agent, so it stays English.
     */
    fun fixPrompt(round: Int, issues: List<String>, log: String): String = buildString {
        append(AUTO_FIX_PREFIX).append(round).append('/').append(MAX_FIX_ROUNDS).append("] ")
        appendLine("The local build check (`gradle $VERIFY_TASKS`) failed after your last change. Fix the cause.")
        appendLine()
        if (issues.isNotEmpty()) {
            issues.take(MAX_ISSUES).forEach { appendLine(it).appendLine() }
            if (issues.size > MAX_ISSUES) appendLine("(${issues.size - MAX_ISSUES} more errors not shown; they may be follow-ups of these.)")
        } else {
            appendLine("```text")
            appendLine(log.lineSequence().toList().takeLast(LOG_TAIL_LINES).joinToString("\n"))
            appendLine("```")
        }
        appendLine()
        appendLine("Rules: fix the code or build files themselves. Do not delete, skip or weaken tests, and do not disable")
        appendLine("checks to make the build pass. The app re-runs the same check when you finish, so you do not need to run")
        append("the full build yourself.")
    }

    private const val MAX_ISSUES = 8
    private const val LOG_TAIL_LINES = 60

    private val OOM_MARKERS = listOf(
        "OutOfMemoryError", "Java heap space", "GC overhead limit exceeded", "Metaspace",
        "Gradle build daemon disappeared unexpectedly", "daemon disappeared", "exit value 137", "Killed",
    )
    private val DEPENDENCY_MARKERS = listOf(
        "Could not resolve", "Could not download", "No cached version of", "Plugin [id:", "was not found in any of the following sources",
        "Could not GET", "Could not HEAD", "UnknownHostException", "offline mode",
    )
    private val CODE_MARKERS = listOf(
        "e: file://", "e: /", "Compilation error", "error: ", "tests completed,", "Unresolved reference",
    )
}

/** How a build ended, for whoever started it (the local CI loop). [failure] is null when it succeeded or was stopped. */
data class BuildOutcome(
    val succeeded: Boolean,
    val cancelled: Boolean,
    val failure: BuildVerification.FailureKind?,
    val issues: List<AndroidBuildIssue>,
    val log: String,
)
