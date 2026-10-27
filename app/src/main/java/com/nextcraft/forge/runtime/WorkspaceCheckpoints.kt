package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLine
import com.nextcraft.forge.model.DiffLineType
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

class WorkspaceCheckpoints(
    private val filesDir: File,
    @Suppress("UNUSED_PARAMETER") private val context: Context? = null,
) {
    private data class DigestCacheKey(
        val absolutePath: String,
        val modifiedAt: Long,
        val byteLength: Long,
    )

    private val configuredSubroots = ConcurrentHashMap<String, String>()
    private val digestMemo = ConcurrentHashMap<DigestCacheKey, String>()

    private fun acquireMutex(projectId: String): Any =
        globalProjectLocks.computeIfAbsent(projectId) { Any() }

    private fun validateProjectToken(projectId: String): String {
        val trimmed = projectId.trim()
        val ok = trimmed.isNotEmpty() &&
            trimmed != "." &&
            '\u0000' !in trimmed &&
            '/' !in trimmed &&
            '\\' !in trimmed &&
            !trimmed.contains("..")
        require(ok) { "Unsafe project identifier" }
        return trimmed
    }

    fun ensureWorkspace(projectId: String): File {
        val token = validateProjectToken(projectId)
        branchDirectoryOverrides[token]?.let { overrideDir ->
            overrideDir.mkdirs()
            return overrideDir
        }
        val workspaceBase = File(filesDir, "workspaces/$token").apply { mkdirs() }.canonicalFile
        val subroot = configuredSubroots[token].orEmpty()
        if (subroot.isBlank()) return workspaceBase
        val nestedDir = File(workspaceBase, subroot).canonicalFile
        require(nestedDir.toPath().startsWith(workspaceBase.toPath())) { "Unsafe project root" }
        nestedDir.mkdirs()
        return nestedDir
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val token = validateProjectToken(projectId)
        val cleanRel = rootPath.replace('\\', '/').trim().trim('/')
        val validRel = cleanRel.isEmpty() || (
            '\u0000' !in cleanRel &&
                !cleanRel.startsWith('/') &&
                cleanRel.split('/').none { seg -> seg.isEmpty() || seg == "." || seg == ".." }
            )
        require(validRel) { "Unsafe project root" }

        synchronized(acquireMutex(token)) {
            val oldRel = configuredSubroots.put(token, cleanRel)
            if (oldRel != null && oldRel != cleanRel) {
                checkpointDir(token).deleteRecursively()
            }
        }
    }

    fun checkpointDir(projectId: String): File =
        File(filesDir, "checkpoints/${validateProjectToken(projectId)}/latest")

    fun createCheckpoint(projectId: String, workspace: File) {
        val token = validateProjectToken(projectId)
        synchronized(acquireMutex(token)) {
            val cpFolder = checkpointDir(token)
            val baselineRoot = File(cpFolder, "project")
            if (baselineRoot.isDirectory && (loadPendingListUnlocked(token).isNotEmpty() || hasCorruptManifest(token))) {
                return
            }
            File(cpFolder, "changes.json").delete()
            File(cpFolder, ".changes.json.tmp").delete()
            baselineRoot.mkdirs()

            val canonicalWs = workspace.canonicalFile.toPath()
            val trackedRelPaths = HashSet<String>()

            workspace.walkTopDown()
                .onEnter { dir ->
                    val rel = if (dir == workspace) "" else dir.relativeTo(workspace).invariantSeparatorsPath
                    dir == workspace || (
                        !isInternalRuntimePath(rel) &&
                            !Files.isSymbolicLink(dir.toPath()) &&
                            runCatching { dir.canonicalFile.toPath().startsWith(canonicalWs) }.getOrDefault(false)
                        )
                }
                .filter { f ->
                    f.isFile &&
                        !isInternalRuntimePath(f.relativeTo(workspace).invariantSeparatorsPath) &&
                        !Files.isSymbolicLink(f.toPath())
                }
                .forEach { srcFile ->
                    if (!srcFile.canRead()) return@forEach
                    val relPath = srcFile.relativeTo(workspace).invariantSeparatorsPath
                    trackedRelPaths.add(relPath)
                    val dstFile = safeWorkspaceFile(baselineRoot, relPath)

                    val alreadySynced = dstFile.isFile &&
                        !Files.isSymbolicLink(dstFile.toPath()) &&
                        dstFile.length() == srcFile.length() &&
                        (dstFile.lastModified() == srcFile.lastModified() || computeSha256(dstFile) == computeSha256(srcFile))

                    if (!alreadySynced) {
                        dstFile.parentFile?.mkdirs()
                        srcFile.copyTo(dstFile, overwrite = true)
                        runCatching { dstFile.setLastModified(srcFile.lastModified()) }
                            .onFailure { ErrorLog.swallowed(LOG_TAG, it) }
                    }
                }

            val canonicalBaseline = baselineRoot.canonicalFile.toPath()
            val obsoleteBackups = baselineRoot.walkTopDown()
                .filter { f ->
                    f.isFile &&
                        runCatching { f.canonicalFile.toPath().startsWith(canonicalBaseline) }.getOrDefault(false) &&
                        f.relativeTo(baselineRoot).invariantSeparatorsPath !in trackedRelPaths
                }
                .toList()
            for (obsolete in obsoleteBackups) {
                removeFileAndEmptyAncestors(baselineRoot, obsolete)
            }
        }
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val token = validateProjectToken(projectId)
        synchronized(acquireMutex(token)) {
            val cpFolder = checkpointDir(token).apply { mkdirs() }
            val combined = (loadPendingListUnlocked(token) + paths)
                .filterNot(::isInternalRuntimePath)
                .distinct()
                .sorted()
            commitManifestFile(File(cpFolder, "changes.json"), JSONArray(combined).toString())
        }
    }

    fun readChangedPaths(projectId: String): List<String> {
        val token = validateProjectToken(projectId)
        return synchronized(acquireMutex(token)) {
            loadPendingListUnlocked(token)
        }
    }

    private fun hasCorruptManifest(token: String): Boolean {
        val file = File(checkpointDir(token), "changes.json")
        return file.isFile && runCatching { JSONArray(file.readText()) }.isFailure
    }

    private fun loadPendingListUnlocked(token: String): List<String> {
        val file = File(checkpointDir(token), "changes.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { idx -> arr.getString(idx) }
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val token = validateProjectToken(projectId)
        synchronized(acquireMutex(token)) {
            updateOrClearPendingManifest(token, loadPendingListUnlocked(token).filter { it != path })
        }
    }

    fun revertFile(projectId: String, workspace: File, path: String): Boolean {
        val token = validateProjectToken(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(acquireMutex(token)) {
            val pendingList = loadPendingListUnlocked(token)
            if (path !in pendingList) return@synchronized false
            val baselineRoot = File(checkpointDir(token), "project")
            if (!baselineRoot.isDirectory) return@synchronized false

            restoreSingleEntry(workspace, baselineRoot, path)
            updateOrClearPendingManifest(token, pendingList.filter { it != path })
            true
        }
    }

    fun revertLatestTurn(projectId: String, workspace: File): List<String> {
        val token = validateProjectToken(projectId)
        return synchronized(acquireMutex(token)) {
            val cpFolder = checkpointDir(token)
            val baselineRoot = File(cpFolder, "project")
            val pendingList = loadPendingListUnlocked(token).filterNot(::isInternalRuntimePath)
            if (!baselineRoot.isDirectory || pendingList.isEmpty()) return@synchronized emptyList()

            for (relPath in pendingList) {
                restoreSingleEntry(workspace, baselineRoot, relPath)
            }
            cpFolder.deleteRecursively()
            pendingList
        }
    }

    private fun restoreSingleEntry(workspace: File, baselineRoot: File, relPath: String) {
        val liveFile = safeWorkspaceFile(workspace, relPath)
        val backupFile = safeWorkspaceFile(baselineRoot, relPath)
        if (backupFile.isFile) {
            liveFile.parentFile?.mkdirs()
            backupFile.copyTo(liveFile, overwrite = true)
        } else if (liveFile.isFile) {
            removeFileAndEmptyAncestors(workspace, liveFile)
        }
    }

    fun acceptFile(projectId: String, workspace: File, path: String): Boolean {
        val token = validateProjectToken(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(acquireMutex(token)) {
            val pendingList = loadPendingListUnlocked(token)
            if (path !in pendingList) return@synchronized false
            val baselineRoot = File(checkpointDir(token), "project")
            val liveFile = safeWorkspaceFile(workspace, path)
            val backupFile = safeWorkspaceFile(baselineRoot, path)

            if (liveFile.isFile) {
                backupFile.parentFile?.mkdirs()
                liveFile.copyTo(backupFile, overwrite = true)
            } else if (backupFile.isFile) {
                removeFileAndEmptyAncestors(baselineRoot, backupFile)
            }
            updateOrClearPendingManifest(token, pendingList.filter { it != path })
            true
        }
    }

    fun revertHunk(projectId: String, workspace: File, path: String, hunkIndex: Int): Boolean {
        if (hunkIndex < 0 || isInternalRuntimePath(path)) return false
        val token = validateProjectToken(projectId)
        return synchronized(acquireMutex(token)) {
            val pendingList = loadPendingListUnlocked(token)
            if (path !in pendingList) return@synchronized false
            val baselineRoot = File(checkpointDir(token), "project")
            if (!baselineRoot.isDirectory) return@synchronized false

            val liveFile = safeWorkspaceFile(workspace, path)
            val backupFile = safeWorkspaceFile(baselineRoot, path)
            if (!backupFile.isFile || !liveFile.isFile) {
                return@synchronized revertFile(projectId, workspace, path)
            }

            val oldBytes = backupFile.readBytes()
            val newBytes = liveFile.readBytes()
            if (!isTextPayload(oldBytes) || !isTextPayload(newBytes)) {
                return@synchronized revertFile(projectId, workspace, path)
            }

            val oldLines = splitTextLines(oldBytes)
            val newLines = splitTextLines(newBytes)
            val fullDiff = buildLcsDiff(oldLines, newLines) ?: return@synchronized false
            val hunkTagged = tagDiffHunks(fullDiff)

            val reconstructed = ArrayList<String>(newLines.size)
            for ((diffLine, hId) in hunkTagged) {
                val keepLine = if (hId == hunkIndex) {
                    diffLine.type == DiffLineType.CONTEXT || diffLine.type == DiffLineType.DELETION
                } else {
                    diffLine.type == DiffLineType.CONTEXT || diffLine.type == DiffLineType.ADDITION
                }
                if (keepLine) reconstructed.add(diffLine.text)
            }

            if (reconstructed == oldLines) {
                backupFile.copyTo(liveFile, overwrite = true)
            } else {
                val crlf = newBytes.decodeToString().contains("\r\n") || oldBytes.decodeToString().contains("\r\n")
                val eol = if (crlf) "\r\n" else "\n"
                val endsWithLf = newBytes.isNotEmpty() && newBytes.last() == '\n'.code.toByte()
                val tailEol = if (endsWithLf && reconstructed.isNotEmpty()) eol else ""
                liveFile.writeText(reconstructed.joinToString(eol) + tailEol, Charsets.UTF_8)
            }

            if (computeSha256(liveFile) == computeSha256(backupFile)) {
                updateOrClearPendingManifest(token, pendingList.filter { it != path })
            }
            true
        }
    }

    fun acceptAll(projectId: String) {
        val token = validateProjectToken(projectId)
        synchronized(acquireMutex(token)) {
            checkpointDir(token).deleteRecursively()
        }
    }

    private fun updateOrClearPendingManifest(token: String, remaining: List<String>) {
        val cpFolder = checkpointDir(token)
        if (remaining.isEmpty()) {
            cpFolder.deleteRecursively()
        } else {
            commitManifestFile(File(cpFolder, "changes.json"), JSONArray(remaining).toString())
        }
    }

    private fun removeFileAndEmptyAncestors(boundaryRoot: File, targetFile: File) {
        val canonicalBoundary = runCatching { boundaryRoot.canonicalFile }.getOrDefault(boundaryRoot)
        var dirCursor = targetFile.parentFile
        targetFile.delete()
        while (dirCursor != null) {
            val current = dirCursor
            val canonicalCurrent = runCatching { current.canonicalFile }.getOrDefault(current)
            val reachedBoundary = current == boundaryRoot ||
                canonicalCurrent == canonicalBoundary ||
                !canonicalCurrent.toPath().startsWith(canonicalBoundary.toPath())
            if (reachedBoundary) break

            val entries = current.list()
            if (entries != null && entries.isEmpty() && current.delete()) {
                dirCursor = current.parentFile
            } else {
                break
            }
        }
    }

    private fun commitManifestFile(dest: File, jsonText: String) {
        dest.parentFile?.mkdirs()
        val staging = File(dest.parentFile, ".${dest.name}.tmp")
        staging.writeText(jsonText)
        if (!staging.renameTo(dest)) {
            Log.w(LOG_TAG, "Atomic rename failed for ${dest.name}; falling back to copy")
            staging.copyTo(dest, overwrite = true)
            staging.delete()
        }
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val baselineRoot = File(checkpointDir(projectId), "project")
        return paths.map { relPath ->
            val oldFile = safeWorkspaceFile(baselineRoot, relPath).takeIf { it.isFile }
            val newFile = safeWorkspaceFile(workspace, relPath).takeIf { it.isFile }
            val exceedsSizeCap = (oldFile?.length() ?: 0L) > DIFF_BYTE_CAP || (newFile?.length() ?: 0L) > DIFF_BYTE_CAP
            if (exceedsSizeCap) {
                return@map ChangeItem(
                    path = relPath,
                    additions = 0,
                    deletions = 0,
                    diffLines = listOf(
                        DiffLine(DiffLineType.INFO, "File is too large to preview safely. Undo and Keep still work."),
                    ),
                )
            }
            val oldBytes = oldFile?.readBytes() ?: ByteArray(0)
            val newBytes = newFile?.readBytes() ?: ByteArray(0)
            val isBinary = !isTextPayload(oldBytes) || !isTextPayload(newBytes)
            val (addedCount, deletedCount) = countLineDelta(oldBytes, newBytes)
            ChangeItem(
                path = relPath,
                additions = addedCount,
                deletions = deletedCount,
                diffLines = buildDiffLines(oldBytes, newBytes),
                binary = isBinary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (!isTextPayload(beforeBytes) || !isTextPayload(afterBytes)) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val oldLines = splitTextLines(beforeBytes)
        val newLines = splitTextLines(afterBytes)
        val fullDiff = buildLcsDiff(oldLines, newLines) ?: return listOf(
            DiffLine(
                DiffLineType.INFO,
                "Diff is too large to display (${oldLines.size} → ${newLines.size} lines). Undo and Keep still work.",
            ),
        )
        return foldContextLines(fullDiff)
    }

    private fun buildLcsDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine>? {
        if (oldLines.size > RENDERED_LINE_CAP || newLines.size > RENDERED_LINE_CAP) return null
        val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (r in oldLines.lastIndex downTo 0) {
            for (c in newLines.lastIndex downTo 0) {
                dp[r][c] = if (oldLines[r] == newLines[c]) {
                    dp[r + 1][c + 1] + 1
                } else {
                    maxOf(dp[r + 1][c], dp[r][c + 1])
                }
            }
        }

        val diffOut = ArrayList<DiffLine>(oldLines.size + newLines.size)
        var r = 0
        var c = 0
        while (r < oldLines.size || c < newLines.size) {
            when {
                r < oldLines.size && c < newLines.size && oldLines[r] == newLines[c] -> {
                    diffOut.add(DiffLine(DiffLineType.CONTEXT, oldLines[r], r + 1, c + 1))
                    r++
                    c++
                }
                c < newLines.size && (r == oldLines.size || dp[r][c + 1] >= dp[r + 1][c]) -> {
                    diffOut.add(DiffLine(DiffLineType.ADDITION, newLines[c], null, c + 1))
                    c++
                }
                r < oldLines.size -> {
                    diffOut.add(DiffLine(DiffLineType.DELETION, oldLines[r], r + 1, null))
                    r++
                }
            }
        }
        return diffOut
    }

    private fun computeContextMask(lines: List<DiffLine>): BooleanArray? {
        val mask = BooleanArray(lines.size)
        var hasChanges = false
        for (idx in lines.indices) {
            if (lines[idx].type != DiffLineType.CONTEXT) {
                hasChanges = true
                val low = (idx - CONTEXT_RADIUS).coerceAtLeast(0)
                val high = (idx + CONTEXT_RADIUS).coerceAtMost(lines.lastIndex)
                for (k in low..high) mask[k] = true
            }
        }
        return if (hasChanges) mask else null
    }

    private fun tagDiffHunks(lines: List<DiffLine>): List<Pair<DiffLine, Int>> {
        val showMask = computeContextMask(lines) ?: return lines.map { it to -1 }
        val result = ArrayList<Pair<DiffLine, Int>>(lines.size)
        var currentHunk = 0
        var insideBlock = false
        for (idx in lines.indices) {
            if (showMask[idx]) {
                insideBlock = true
                val item = lines[idx].copy(hunkIndex = currentHunk)
                val activeId = if (lines[idx].type != DiffLineType.CONTEXT) currentHunk else -1
                result.add(item to activeId)
            } else {
                if (insideBlock) {
                    currentHunk++
                    insideBlock = false
                }
                result.add(lines[idx] to -1)
            }
        }
        return result
    }

    private fun foldContextLines(lines: List<DiffLine>): List<DiffLine> {
        val showMask = computeContextMask(lines) ?: return lines
        val folded = ArrayList<DiffLine>()
        var cursor = 0
        var currentHunk = 0
        var insideBlock = false
        while (cursor < lines.size) {
            if (showMask[cursor]) {
                insideBlock = true
                folded.add(lines[cursor].copy(hunkIndex = currentHunk))
                cursor++
            } else {
                if (insideBlock) {
                    currentHunk++
                    insideBlock = false
                }
                val gapStart = cursor
                while (cursor < lines.size && !showMask[cursor]) cursor++
                val skipped = cursor - gapStart
                folded.add(DiffLine(DiffLineType.INFO, "… $skipped unchanged lines …", hunkIndex = -1))
            }
        }
        return folded
    }

    private fun countLineDelta(oldBytes: ByteArray, newBytes: ByteArray): Pair<Int, Int> {
        if (!isTextPayload(oldBytes) || !isTextPayload(newBytes)) {
            return (if (newBytes.isNotEmpty()) 1 else 0) to (if (oldBytes.isNotEmpty()) 1 else 0)
        }
        val oldLines = splitTextLines(oldBytes)
        val newLines = splitTextLines(newBytes)
        if (oldLines.size > STATS_LINE_CAP || newLines.size > STATS_LINE_CAP) {
            return maxOf(0, newLines.size - oldLines.size) to maxOf(0, oldLines.size - newLines.size)
        }
        var prevRow = IntArray(newLines.size + 1)
        for (oldLine in oldLines) {
            val nextRow = IntArray(newLines.size + 1)
            for (c in newLines.indices) {
                nextRow[c + 1] = if (oldLine == newLines[c]) {
                    prevRow[c] + 1
                } else {
                    maxOf(prevRow[c + 1], nextRow[c])
                }
            }
            prevRow = nextRow
        }
        val lcsLen = prevRow[newLines.size]
        return (newLines.size - lcsLen) to (oldLines.size - lcsLen)
    }

    private fun isTextPayload(bytes: ByteArray): Boolean =
        bytes.size <= DIFF_BYTE_CAP && bytes.none { it == 0.toByte() }

    private fun splitTextLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty() || !isTextPayload(bytes)) return emptyList()
        val rawSplit = bytes.decodeToString().split('\n').map { it.trimEnd('\r') }
        return if (rawSplit.lastOrNull().isNullOrEmpty()) rawSplit.dropLast(1) else rawSplit
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        val cleanRel = relative.replace('\\', '/')
        val parts = cleanRel.split('/')
        val validSyntax = cleanRel.isNotBlank() &&
            '\u0000' !in cleanRel &&
            !cleanRel.startsWith('/') &&
            parts.isNotEmpty() &&
            parts.none { it.isEmpty() || it == "." || it == ".." }
        require(validSyntax) { "Unsafe workspace path" }

        val target = File(root, cleanRel)
        val canonicalRoot = root.canonicalFile
        val rootPathObj = canonicalRoot.toPath()

        var node: File? = target
        while (node != null && node != root && node.canonicalFile != canonicalRoot) {
            require(!Files.isSymbolicLink(node.toPath())) {
                "Symbolic links cannot be restored or diffed directly"
            }
            node = node.parentFile
        }
        val parentPathObj = (target.parentFile ?: root).canonicalFile.toPath()
        require(parentPathObj.startsWith(rootPathObj)) { "Workspace path escapes project" }
        val targetPathObj = target.canonicalFile.toPath()
        require(targetPathObj.startsWith(rootPathObj) && targetPathObj != rootPathObj) {
            "Workspace path escapes project"
        }
        return target
    }

    fun snapshot(root: File): Map<String, String> {
        val rootBoundary = root.canonicalFile.toPath()
        val map = LinkedHashMap<String, String>()
        root.walkTopDown()
            .onEnter { dir ->
                val rel = if (dir == root) "" else dir.relativeTo(root).invariantSeparatorsPath
                dir == root || (
                    !isInternalRuntimePath(rel) &&
                        !Files.isSymbolicLink(dir.toPath()) &&
                        runCatching { dir.canonicalFile.toPath().startsWith(rootBoundary) }.getOrDefault(false)
                    )
            }
            .filter { f ->
                f.isFile &&
                    !isInternalRuntimePath(f.relativeTo(root).invariantSeparatorsPath) &&
                    !Files.isSymbolicLink(f.toPath())
            }
            .forEach { f ->
                val hash = runCatching { computeSha256(f) }.getOrNull()
                if (hash != null) {
                    map[f.relativeTo(root).invariantSeparatorsPath] = hash
                }
            }
        return map
    }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val current = snapshot(root)
        val allPaths = HashSet<String>(before.size + current.size).apply {
            addAll(before.keys)
            addAll(current.keys)
        }
        return allPaths.filter { p -> before[p] != current[p] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val clean = path.replace('\\', '/')
        if (clean == ".claude.json") return true
        var underSrcDir = false
        for (part in clean.split('/')) {
            if (part in EXCLUDED_DIR_NAMES) return true
            if (part in ARTIFACT_DIR_NAMES && !underSrcDir) return true
            if (part == "src") underSrcDir = true
        }
        return false
    }

    private fun computeSha256(file: File): String {
        val modTime = file.lastModified()
        val fileLen = file.length()
        val cacheKey = if (modTime > 0L) DigestCacheKey(file.absolutePath, modTime, fileLen) else null
        if (cacheKey != null) {
            digestMemo[cacheKey]?.let { return it }
        }
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        file.inputStream().use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val hexDigest = HEX_FORMATTER.formatHex(md.digest())
        if (cacheKey != null && file.lastModified() == modTime && file.length() == fileLen) {
            if (digestMemo.size > 4_096) digestMemo.clear()
            digestMemo[cacheKey] = hexDigest
        }
        return hexDigest
    }

    companion object {
        private const val LOG_TAG = "WorkspaceCheckpoints"
        private const val STATS_LINE_CAP = 2_000
        private const val RENDERED_LINE_CAP = 600
        private const val DIFF_BYTE_CAP = 1_000_000L
        private const val CONTEXT_RADIUS = 3
        private val HEX_FORMATTER = HexFormat.of()

        private val EXCLUDED_DIR_NAMES = setOf(
            ".claude",
            ".git",
            ".gradle",
            ".idea",
            ".kotlin",
            ".forge",
            ".rea",
            ".next",
            ".dart_tool",
            ".venv",
            "node_modules",
            "venv",
        )
        private val ARTIFACT_DIR_NAMES = setOf("build", "dist", "target")
        private val globalProjectLocks = ConcurrentHashMap<String, Any>()
        private val branchDirectoryOverrides = ConcurrentHashMap<String, File>()

        fun registerWorkspaceOverride(projectId: String, directory: File) {
            branchDirectoryOverrides[projectId] = directory
        }

        fun clearWorkspaceOverride(projectId: String) {
            branchDirectoryOverrides.remove(projectId)
        }
    }
}
