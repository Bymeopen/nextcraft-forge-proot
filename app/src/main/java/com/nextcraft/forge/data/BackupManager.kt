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
                val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                SETTINGS_PREFS_FILES.forEach { fileName ->
                    val f = File(sharedPrefsDir, fileName)
                    if (f.isFile) addFile("prefs/$fileName", f)
                }
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
                val keyIds = ProviderKind.entries.map { it.name } + EXTRA_VAULT_KEYS
                keyIds.forEach { keyId ->
                    val creds = runCatching { vault.credentials(keyId) }.getOrDefault(emptyList())
                    if (creds.isNotEmpty()) {
                        keys.put(keyId, JSONArray(creds.map { JSONObject().put("name", it.name).put("secret", it.secret).put("active", it.isActive) }))
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
        val encrypted = isEncrypted(buffered)
        val source = if (encrypted) {
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
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    require(files < MAX_RESTORE_FILES) { "Backup archive has too many files" }
                    val target = File(staging, entry.name).canonicalFile
                    require(target.toPath().startsWith(stagingRoot)) { "Unsafe path in backup: ${entry.name}" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= MAX_RESTORE_BYTES) { "Backup archive exceeds maximum restore size" }
                            out.write(buffer, 0, count)
                        }
                    }
                    files++
                }
            }
            val manifest = File(staging, MANIFEST).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
                ?: error("Not a NextCraft backup")
            require(manifest.optInt("format") == 1) { "Unsupported backup format" }

            File(staging, "workspaces").listFiles()?.forEach { project ->
                require(SAFE_RESTORE_NAME.matches(project.name)) { "Invalid project directory name: ${project.name}" }
                onProgress("projects")
                replaceDir(project, File(filesDir, "workspaces/${project.name}"))
            }
            File(staging, "chats").takeIf { it.isDirectory }?.let { chats ->
                onProgress("chats")
                chats.listFiles()?.forEach { item ->
                    require(SAFE_RESTORE_NAME.matches(item.name)) { "Invalid chat entry name: ${item.name}" }
                    val dest = File(filesDir, "chats/${item.name}")
                    if (item.isDirectory) replaceDir(item, dest) else item.copyTo(dest, overwrite = true)
                }
            }
            File(staging, "prefs").takeIf { it.isDirectory }?.let { prefsDir ->
                onProgress("settings")
                SETTINGS_PREFS_FILES.forEach { fileName ->
                    // Unencrypted backups cannot overwrite schedule or custom build-matrix shell commands.
                    if (!encrypted && fileName != "$PREFS_NAME.xml") return@forEach
                    val stagedFile = File(prefsDir, fileName)
                    if (!stagedFile.isFile) return@forEach
                    // Hazard: copying the XML over shared_prefs/ would leave the SharedPreferences instance already
                    // loaded in this process (AppPreferences, ScheduleStore, ...) stale, and its next edit().apply()
                    // would write the old map back over the restore. So the restored values are loaded through the
                    // live instance instead, which is also where the privileged flags are reset.
                    val values = PrefsXml.parse(stagedFile.readText())
                    val editor = context.getSharedPreferences(fileName.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear()
                    PrefsXml.putAll(editor, values)
                    if (fileName == "$PREFS_NAME.xml") PrefsXml.resetPrivilegedFlags(editor)
                    editor.commit()
                }
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
        private val SETTINGS_PREFS_FILES = listOf(
            "$PREFS_NAME.xml",
            "nextcraft_schedule.xml",
            "forge_tools.xml",
        )
        private val EXTRA_VAULT_KEYS = listOf(
            "GITHUB_TOKEN",
            com.nextcraft.forge.network.GeminiImageModels.KEY_ID,
        )
        private const val MANIFEST = "nextcraft-backup.json"
        private const val SECRETS = "api-keys.json"
        private const val MAX_RESTORE_FILES = 100_000
        private const val MAX_RESTORE_BYTES = 4L * 1024 * 1024 * 1024
        private val SAFE_RESTORE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

        /** Rebuilt by the next build or install; skipping them keeps a backup small. */
        val SKIPPED_DIRS = setOf("build", ".gradle", "node_modules", ".cxx", ".kotlin", "__pycache__", ".venv", "venv", ".next")
    }
}

/**
 * The `shared_prefs` XML format (`<map>` of `string`, `boolean`, `int`, `long`, `float` and `set` entries) as
 * written by Android, parsed back into values that can be loaded into a live [android.content.SharedPreferences].
 */
internal object PrefsXml {
    /** Keys whose restored values are always overridden so an untrusted archive cannot silently enable them. */
    val PRIVILEGED_RESETS: Map<String, Boolean> = mapOf(
        "android_host_tools" to false,
        "auto_approve_all_tools" to false,
        "interactive_high_risk_approvals" to true,
    )

    fun parse(xml: String): Map<String, Any> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val root = document.documentElement ?: return emptyMap()
        require(root.tagName == "map") { "Not a shared_prefs file" }
        val result = LinkedHashMap<String, Any>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            val value: Any = when (node.tagName) {
                "string" -> node.textContent
                "boolean" -> node.getAttribute("value").toBooleanStrictOrNull() ?: continue
                "int" -> node.getAttribute("value").toIntOrNull() ?: continue
                "long" -> node.getAttribute("value").toLongOrNull() ?: continue
                "float" -> node.getAttribute("value").toFloatOrNull() ?: continue
                "set" -> {
                    val items = node.childNodes
                    (0 until items.length).mapNotNull { j -> (items.item(j) as? org.w3c.dom.Element)?.takeIf { it.tagName == "string" }?.textContent }.toSet()
                }
                else -> continue
            }
            result[name] = value
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    fun putAll(editor: android.content.SharedPreferences.Editor, values: Map<String, Any>) {
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
    }

    /** Restoring a backup never enables root host-bridge execution or unprompted high-risk tool runs. */
    fun resetPrivilegedFlags(editor: android.content.SharedPreferences.Editor) {
        PRIVILEGED_RESETS.forEach { (key, value) -> editor.putBoolean(key, value) }
    }

    /** [values] with the privileged flags reset; what a restore ends up with. Pure, for tests. */
    fun withPrivilegedReset(values: Map<String, Any>): Map<String, Any> = values + PRIVILEGED_RESETS
}
