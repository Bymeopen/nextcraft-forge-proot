package com.nextcraft.forge.model

import com.nextcraft.forge.runtime.AndroidToolchain
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max

enum class ProjectKind {
    PROJECT,
    QUICK_PROJECT,
}

enum class ProjectType {
    GENERAL,
    ANDROID,
}

enum class StarterTemplate(val label: String) {
    EMPTY(label = "Empty"),
    GO(label = "Go (Windows/Linux)"),
    GAME_LIBGDX(label = "Game (libGDX)"),
}

enum class AndroidTemplate(val label: String) {
    COMPOSE(label = "Jetpack Compose"),
    XML(label = "Kotlin + XML"),
}

private val RELATIVE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val language: String,
    val slug: String = projectSlug(name),
    val rootPath: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val kind: ProjectKind = ProjectKind.PROJECT,
    val type: ProjectType = ProjectType.GENERAL,
    val androidTemplate: AndroidTemplate? = null,
) {
    val formattedUpdatedAt: String
        get() {
            val elapsedMs = System.currentTimeMillis() - updatedAtMillis
            val elapsedSec = elapsedMs / 1_000L
            if (elapsedMs < 0L || elapsedSec < 60L) return "Just now"
            val elapsedMin = elapsedSec / 60L
            if (elapsedMin < 60L) return "${elapsedMin}m ago"
            val elapsedHours = elapsedMin / 60L
            if (elapsedHours < 24L) return "${elapsedHours}h ago"
            val elapsedDays = elapsedHours / 24L
            return when {
                elapsedDays == 1L -> "Yesterday"
                elapsedDays < 7L -> "${elapsedDays}d ago"
                else -> Instant.ofEpochMilli(updatedAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(RELATIVE_DATE_FORMATTER)
            }
        }
}

private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9]+")

fun projectSlug(rawTitle: String): String {
    val decomposed = Normalizer.normalize(rawTitle, Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS_REGEX, "")
        .lowercase(Locale.US)
    val slugified = decomposed
        .replace(NON_ALPHANUM_REGEX, "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return slugified.ifEmpty { "nextcraft" }
}

data class QuickChatIdentity(
    val displayName: String,
    val slug: String,
)

private const val QUICK_PROJECT_SLUG_PREFIX = "quick-project-"

fun generateQuickChatIdentity(
    existingSlugs: Set<String>,
    prefixLabel: String,
): QuickChatIdentity {
    var highestIndex = 0
    for (candidate in existingSlugs) {
        if (candidate.startsWith(QUICK_PROJECT_SLUG_PREFIX)) {
            val parsedNum = candidate.removePrefix(QUICK_PROJECT_SLUG_PREFIX).toIntOrNull()
            if (parsedNum != null) highestIndex = max(highestIndex, parsedNum)
        }
    }
    var candidateNum = highestIndex + 1
    while (existingSlugs.contains("$QUICK_PROJECT_SLUG_PREFIX$candidateNum")) {
        candidateNum++
    }
    return QuickChatIdentity(
        displayName = "$prefixLabel $candidateNum",
        slug = "$QUICK_PROJECT_SLUG_PREFIX$candidateNum",
    )
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val sizeBytes: Long = 0L,
    val childCount: Int? = null,
    val modifiedMillis: Long = 0L,
)

data class WorkspaceDirListing(
    val entries: List<WorkspaceEntry>,
    val truncated: Boolean = false,
)

enum class DevStack(
    val label: String,
    val description: String,
    val installsSummary: String,
) {
    WEB(
        label = "Web (JavaScript / TypeScript)",
        description = "Modern frontend and fullstack web applications powered by HTML, CSS, and TypeScript.",
        installsSummary = "Node.js and npm (pre-bundled in base image)",
    ),
    PYTHON(
        label = "Python",
        description = "Backend services, CLI automation, and data engineering scripts.",
        installsSummary = "python3, pip, venv, and native build essentials",
    ),
    ANDROID(
        label = "Android (Java / Kotlin)",
        description = "Compile Android APK packages locally in Ubuntu 24.04 and install directly onto this device.",
        installsSummary = AndroidToolchain.SUMMARY,
    ),
    CPP(
        label = "C / C++",
        description = "High-performance native binaries, systems programming, and CMake projects.",
        installsSummary = "gcc, g++, make, cmake, and gdb",
    ),
    PHP(
        label = "PHP",
        description = "Server-side web applications and Laravel ecosystems.",
        installsSummary = "php-cli, standard extensions, and Composer",
    ),
}
