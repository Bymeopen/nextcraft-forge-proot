package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorkspaceSnapshot(val id: String, val file: File, val label: String, val createdAtMillis: Long, val sizeBytes: Long)

/**
 * Manual workspace snapshots: a zip of the project files (without dependency and build directories) that can be
 * restored later. Separate from the per-turn checkpoint that backs Keep / Revert.
 */
class SnapshotManager(private val filesDir: File) {

    private fun dir(projectId: String): File {
        val safe = projectId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(safe.isNotEmpty()) { "Invalid project id" }
        return File(filesDir, "snapshots/$safe").apply { mkdirs() }
    }

    fun list(projectId: String): List<WorkspaceSnapshot> =
        dir(projectId).listFiles { f -> f.isFile && f.name.endsWith(".zip") }.orEmpty()
            .mapNotNull(::parse)
            .sortedByDescending { it.createdAtMillis }

    private fun parse(file: File): WorkspaceSnapshot? {
        val match = NAME.matchEntire(file.name) ?: return null
        val created = match.groupValues[1].toLongOrNull() ?: return null
        return WorkspaceSnapshot(file.name.removeSuffix(".zip"), file, match.groupValues[2].replace('_', ' ').ifBlank { "Snapshot" }, created, file.length())
    }

    fun create(projectId: String, workspace: File, label: String): WorkspaceSnapshot {
        require(workspace.isDirectory) { "Project folder not found" }
        val cleanLabel = label.trim().take(40).filter { it.isLetterOrDigit() || it in " -_." }.trim().ifBlank { "Snapshot" }
        val created = System.currentTimeMillis()
        val target = File(dir(projectId), "$created-${cleanLabel.replace(' ', '_')}.zip")
        val partial = File(target.parentFile, target.name + ".part")
        var total = 0L
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                val root = workspace.canonicalFile
                root.walkTopDown()
                    // walkTopDown follows a symlinked folder (it only asks isDirectory), so links are refused here, not just filtered.
                    .onEnter { it == root || (it.name !in EXCLUDED_DIRS && !Files.isSymbolicLink(it.toPath())) }
                    .filter { it != root && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .forEach { file ->
                        val name = file.relativeTo(root).invariantSeparatorsPath
                        if (file.isDirectory) {
                            zip.putNextEntry(ZipEntry("$name/"))
                            zip.closeEntry()
                        } else if (file.isFile) {
                            total += file.length()
                            check(total <= MAX_TOTAL_BYTES) { "Project is larger than ${MAX_TOTAL_BYTES / 1_048_576} MB; remove build output first." }
                            zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
            }
            check(partial.renameTo(target)) { "Could not save the snapshot" }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        return parse(target) ?: error("Could not read the snapshot back")
    }

    /**
     * Replaces the project files with [snapshot]. A safety snapshot of the current state is taken first so the restore
     * itself can be undone.
     */
    fun restore(projectId: String, workspace: File, snapshot: WorkspaceSnapshot): WorkspaceSnapshot {
        require(snapshot.file.isFile) { "Snapshot file is missing" }
        val root = workspace.canonicalFile
        // Validate every entry before touching the project, so a bad archive cannot leave it half-cleared.
        ZipInputStream(snapshot.file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(File(root, entry.name).canonicalFile.toPath().startsWith(root.toPath())) { "Snapshot contains an unsafe path" }
                entry = zip.nextEntry
            }
        }
        val safety = create(projectId, workspace, "Before restore")
        check(clearRestorable(root)) { "Could not clear the project folder; a build or terminal may still be using it." }
        ZipInputStream(snapshot.file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(root, entry.name).canonicalFile
                // Zip-slip guard: entries must stay inside the project folder.
                require(out.toPath().startsWith(root.toPath())) { "Snapshot contains an unsafe path" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    if (entry.time > 0) out.setLastModified(entry.time)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return safety
    }

    fun delete(snapshot: WorkspaceSnapshot): Boolean = snapshot.file.delete()

    /**
     * Deletes what [create] would have archived, and only that. Folders named in [EXCLUDED_DIRS] are never archived, so
     * they are kept at every depth (not just at the top): a `scripts/build` or `packages/a/node_modules` the snapshot
     * never saw must survive a restore. Symbolic links are not archived either and are left alone.
     */
    private fun clearRestorable(dir: File): Boolean {
        var ok = true
        for (child in dir.listFiles().orEmpty()) {
            when {
                Files.isSymbolicLink(child.toPath()) -> Unit
                child.isDirectory && child.name in EXCLUDED_DIRS -> Unit
                child.isDirectory -> {
                    ok = clearRestorable(child) && ok
                    // A folder that still holds an excluded subfolder stays; one that was emptied goes.
                    if (child.list().isNullOrEmpty()) ok = child.delete() && ok
                }
                else -> ok = child.delete() && ok
            }
        }
        return ok
    }

    private companion object {
        val NAME = Regex("^(\\d{10,})-(.*)\\.zip$")
        val EXCLUDED_DIRS = setOf("node_modules", ".gradle", "build", "__pycache__", ".cxx", ".venv", ".forge", ".rea")
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
    }
}
