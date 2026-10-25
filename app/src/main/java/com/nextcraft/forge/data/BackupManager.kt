package com.nextcraft.forge.data

import android.content.Context
import com.nextcraft.forge.BuildConfig
import com.nextcraft.forge.model.ProviderKind
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore of the user's own data: project folders, chats and settings, and (only in an encrypted backup)
 * the API keys. The Linux runtime is not included: it is gigabytes and the app downloads it again.
 *
 * A backup is a zip, optionally wrapped by [BackupCrypto]. Build output and dependency folders are skipped; they are
 * recreated by the next build or install.
 */
class BackupManager(private val context: Context) {
    data class Options(
        val projects: Boolean = true,
        val chats: Boolean = true,
        val settings: Boolean = true,
        /** Only allowed with a password: the keys would otherwise sit in the file as plain text. */
        val apiKeys: Boolean = false,
    )

    data class Result(val files: Int, val bytes: Long, val skipped: Int)

    class NeedsPasswordException : Exception("This backup is encrypted")

    private val filesDir = context.filesDir
    private val prefsFile get() = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")

    /** Writes a backup to [out] (closed afterwards). [password] null or empty means not encrypted. */
    fun create(out: OutputStream, options: Options, password: CharArray?, onProgress: (String) -> Unit = {}): Result {
        val encrypted = password != null && password.isNotEmpty()
        require(!options.apiKeys || encrypted) { "API keys are only backed up with a password" }
        var files = 0
        var bytes = 0L
        var skipped = 0
        val sink = if (encrypted) BackupCrypto.encrypt(out, password!!) else out
        ZipOutputStream(sink.buffered()).use { zip ->
            fun addFile(entryName: String, file: File) {
                runCatching {
                    file.inputStream().use { input ->
                        zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                        bytes += input.copyTo(zip)
                        zip.closeEntry()
                    }
                    files++
                }.onFailure { skipped++ } // root-owned files the app cannot read
            }
            fun addTree(prefix: String, root: File) {
                if (!root.isDirectory) return
                root.walkTopDown()
                    .onEnter { dir -> dir == root || dir.name !in SKIPPED_DIRS }
                    .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .forEach { file -> addFile(prefix + file.relativeTo(root).invariantSeparatorsPath, file) }
            }

            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                JSONObject()
                    .put("format", 1)
                    .put("app", context.packageName)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("createdAt", System.currentTimeMillis())
                    .put("projects", options.projects)
                    .put("chats", options.chats)
                    .put("settings", options.settings)
                    .put("apiKeys", options.apiKeys)
                    .toString(2).toByteArray(),
            )
            zip.closeEntry()
            if (options.settings) {
                onProgress("settings")
                if (prefsFile.isFile) addFile("prefs/$PREFS_NAME.xml", prefsFile)
            }
            if (options.chats) {
                onProgress("chats")
                addTree("chats/", File(filesDir, "chats"))
            }
            if (options.projects) {
                onProgress("projects")
                addTree("workspaces/", File(filesDir, "workspaces"))
            }
            if (options.apiKeys) {
                onProgress("keys")
                val vault = ApiKeyVault(context)
                val keys = JSONObject()
                ProviderKind.entries.forEach { kind ->
                    val creds = runCatching { vault.credentials(kind.name) }.getOrDefault(emptyList())
                    if (creds.isNotEmpty()) {
                        keys.put(kind.name, JSONArray(creds.map { JSONObject().put("name", it.name).put("secret", it.secret).put("active", it.isActive) }))
                    }
                }
                zip.putNextEntry(ZipEntry(SECRETS))
                zip.write(keys.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return Result(files, bytes, skipped)
    }

    /** True when [input] needs a password; leaves the stream re-readable from the start. */
    fun isEncrypted(input: BufferedInputStream): Boolean {
        input.mark(8)
        val header = ByteArray(4)
        val n = input.read(header)
        input.reset()
        return n == 4 && BackupCrypto.isEncrypted(header)
    }

    /**
     * Restores a backup. Each part present in the backup replaces the current one (projects and chats per folder,
     * settings as a whole); API keys are added to the vault unless the same key is already there. The app must restart
     * afterwards so no in-memory copy of the settings writes the old values back.
     */
    fun restore(input: InputStream, password: CharArray?, onProgress: (String) -> Unit = {}): Result {
        val buffered = input as? BufferedInputStream ?: BufferedInputStream(input)
        val source = if (isEncrypted(buffered)) {
            if (password == null || password.isEmpty()) throw NeedsPasswordException()
            BackupCrypto.decrypt(buffered, password)
        } else {
            buffered
        }
        val staging = File(context.cacheDir, "restore-staging").apply { deleteRecursively(); mkdirs() }
        val stagingRoot = staging.canonicalFile.toPath()
        var files = 0
        var bytes = 0L
        try {
            onProgress("reading")
            ZipInputStream(source).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = File(staging, entry.name).canonicalFile
                    require(target.toPath().startsWith(stagingRoot)) { "Unsafe path in backup: ${entry.name}" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { bytes += zip.copyTo(it) }
                    files++
                }
            }
            val manifest = File(staging, MANIFEST).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
                ?: error("Not a NextCraft backup")
            require(manifest.optInt("format") == 1) { "Unsupported backup format" }

            File(staging, "workspaces").listFiles()?.forEach { project ->
                onProgress("projects")
                replaceDir(project, File(filesDir, "workspaces/${project.name}"))
            }
            File(staging, "chats").takeIf { it.isDirectory }?.let { chats ->
                onProgress("chats")
                chats.listFiles()?.forEach { item ->
                    val dest = File(filesDir, "chats/${item.name}")
                    if (item.isDirectory) replaceDir(item, dest) else item.copyTo(dest, overwrite = true)
                }
            }
            File(staging, "prefs/$PREFS_NAME.xml").takeIf { it.isFile }?.let { prefs ->
                onProgress("settings")
                prefsFile.parentFile?.mkdirs()
                prefs.copyTo(prefsFile, overwrite = true)
            }
            File(staging, SECRETS).takeIf { it.isFile }?.let { secrets ->
                onProgress("keys")
                val vault = ApiKeyVault(context)
                val json = JSONObject(secrets.readText())
                json.keys().forEach { provider ->
                    val have = runCatching { vault.credentials(provider).map { it.secret }.toSet() }.getOrDefault(emptySet())
                    val list = json.getJSONArray(provider)
                    for (i in 0 until list.length()) {
                        val key = list.getJSONObject(i)
                        val secret = key.getString("secret")
                        if (secret !in have) {
                            val added = vault.add(provider, key.optString("name"), secret)
                            if (key.optBoolean("active")) vault.activate(provider, added.id)
                        }
                    }
                }
            }
        } finally {
            staging.deleteRecursively()
        }
        return Result(files, bytes, 0)
    }

    private fun replaceDir(source: File, dest: File) {
        if (!source.exists()) {
            dest.deleteRecursively()
            return
        }
        dest.parentFile?.mkdirs()
        val backup = File(dest.parentFile, "${dest.name}.restore-bak-${System.nanoTime()}")
        val hadExisting = dest.exists() && dest.renameTo(backup)
        if (!hadExisting && dest.exists()) dest.deleteRecursively()
        try {
            if (!source.renameTo(dest)) {
                source.copyRecursively(dest, overwrite = true)
            }
            if (hadExisting) backup.deleteRecursively()
        } catch (error: Throwable) {
            if (hadExisting) {
                dest.deleteRecursively()
                backup.renameTo(dest)
            }
            throw error
        }
    }

    companion object {
        const val PREFS_NAME = "nextcraft_preferences"
        private const val MANIFEST = "nextcraft-backup.json"
        private const val SECRETS = "api-keys.json"

        /** Rebuilt by the next build or install; skipping them keeps a backup small. */
        val SKIPPED_DIRS = setOf("build", ".gradle", "node_modules", ".cxx", ".kotlin", "__pycache__", ".venv", "venv", ".next")
    }
}
