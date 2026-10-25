package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLine
import com.nextcraft.forge.model.DiffLineType
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Uses isolated physical file copies (never hardlinks) for baseline snapshots so
 * in-place file writes (`O_TRUNC`) by coding agents never mutate the checkpoint backup.
 */
class WorkspaceCheckpoints(
    private val filesDir: File,
    @Suppress("UNUSED_PARAMETER") private val context: Context? = null,
) {
    private data class FileKey(val path: String, val mtime: Long, val size: Long)

    private val projectRoots = ConcurrentHashMap<String, String>()
    private val sha256Cache = ConcurrentHashMap<FileKey, String>()

    // Shared by every instance: the ViewModel and each runtime bridge own a WorkspaceCheckpoints.
    private fun lockFor(projectId: String): Any = projectLocks.computeIfAbsent(projectId) { Any() }

    private fun safeProjectId(projectId: String): String {
        val clean = projectId.trim()
        require(
            clean.isNotEmpty() &&
                clean != "." &&
                !clean.contains('\u0000') &&
                !clean.contains('/') &&
                !clean.contains('\\') &&
                !clean.contains(".."),
        ) {
            "Unsafe project identifier"
        }
        return clean
    }

    fun ensureWorkspace(projectId: String): File {
        val safeId = safeProjectId(projectId)
        // A chat branch (its own clone of the project) registered a directory for its pseudo project id.
        workspaceOverrides[safeId]?.let { return it.apply { mkdirs() } }
        val base = File(filesDir, "workspaces/$safeId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[safeId].orEmpty()
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val safeId = safeProjectId(projectId)
        val normalized = rootPath.replace('\\', '/').trim().trim('/')
        require(
            normalized.isBlank() || (
                !normalized.contains('\u0000') &&
                    !normalized.startsWith('/') &&
                    normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
                ),
        ) {
            "Unsafe project root"
        }
        synchronized(lockFor(safeId)) {
            // First call after a process start has no previous root; the pending Keep/Undo baseline on disk must survive.
            val previous = projectRoots.put(safeId, normalized)
            if (previous != null && previous != normalized) checkpointDir(safeId).deleteRecursively()
        }
    }

    fun checkpointDir(projectId: String) = File(filesDir, "checkpoints/${safeProjectId(projectId)}/latest")

    fun createCheckpoint(projectId: String, workspace: File) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            // Keep the original baseline only while unreviewed pending files exist.
            if (backup.isDirectory && (readChangedPathsUnlocked(safeId).isNotEmpty() || isManifestUnreadable(safeId))) return
            File(checkpoint, "changes.json").delete()
            File(checkpoint, ".changes.json.tmp").delete()
            backup.mkdirs()
            val workspacePath = workspace.canonicalFile.toPath()
            val activeRelatives = HashSet<String>()
            workspace.walkTopDown()
                .onEnter { directory ->
                    val relative = if (directory == workspace) "" else directory.relativeTo(workspace).invariantSeparatorsPath
                    directory == workspace || (
                        !isInternalRuntimePath(relative) &&
                            !Files.isSymbolicLink(directory.toPath()) &&
                            runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                        )
                }
                // Directories are already confined to the workspace by onEnter, so a regular file inside one cannot
                // escape it; a canonicalFile() per file was a realpath walk for every file of every prompt.
                .filter {
                    it.isFile &&
                        !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                        !Files.isSymbolicLink(it.toPath())
                }
                .forEach { source ->
                    // A file only root can read (made by a build in the chroot) must not abort the whole turn.
                    if (!source.canRead()) return@forEach
                    val relative = source.relativeTo(workspace).invariantSeparatorsPath
                    activeRelatives.add(relative)
                    val destination = safeWorkspaceFile(backup, relative)
                    // Fast path: identical size + mtime means the baseline is current. Only hash
                    // when metadata differs, so large workspaces are not re-digested every prompt.
                    val upToDate = destination.isFile &&
                        !Files.isSymbolicLink(destination.toPath()) &&
                        destination.length() == source.length() &&
                        (
                            destination.lastModified() == source.lastModified() ||
                                digest(destination) == digest(source)
                            )
                    if (!upToDate) {
                        destination.parentFile?.mkdirs()
                        // Always copy bytes into a distinct inode so in-place O_TRUNC edits in
                        // workspace never overwrite the checkpoint baseline!
                        source.copyTo(destination, overwrite = true)
                        runCatching { destination.setLastModified(source.lastModified()) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("WorkspaceCheckpoints", it) }
                    }
                }
            val backupPath = backup.canonicalFile.toPath()
            val staleFiles = backup.walkTopDown()
                .filter {
                    it.isFile &&
                        runCatching { it.canonicalFile.toPath().startsWith(backupPath) }.getOrDefault(false) &&
                        it.relativeTo(backup).invariantSeparatorsPath !in activeRelatives
                }
                .toList()
            staleFiles.forEach { stale ->
                deleteTargetAndPruneEmptyParents(backup, stale)
            }
        }
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val dir = checkpointDir(safeId).apply { mkdirs() }
            val manifest = File(dir, "changes.json")
            val merged = (readChangedPathsUnlocked(safeId) + paths)
                .filterNot(::isInternalRuntimePath)
                .distinct()
                .sorted()
            writeManifestAtomically(manifest, JSONArray(merged).toString())
        }
    }

    fun readChangedPaths(projectId: String): List<String> {
        val safeId = safeProjectId(projectId)
        return synchronized(lockFor(safeId)) {
            readChangedPathsUnlocked(safeId)
        }
    }

    /** A manifest that exists but cannot be parsed still guards an undo baseline; do not overwrite that baseline. */
    private fun isManifestUnreadable(safeId: String): Boolean {
        val manifest = File(checkpointDir(safeId), "changes.json")
        return manifest.isFile && runCatching { JSONArray(manifest.readText()) }.isFailure
    }

    private fun readChangedPathsUnlocked(safeId: String): List<String> {
        val manifest = File(checkpointDir(safeId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val remaining = readChangedPathsUnlocked(safeId).filterNot { it == path }
            if (remaining.isEmpty()) {
                checkpointDir(safeId).deleteRecursively()
            } else {
                val manifest = File(checkpointDir(safeId), "changes.json")
                writeManifestAtomically(manifest, JSONArray(remaining).toString())
            }
        }
    }

    fun revertFile(projectId: String, workspace: File, path: String): Boolean {
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val backup = File(checkpointDir(safeId), "project")
            if (!backup.isDirectory) return@synchronized false
            val target = safeWorkspaceFile(workspace, path)
            val original = safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                deleteTargetAndPruneEmptyParents(workspace, target)
            }
            val remaining = pending.filterNot { it == path }
            if (remaining.isEmpty()) {
                checkpointDir(safeId).deleteRecursively()
            } else {
                writeManifestAtomically(File(checkpointDir(safeId), "changes.json"), JSONArray(remaining).toString())
            }
            true
        }
    }

    fun revertLatestTurn(projectId: String, workspace: File): List<String> {
        val safeId = safeProjectId(projectId)
        return synchronized(lockFor(safeId)) {
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            val paths = readChangedPathsUnlocked(safeId).filterNot(::isInternalRuntimePath)
            if (!backup.isDirectory || paths.isEmpty()) return@synchronized emptyList()
            paths.forEach { path ->
                val target = safeWorkspaceFile(workspace, path)
                val original = safeWorkspaceFile(backup, path)
                if (original.isFile) {
                    target.parentFile?.mkdirs()
                    original.copyTo(target, overwrite = true)
                } else if (target.isFile) {
                    deleteTargetAndPruneEmptyParents(workspace, target)
                }
            }
            checkpoint.deleteRecursively()
            paths
        }
    }

    fun acceptFile(projectId: String, workspace: File, path: String): Boolean {
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            val current = safeWorkspaceFile(workspace, path)
            val baseline = safeWorkspaceFile(backup, path)
            if (current.isFile) {
                baseline.parentFile?.mkdirs()
                current.copyTo(baseline, overwrite = true)
            } else if (baseline.isFile) {
                deleteTargetAndPruneEmptyParents(backup, baseline)
            }
            val remaining = pending.filterNot { it == path }
            if (remaining.isEmpty()) {
                checkpoint.deleteRecursively()
            } else {
                writeManifestAtomically(File(checkpoint, "changes.json"), JSONArray(remaining).toString())
            }
            true
        }
    }

    fun revertHunk(projectId: String, workspace: File, path: String, hunkIndex: Int): Boolean {
        if (hunkIndex < 0) return false
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val checkpoint = checkpointDir(safeId)
            val backup = File(checkpoint, "project")
            if (!backup.isDirectory) return@synchronized false
            val target = safeWorkspaceFile(workspace, path)
            val original = safeWorkspaceFile(backup, path)
            if (!original.isFile || !target.isFile) {
                return@synchronized revertFile(projectId, workspace, path)
            }
            val beforeBytes = original.readBytes()
            val afterBytes = target.readBytes()
            if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
                return@synchronized revertFile(projectId, workspace, path)
            }
            val before = textLines(beforeBytes)
            val after = textLines(afterBytes)
            val rawDiff = computeRawDiffLines(before, after) ?: return@synchronized false
            val tagged = assignHunkIndices(rawDiff)
            val updatedLines = ArrayList<String>(after.size)
            for ((line, hIdx) in tagged) {
                if (hIdx == hunkIndex) {
                    // Revert this hunk to `before` state: keep CONTEXT and DELETION, drop ADDITION
                    if (line.type == DiffLineType.CONTEXT || line.type == DiffLineType.DELETION) {
                        updatedLines += line.text
                    }
                } else {
                    // Keep `after` state for all other hunks: keep CONTEXT and ADDITION, drop DELETION
                    if (line.type == DiffLineType.CONTEXT || line.type == DiffLineType.ADDITION) {
                        updatedLines += line.text
                    }
                }
            }
            val useCrlf = afterBytes.toString(Charsets.UTF_8).contains("\r\n") || beforeBytes.toString(Charsets.UTF_8).contains("\r\n")
            val lineSep = if (useCrlf) "\r\n" else "\n"
            val hadTrailingNewline = afterBytes.isNotEmpty() && afterBytes.last() == '\n'.code.toByte()
            val newText = updatedLines.joinToString(lineSep) + if (hadTrailingNewline && updatedLines.isNotEmpty()) lineSep else ""
            target.writeText(newText, Charsets.UTF_8)
            if (digest(target) == digest(original)) {
                val remaining = pending.filterNot { it == path }
                if (remaining.isEmpty()) {
                    checkpoint.deleteRecursively()
                } else {
                    writeManifestAtomically(File(checkpoint, "changes.json"), JSONArray(remaining).toString())
                }
            }
            true
        }
    }

    fun acceptAll(projectId: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            checkpointDir(safeId).deleteRecursively()
        }
    }

    private fun deleteTargetAndPruneEmptyParents(root: File, target: File) {
        val rootCanonical = runCatching { root.canonicalFile }.getOrDefault(root)
        var parent = target.parentFile
        target.delete()
        while (parent != null) {
            val currentParent = parent
            val parentCanonical = runCatching { currentParent.canonicalFile }.getOrDefault(currentParent)
            if (currentParent == root || parentCanonical == rootCanonical || !parentCanonical.toPath().startsWith(rootCanonical.toPath())) {
                break
            }
            val children = currentParent.list()
            if (children != null && children.isEmpty() && currentParent.delete()) {
                parent = currentParent.parentFile
            } else {
                break
            }
        }
    }

    private fun writeManifestAtomically(manifest: File, content: String) {
        manifest.parentFile?.mkdirs()
        val tmp = File(manifest.parentFile, ".${manifest.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(manifest)) {
            android.util.Log.w("Checkpoints", "Atomic rename failed for ${manifest.name}; falling back to copy")
            tmp.copyTo(manifest, overwrite = true)
            tmp.delete()
        }
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val beforeFile = safeWorkspaceFile(backup, path).takeIf(File::isFile)
            val afterFile = safeWorkspaceFile(workspace, path).takeIf(File::isFile)
            if (listOfNotNull(beforeFile, afterFile).any { it.length() > MAX_DIFF_FILE_BYTES }) {
                return@map ChangeItem(
                    path = path,
                    additions = 0,
                    deletions = 0,
                    diffLines = listOf(
                        DiffLine(
                            DiffLineType.INFO,
                            "File is too large to preview safely. Undo and Keep still work.",
                        ),
                    ),
                )
            }
            val before = beforeFile?.readBytes() ?: ByteArray(0)
            val after = afterFile?.readBytes() ?: ByteArray(0)
            val binary = !before.isProbablyText() || !after.isProbablyText()
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        val raw = computeRawDiffLines(before, after) ?: return listOf(
            DiffLine(
                DiffLineType.INFO,
                "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
            ),
        )
        return collapseUnchangedLines(raw)
    }

    private fun computeRawDiffLines(before: List<String>, after: List<String>): List<DiffLine>? {
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) return null
        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return result
    }

    private fun assignHunkIndices(lines: List<DiffLine>): List<Pair<DiffLine, Int>> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines.map { it to -1 }
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val out = ArrayList<Pair<DiffLine, Int>>(lines.size)
        var hunkId = 0
        var inVisibleBlock = false
        for (i in lines.indices) {
            if (visible[i]) {
                if (!inVisibleBlock) {
                    inVisibleBlock = true
                }
                val tagged = lines[i].copy(hunkIndex = hunkId)
                out += tagged to (if (lines[i].type != DiffLineType.CONTEXT) hunkId else -1)
            } else {
                if (inVisibleBlock) {
                    hunkId++
                    inVisibleBlock = false
                }
                out += lines[i] to -1
            }
        }
        return out
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        var hunkId = 0
        var seenVisibleBlock = false
        while (index < lines.size) {
            if (visible[index]) {
                seenVisibleBlock = true
                result += lines[index++].copy(hunkIndex = hunkId)
            } else {
                if (seenVisibleBlock) {
                    hunkId++
                    seenVisibleBlock = false
                }
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …", hunkIndex = -1)
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun ByteArray.isProbablyText(): Boolean =
        size <= MAX_DIFF_FILE_BYTES && none { it == 0.toByte() }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty() || !bytes.isProbablyText()) return emptyList()
        val lines = bytes.toString(Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        val segments = normalized.split('/')
        require(
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !normalized.startsWith('/') &&
                segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "Unsafe workspace path"
        }
        val file = File(root, normalized)
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath()
        var cursor: File? = file
        while (cursor != null && cursor != root && cursor.canonicalFile != rootCanonical) {
            require(!Files.isSymbolicLink(cursor.toPath())) {
                "Symbolic links cannot be restored or diffed directly"
            }
            cursor = cursor.parentFile
        }
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        val canonicalFile = file.canonicalFile.toPath()
        require(canonicalFile.startsWith(rootPath) && canonicalFile != rootPath) {
            "Workspace path escapes project"
        }
        return file
    }

    fun snapshot(root: File): Map<String, String> {
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (
                    !isInternalRuntimePath(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            // onEnter already keeps the walk inside the root, so no per-file canonicalFile() is needed.
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(root).invariantSeparatorsPath) &&
                    !Files.isSymbolicLink(it.toPath())
            }
            // A file the app cannot read (root-owned, or removed meanwhile) is left out instead of failing the turn.
            .mapNotNull { file -> runCatching { digest(file) }.getOrNull()?.let { file.relativeTo(root).invariantSeparatorsPath to it } }
            .toMap()
    }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized == ".claude.json") return true
        var insideSourceTree = false
        for (segment in normalized.split('/')) {
            if (segment in IGNORED_DIRECTORY_NAMES) return true
            // build/dist/target are artifact folders, but a package or directory with that name under src/
            // is real source and must stay tracked (and undoable).
            if (segment in BUILD_OUTPUT_DIRECTORY_NAMES && !insideSourceTree) return true
            if (segment == "src") insideSourceTree = true
        }
        return false
    }

    private fun digest(file: File): String {
        val mtime = file.lastModified()
        val size = file.length()
        val key = if (mtime > 0L) FileKey(file.absolutePath, mtime, size) else null
        if (key != null) {
            sha256Cache[key]?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (key != null && file.lastModified() == mtime && file.length() == size) {
            if (sha256Cache.size > 4_096) sha256Cache.clear()
            sha256Cache[key] = hex
        }
        return hex
    }

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val MAX_DIFF_FILE_BYTES = 1_000_000L
        private const val DIFF_CONTEXT_LINES = 3
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".claude",
            ".git",
            ".gradle",
            ".idea",
            // Kotlin compiler daemon state; Gradle builds in the chroot (root) leave unreadable files here.
            ".kotlin",
            // Chat branches: one clone of the project per chat lives in here (see ChatBranches).
            ".forge",
            // REA reports and sessions (see the REA tool).
            ".rea",
            ".next",
            ".dart_tool",
            ".venv",
            "node_modules",
            "venv",
        )
        private val BUILD_OUTPUT_DIRECTORY_NAMES = setOf("build", "dist", "target")
        private val projectLocks = ConcurrentHashMap<String, Any>()

        /** Pseudo project ids of chat branches -> the clone directory they work in (shared by every instance). */
        private val workspaceOverrides = ConcurrentHashMap<String, File>()

        fun registerWorkspaceOverride(projectId: String, directory: File) {
            workspaceOverrides[projectId] = directory
        }

        fun clearWorkspaceOverride(projectId: String) {
            workspaceOverrides.remove(projectId)
        }
    }
}
