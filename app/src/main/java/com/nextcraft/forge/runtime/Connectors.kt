package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.network.LocalNetwork
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class McpTransport { HTTP, COMMAND }

/**
 * A Model Context Protocol server Claude Code can use. [target] is the https address of a remote server or the
 * command line of a local one; its secret (a bearer token, or the value of the environment variable [secretName] for
 * a command) lives in the encrypted vault, never in this record.
 */
data class McpConnector(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val target: String,
    val secretName: String = "",
    val enabled: Boolean = true,
) {
    /** Name of the server in Claude's config; the prefix marks the entries this app manages. */
    val key: String get() = MANAGED_PREFIX + id

    companion object {
        const val MANAGED_PREFIX = "forge-"
    }
}

internal object McpNames {
    /** A short id made of lowercase letters, digits and dashes, or null when nothing usable is left. */
    fun id(name: String): String? = name.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(32)
        .trim('-')
        .ifEmpty { null }

    /** Splits a command line into words; single and double quotes group words, a backslash escapes outside quotes. */
    fun splitCommandLine(line: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)
                c == '\'' || c == '"' -> { quote = c; started = true }
                c == '\\' && index + 1 < line.length -> { current.append(line[++index]); started = true }
                c.isWhitespace() -> if (started) { words += current.toString(); current.setLength(0); started = false }
                else -> { current.append(c); started = true }
            }
            index++
        }
        if (started) words += current.toString()
        return words
    }

    /** An environment variable name: letters, digits and underscores, not starting with a digit. */
    fun isEnvName(name: String): Boolean = Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name)
}

/** The connectors the user added (definitions in preferences, secrets in the encrypted vault). */
class ConnectorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nextcraft_connectors", Context.MODE_PRIVATE)
    private val vault = ApiKeyVault(context.applicationContext)

    fun list(): List<McpConnector> = runCatching {
        val array = JSONArray(prefs.getString(KEY_LIST, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val id = o.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            McpConnector(
                id = id,
                name = o.optString("name", id),
                transport = runCatching { McpTransport.valueOf(o.optString("transport")) }.getOrDefault(McpTransport.HTTP),
                target = o.optString("target"),
                secretName = o.optString("secretName"),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<McpConnector>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("transport", it.transport.name)
                    .put("target", it.target).put("secretName", it.secretName).put("enabled", it.enabled),
            )
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** Adds or replaces a connector. [secret]: null keeps the saved one, blank clears it. */
    fun upsert(connector: McpConnector, secret: String?) {
        val others = list().filterNot { it.id == connector.id }
        save(others + connector)
        when {
            secret == null -> Unit
            secret.isBlank() -> vault.remove(vaultId(connector.id))
            else -> vault.put(vaultId(connector.id), secret.trim())
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun delete(id: String) {
        save(list().filterNot { it.id == id })
        vault.remove(vaultId(id))
    }

    fun hasSecret(id: String): Boolean = vault.contains(vaultId(id))

    /** Claude Code's `mcpServers` entries for the enabled connectors, keyed by [McpConnector.key]. */
    fun mcpServersJson(): JSONObject {
        val servers = JSONObject()
        list().filter { it.enabled }.forEach { c ->
            val secret = vault.get(vaultId(c.id))?.takeIf(String::isNotBlank)
            val entry = when (c.transport) {
                McpTransport.HTTP -> {
                    if (!LocalNetwork.isPlainHttpAllowed(c.target)) return@forEach
                    JSONObject().put("type", "http").put("url", c.target).also { o ->
                        if (secret != null) o.put("headers", JSONObject().put("Authorization", "Bearer $secret"))
                    }
                }
                McpTransport.COMMAND -> {
                    val words = McpNames.splitCommandLine(c.target)
                    if (words.isEmpty()) return@forEach
                    JSONObject().put("type", "stdio").put("command", words.first()).put("args", JSONArray(words.drop(1))).also { o ->
                        if (secret != null && McpNames.isEnvName(c.secretName)) o.put("env", JSONObject().put(c.secretName, secret))
                    }
                }
            }
            servers.put(c.key, entry)
        }
        return servers
    }

    private fun vaultId(id: String) = "MCP_$id"

    private companion object {
        const val KEY_LIST = "connectors"
    }
}

data class ClaudeSkill(val name: String, val description: String)

/** Skills Claude Code loads from `~/.claude/skills/<name>/SKILL.md` inside the chroot. */
class ClaudeSkillStore(private val skillsDir: File) {
    fun list(): List<ClaudeSkill> = skillsDir.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { dir ->
            val file = File(dir, "SKILL.md").takeIf(File::isFile) ?: return@mapNotNull null
            val (name, description) = parseFrontmatter(runCatching { file.readText() }.getOrDefault(""))
            ClaudeSkill(name.ifBlank { dir.name }, description)
        }
        .sortedBy { it.name }

    private fun safeSkillDir(name: String): File {
        val clean = skillName(name) ?: throw IllegalArgumentException("Invalid skill name")
        return RootPathGuard.resolveChild(skillsDir, clean)
    }

    /** The instructions part of a saved skill, for editing. */
    fun body(name: String): String = runCatching {
        val text = File(safeSkillDir(name), "SKILL.md").readText()
        if (text.startsWith("---")) text.substringAfter("\n---", "").substringAfter('\n').trim() else text.trim()
    }.getOrDefault("")

    fun save(name: String, description: String, body: String) {
        val clean = skillName(name) ?: throw IllegalArgumentException("Invalid skill name")
        val dir = safeSkillDir(clean)
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(render(clean, description, body))
    }

    fun delete(name: String): Boolean = runCatching {
        val dir = safeSkillDir(name)
        !dir.exists() || dir.deleteRecursivelyNoFollow()
    }.getOrDefault(false)

    companion object {
        /** Skill directory name: lowercase letters, digits and dashes (Claude's rule), at most 64 characters. */
        fun skillName(text: String): String? = text.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(64)
            .trim('-')
            .ifEmpty { null }

        fun render(name: String, description: String, body: String): String {
            val oneLine = description.replace(Regex("\\s+"), " ").trim()
            return "---\nname: $name\ndescription: ${yamlScalar(oneLine)}\n---\n\n${body.trim()}\n"
        }

        private fun yamlScalar(value: String): String =
            if (value.isEmpty() || value.any { it in ":#\"'{}[],&*!|>%@`" } || value.first() == '-') {
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else {
                value
            }

        /** `name` and `description` of the frontmatter block (empty strings when missing). */
        fun parseFrontmatter(text: String): Pair<String, String> {
            if (!text.startsWith("---")) return "" to ""
            val block = text.removePrefix("---").substringBefore("\n---")
            fun field(key: String): String = block.lineSequence()
                .firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim().orEmpty()
                .let { v ->
                    if (v.length >= 2 && v.first() == '"' && v.last() == '"') v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\") else v
                }
            return field("name") to field("description")
        }
    }
}
