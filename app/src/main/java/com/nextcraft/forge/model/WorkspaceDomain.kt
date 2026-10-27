package com.nextcraft.forge.model

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
    EMPTY("Empty"),
    GO("Go (Windows/Linux)"),
    GAME_LIBGDX("Game (libGDX)"),
}

enum class AndroidTemplate(val label: String) {
    COMPOSE(label = "Jetpack Compose"),
    XML(label = "Kotlin + XML"),
}

private val DATE_BADGE_FMT: DateTimeFormatter =
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
            val deltaMs = System.currentTimeMillis() - updatedAtMillis
            val sec = deltaMs / 1_000L
            if (deltaMs < 0L || sec < 60L) return "Just now"
            val min = sec / 60L
            if (min < 60L) return "${min}m ago"
            val hr = min / 60L
            if (hr < 24L) return "${hr}h ago"
            val days = hr / 24L
            return when {
                days == 1L -> "Yesterday"
                days < 7L -> "${days}d ago"
                else -> Instant.ofEpochMilli(updatedAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_BADGE_FMT)
            }
        }
}

private val DIACRITICS_PATTERN = Regex("\\p{M}+")
private val SLUG_SEPARATOR_PATTERN = Regex("[^a-z0-9]+")

fun projectSlug(rawTitle: String): String {
    val ascii = Normalizer.normalize(rawTitle, Normalizer.Form.NFKD)
        .replace(DIACRITICS_PATTERN, "")
        .lowercase(Locale.US)
    val sanitized = ascii
        .replace(SLUG_SEPARATOR_PATTERN, "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return sanitized.ifEmpty { "nextcraft" }
}

data class QuickChatIdentity(
    val displayName: String,
    val slug: String,
)

private const val QUICK_PREFIX = "quick-project-"

fun generateQuickChatIdentity(existingSlugs: Set<String>, prefixLabel: String): QuickChatIdentity {
    var maxSeen = 0
    for (item in existingSlugs) {
        if (item.startsWith(QUICK_PREFIX)) {
            val idx = item.removePrefix(QUICK_PREFIX).toIntOrNull()
            if (idx != null) maxSeen = max(maxSeen, idx)
        }
    }
    var nextNumber = maxSeen + 1
    while (existingSlugs.contains("$QUICK_PREFIX$nextNumber")) {
        nextNumber++
    }
    return QuickChatIdentity("$prefixLabel $nextNumber", "$QUICK_PREFIX$nextNumber")
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
        "Web (JavaScript / TypeScript)",
        "Websites and web apps with HTML, CSS, and JS frameworks.",
        "Node.js and npm (already included)",
    ),
    PYTHON(
        "Python",
        "Scripts, automation, data work, and Python backends.",
        "python3, pip, venv, and build tools",
    ),
    ANDROID(
        "Android (Java / Kotlin)",
        "Build Android app projects at native Chroot speed and install them directly on this phone.",
        com.nextcraft.forge.runtime.AndroidToolchain.SUMMARY,
    ),
    CPP(
        "C / C++",
        "Fast compiled programs, algorithms, and systems code.",
        "gcc, g++, make, cmake, gdb",
    ),
    PHP(
        "PHP",
        "Websites and apps with PHP — classic sites and Laravel projects.",
        "php-cli, common extensions, and Composer",
    ),
}
