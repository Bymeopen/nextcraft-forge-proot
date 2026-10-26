package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.network.LocalNetwork
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class McpTransport { HTTP, COMMAND }

enum class McpTargetScope { ALL, MOBILE, GAME }

data class McpPreset(
    val name: String,
    val transport: McpTransport,
    val target: String,
    val secretName: String = "",
    val scope: McpTargetScope = McpTargetScope.ALL,
)

/**
 * A Model Context Protocol server Claude Code and Antigravity can use. [target] is the https address of a remote server or the
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
    /** Name of the server in Claude's and Antigravity's config; the prefix marks the entries this app manages. */
    val key: String get() = MANAGED_PREFIX + id

    companion object {
        const val MANAGED_PREFIX = "forge-"

        /** Open-source MCP server presets ready for 1-tap configuration. */
        val OPEN_SOURCE_PRESETS: List<McpPreset> = listOf(
            McpPreset(
                name = "Forge Design & Android",
                transport = McpTransport.COMMAND,
                target = "python3 ${ForgeMcpServer.FORGE_MCP_GUEST_PATH}",
                scope = McpTargetScope.MOBILE,
            ),
            McpPreset(
                name = "Context7",
                transport = McpTransport.HTTP,
                target = "https://mcp.context7.com/mcp",
            ),
            McpPreset(
                name = "Figma",
                transport = McpTransport.COMMAND,
                target = "npx -y figma-developer-mcp --stdio",
                secretName = "FIGMA_API_KEY",
            ),
            McpPreset(
                name = "TalkToFigma",
                transport = McpTransport.COMMAND,
                target = "npx -y cursor-talk-to-figma-mcp@latest",
            ),
            McpPreset(
                name = "shadcn/ui",
                transport = McpTransport.COMMAND,
                target = "npx -y @jpisnice/shadcn-ui-mcp-server",
            ),
            McpPreset(
                name = "Magic UI",
                transport = McpTransport.COMMAND,
                target = "npx -y @magicuidesign/mcp@latest",
            ),
            McpPreset(
                name = "Mermaid",
                transport = McpTransport.COMMAND,
                target = "npx -y @ustccjw/mcp-mermaid",
            ),
            McpPreset(
                name = "Excalidraw",
                transport = McpTransport.COMMAND,
                target = "npx -y mcp-excalidraw",
            ),
            McpPreset(
                name = "Playwright",
                transport = McpTransport.COMMAND,
                target = ForgeMcpServer.PLAYWRIGHT_WRAPPER_GUEST_PATH,
            ),
            McpPreset(
                name = "Mobile MCP",
                transport = McpTransport.COMMAND,
                target = "npx -y @mobilenext/mobile-mcp@latest",
                scope = McpTargetScope.MOBILE,
            ),
            McpPreset(
                name = "GitHub",
                transport = McpTransport.COMMAND,
                target = "npx -y @modelcontextprotocol/server-github",
                secretName = "GITHUB_PERSONAL_ACCESS_TOKEN",
            ),
            McpPreset(
                name = "SQLite",
                transport = McpTransport.COMMAND,
                target = "uvx mcp-server-sqlite --db-path /workspace/app.db",
            ),
            McpPreset(
                name = "PostgreSQL",
                transport = McpTransport.COMMAND,
                target = "npx -y @modelcontextprotocol/server-postgres",
                secretName = "POSTGRES_URL",
            ),
            McpPreset(
                name = "Supabase",
                transport = McpTransport.COMMAND,
                target = "npx -y @supabase/mcp-server-supabase@latest",
                secretName = "SUPABASE_ACCESS_TOKEN",
            ),
            McpPreset(
                name = "Sequential Thinking",
                transport = McpTransport.COMMAND,
                target = "npx -y @modelcontextprotocol/server-sequential-thinking",
            ),
            McpPreset(
                name = "Memory Graph",
                transport = McpTransport.COMMAND,
                target = "npx -y @modelcontextprotocol/server-memory",
            ),
            McpPreset(
                name = "Fetch Web",
                transport = McpTransport.COMMAND,
                target = "uvx mcp-server-fetch",
            ),
            McpPreset(
                name = "Desktop Commander",
                transport = McpTransport.COMMAND,
                target = "npx -y @wonderwhy-er/desktop-mcp-server",
            ),
            McpPreset(
                name = "Godot Game",
                transport = McpTransport.COMMAND,
                target = "npx -y @coding-solo/godot-mcp",
                scope = McpTargetScope.GAME,
            ),
            McpPreset(
                name = "Unity Game",
                transport = McpTransport.COMMAND,
                target = "uvx --from mcp-for-unity mcp-for-unity",
                scope = McpTargetScope.GAME,
            ),
            McpPreset(
                name = "Blender 3D",
                transport = McpTransport.COMMAND,
                target = "uvx blender-mcp",
                scope = McpTargetScope.GAME,
            ),
        )

        /**
         * Returns open-source MCP presets ordered and tailored for the active project target
         * (prioritizes [McpTargetScope.MOBILE] for mobile/Android projects and [McpTargetScope.GAME] for game projects).
         */
        fun presetsForProject(
            isMobileTarget: Boolean = false,
            isGameTarget: Boolean = false,
        ): List<McpPreset> = OPEN_SOURCE_PRESETS.sortedBy { preset ->
            when {
                isMobileTarget && preset.scope == McpTargetScope.MOBILE -> 0
                isGameTarget && preset.scope == McpTargetScope.GAME -> 0
                preset.scope == McpTargetScope.ALL -> 1
                else -> 2
            }
        }
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

    fun hasSecret(id: String): Boolean = vault.contains(vaultId(id)) || (id == "github" && vault.contains("GITHUB_TOKEN"))

    /**
     * Automatically provisions and enables the official GitHub MCP connector using the saved
     * `GITHUB_TOKEN` when GitHub is connected in the app. Does not overwrite a user's manual toggle.
     */
    fun syncGitHubConnector() {
        val ghToken = vault.get("GITHUB_TOKEN")?.trim().orEmpty()
        if (ghToken.isEmpty()) return
        val current = list()
        if (current.none { it.id == "github" }) {
            save(
                current + McpConnector(
                    id = "github",
                    name = "GitHub",
                    transport = McpTransport.COMMAND,
                    target = "npx -y @modelcontextprotocol/server-github",
                    secretName = "GITHUB_PERSONAL_ACCESS_TOKEN",
                    enabled = true,
                ),
            )
        }
    }

    /** Removes the auto-provisioned GitHub MCP connector when the user disconnects their GitHub account. */
    fun removeGitHubConnectorIfAuto() {
        if (!vault.contains(vaultId("github"))) {
            save(list().filterNot { it.id == "github" })
        }
    }

    /** Claude Code's `mcpServers` entries for the enabled connectors, keyed by [McpConnector.key]. */
    fun mcpServersJson(): JSONObject {
        syncGitHubConnector()
        val servers = JSONObject()
        list().filter { it.enabled }.forEach { c ->
            val secret = vault.get(vaultId(c.id))?.takeIf(String::isNotBlank)
                ?: if (c.id == "github" || c.secretName == "GITHUB_PERSONAL_ACCESS_TOKEN") {
                    vault.get("GITHUB_TOKEN")?.takeIf(String::isNotBlank)
                } else {
                    null
                }
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
                        val envName = c.secretName.ifBlank {
                            if (c.id == "github") "GITHUB_PERSONAL_ACCESS_TOKEN" else ""
                        }
                        if (secret != null && McpNames.isEnvName(envName)) o.put("env", JSONObject().put(envName, secret))
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
