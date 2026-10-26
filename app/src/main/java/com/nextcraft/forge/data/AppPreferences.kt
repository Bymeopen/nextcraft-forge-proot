package com.nextcraft.forge.data

import android.content.Context
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.PromptRule
import com.nextcraft.forge.model.RouteMode
import com.nextcraft.forge.model.RoutingConfig
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.defaultDshApiForProvider
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.providersForAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class AppPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("nextcraft_preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).apply() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).apply() }

    /** The notification and battery-exemption prompts are shown once, when the user first starts the runtime setup. */
    var backgroundPromptsShown: Boolean
        get() = preferences.getBoolean("background_prompts_shown", false)
        set(value) { preferences.edit().putBoolean("background_prompts_shown", value).apply() }

    var backgroundSetupComplete: Boolean
        get() = preferences.getBoolean("background_setup_complete", false)
        set(value) { preferences.edit().putBoolean("background_setup_complete", value).apply() }

    var initialLanguageSelected: Boolean
        get() = preferences.getBoolean("initial_language_selected", onboardingComplete || runtimeSetupComplete)
        set(value) { preferences.edit().putBoolean("initial_language_selected", value).apply() }

    /** Coding agent engine the user picked during setup. Absent = pre-agent-choice install → Claude. */
    /** Runtime engine choice: "auto" (root chroot when granted, else PRoot), "chroot", or "proot". ADR-007. */
    var runtimeEngine: String
        get() = preferences.getString("runtime_engine", "auto") ?: "auto"
        set(value) { preferences.edit().putString("runtime_engine", value).apply() }

    var agentKind: String
        get() = preferences.getString("agent_kind", AgentKind.CLAUDE_CODE.stableId) ?: AgentKind.CLAUDE_CODE.stableId
        set(value) { preferences.edit().putString("agent_kind", value).apply() }

    /** Multi-agent routing of chat messages (see [RoutingConfig]); absent = single agent, as before. */
    var routing: RoutingConfig
        get() {
            val order = preferences.getString("routing_agents", null)?.split(',')?.mapNotNull { AgentKind.fromStoredOrNull(it) }.orEmpty()
            return RoutingConfig(
                mode = RouteMode.fromStored(preferences.getString("routing_mode", null)),
                agents = order.ifEmpty { AgentKind.entries }.distinct(),
                weights = preferences.getString("routing_weights", null)?.split(',')?.mapNotNull { pair ->
                    val (id, weight) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                    val agent = AgentKind.fromStoredOrNull(id) ?: return@mapNotNull null
                    weight.toIntOrNull()?.let { agent to it }
                }?.toMap().orEmpty(),
                rules = runCatching {
                    val array = org.json.JSONArray(preferences.getString("routing_rules", "[]"))
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.getJSONObject(index)
                        val agent = AgentKind.fromStoredOrNull(item.optString("agent")) ?: return@mapNotNull null
                        val words = item.optJSONArray("keywords")?.let { list -> (0 until list.length()).map { list.getString(it) } }.orEmpty()
                        if (words.isEmpty()) null else PromptRule(words, agent)
                    }
                }.getOrDefault(emptyList()),
            )
        }
        set(value) {
            preferences.edit()
                .putString("routing_mode", value.mode.stableId)
                .putString("routing_agents", value.agents.joinToString(",") { it.stableId })
                .putString(
                    "routing_rules",
                    org.json.JSONArray().apply {
                        value.rules.forEach { rule ->
                            put(org.json.JSONObject().put("agent", rule.agent.stableId).put("keywords", org.json.JSONArray(rule.keywords)))
                        }
                    }.toString(),
                )
                .putString("routing_weights", value.weights.entries.joinToString(",") { "${it.key.stableId}:${it.value}" })
                .apply()
        }

    /** Messages routed so far; the rotation position of [RouteMode.BALANCED]. */
    var routeCounter: Int
        get() = preferences.getInt("routing_counter", 0)
        set(value) { preferences.edit().putInt("routing_counter", value).apply() }

    /** Agent chosen for the initial runtime installation; used as the leading UI tab. */
    var primaryAgentKind: String
        get() = preferences.getString("primary_agent_kind", "") ?: ""
        set(value) { preferences.edit().putString("primary_agent_kind", value).apply() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "claude-sonnet-5-5-low")
            ?.takeIf { it.isNotBlank() } ?: "claude-sonnet-5-5-low"
        set(value) { preferences.edit().putString("agent_antigravity_model", value).apply() }

    /** Email of the signed-in Claude account, remembered so the account card shows it before `claude auth status` answers. */
    var claudeAccountEmail: String
        get() = preferences.getString("claude_account_email", "") ?: ""
        set(value) { preferences.edit().putString("claude_account_email", value).apply() }

    /** Claude Code effort level (low/medium/high/xhigh/max); empty keeps the model's own default. */
    var claudeEffort: String
        get() = preferences.getString("agent_claude_effort", "") ?: ""
        set(value) { preferences.edit().putString("agent_claude_effort", value).apply() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "low") ?: "low"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var antigravitySignedIn: Boolean
        get() = preferences.getBoolean("agent_antigravity_signed_in", false)
        set(value) { preferences.edit().putBoolean("agent_antigravity_signed_in", value).apply() }

    var antigravityAccountEmail: String
        get() = preferences.getString("agent_antigravity_account_email", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_account_email", value).apply() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).apply() }

    /** Keep the agent's file changes automatically after each turn instead of asking for a review. On by default. */
    var autoKeepChanges: Boolean
        get() = preferences.getBoolean("auto_keep_changes", true)
        set(value) { preferences.edit().putBoolean("auto_keep_changes", value).apply() }

    /** Agents may run Android commands as root (screencap, dumpsys, pm, input) through android-shell. Off by default. */
    var androidHostTools: Boolean
        get() = preferences.getBoolean("android_host_tools", false)
        set(value) { preferences.edit().putBoolean("android_host_tools", value).apply() }

    /** Offer the libGDX game starter in New project and its build targets. Off by default. */
    var gameTemplatesEnabled: Boolean
        get() = preferences.getBoolean("game_templates_enabled", false)
        set(value) { preferences.edit().putBoolean("game_templates_enabled", value).apply() }

    /** Auto-commit and push the workspace to GitHub after every agent turn. Off by default. */
    var autoSyncGitHub: Boolean
        get() = preferences.getBoolean("auto_sync_github", false)
        set(value) { preferences.edit().putBoolean("auto_sync_github", value).apply() }

    /** Announce new app builds with a notification (checked in the background every few hours). On by default. */
    var updateNotifications: Boolean
        get() = preferences.getBoolean("update_notifications", true)
        set(value) { preferences.edit().putBoolean("update_notifications", value).apply() }

    /** Downloads and installs a newer build in the background while the app is idle (root needed; otherwise only notifies). */
    var autoInstallUpdates: Boolean
        get() = preferences.getBoolean("auto_install_updates", true)
        set(value) { preferences.edit().putBoolean("auto_install_updates", value).apply() }

    /** versionCode of the newest build already announced, so one build never notifies twice. */
    var lastNotifiedUpdateCode: Long
        get() = preferences.getLong("last_notified_update_code", 0L)
        set(value) { preferences.edit().putLong("last_notified_update_code", value).apply() }

    /** Adds a note about the `rea` toolkit to chat messages that concern binaries. Off by default: nothing extra is sent. */
    /** Lets the agent create timers with a `[[schedule: …]]` line in its reply (and tells it how). */
    var scheduleEnabled: Boolean
        get() = preferences.getBoolean("schedule_enabled", true)
        set(value) { preferences.edit().putBoolean("schedule_enabled", value).apply() }

    var reaChatHint: Boolean
        get() = preferences.getBoolean("rea_chat_hint", false)
        set(value) { preferences.edit().putBoolean("rea_chat_hint", value).apply() }
    /** Id of a project in its delete-undo window; if the app dies before the files are wiped, the next start finishes it. */
    var pendingProjectWipe: String?
        get() = preferences.getString("pending_project_wipe", null)?.takeIf { it.isNotBlank() }
        set(value) { preferences.edit().apply { if (value == null) remove("pending_project_wipe") else putString("pending_project_wipe", value) }.apply() }

    /** Every Claude Code chat works in its own branch from its first turn, not only when another chat is running. */
    var autoBranchChats: Boolean
        get() = preferences.getBoolean("auto_branch_chats", false)
        set(value) { preferences.edit().putBoolean("auto_branch_chats", value).apply() }

    /** Whether high-risk tool executions prompt for interactive approval instead of immediate rejection. */
    var interactiveHighRiskApprovals: Boolean
        // Auto-approve-all (default on) overrides prompting, so until the user chooses, show this as off instead of
        // contradicting it; the two settings are mutually exclusive.
        get() = preferences.getBoolean("interactive_high_risk_approvals", !autoApproveAllTools)
        set(value) { preferences.edit().putBoolean("interactive_high_risk_approvals", value).apply() }

    /** When true, every tool permission request is approved without classification or prompting. */
    var autoApproveAllTools: Boolean
        get() = preferences.getBoolean("auto_approve_all_tools", true)
        set(value) { preferences.edit().putBoolean("auto_approve_all_tools", value).apply() }

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val key = agentConversationKey(agent, projectId, chatId)
        preferences.edit().apply {
            if (conversationId.isNullOrBlank()) remove(key) else putString(key, conversationId)
        }.commit()
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        preferences.getString(agentConversationKey(agent, projectId, chatId), null)

    fun clearAgentConversations(agent: AgentKind) {
        val stable = agent.stableId
        val keys = preferences.all.keys.filter { key ->
            key.startsWith("agent_conversation_${stable}_") ||
                key.startsWith("agent_conversation_v2_${stable}_")
        }
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun agentConversationKey(agent: AgentKind, projectId: String, chatId: String): String {
        val version = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${version}${agent.stableId}_${projectId}_$chatId"
    }

    /** Pinned dsh version recorded when DeepSeek was installed. */
    var dshVersion: String
        get() = preferences.getString("dsh_version", "") ?: ""
        set(value) { preferences.edit().putString("dsh_version", value).apply() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).apply() }

    /** Glass without blur: solid surfaces, for people who find the frosted look hard to read or whose phone is slow. */
    var glassSolid: Boolean
        get() = preferences.getBoolean("glass_solid", false)
        set(value) { preferences.edit().putBoolean("glass_solid", value).apply() }

    var languageCode: String
        get() = preferences.getString("language_code", "system") ?: "system"
        set(value) { preferences.edit().putString("language_code", value).apply() }

    var legacySeededCredentialRemoved: Boolean
        get() = preferences.getBoolean("legacy_seeded_credential_removed", false)
        set(value) { preferences.edit().putBoolean("legacy_seeded_credential_removed", value).apply() }

    var testProviderDefaultsVersion: Int
        get() = preferences.getInt("test_provider_defaults_version", 0)
        set(value) { preferences.edit().putInt("test_provider_defaults_version", value).apply() }

    var lastAppUpdateCheckMillis: Long
        get() = preferences.getLong("last_app_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_app_update_check_millis", value).apply() }

    var debugUpdateManifestUrl: String
        get() = preferences.getString("debug_update_manifest_url", "") ?: ""
        set(value) { preferences.edit().putString("debug_update_manifest_url", value).apply() }

    /** Development stacks the user picked during onboarding (names of DevStack). */
    var selectedDevStacks: Set<String>
        get() {
            val raw = preferences.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            val arr = JSONArray()
            value.sorted().forEach(arr::put)
            preferences.edit().putString("selected_dev_stacks", arr.toString()).apply()
        }

    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        val editor = preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .putString("provider_dsh_api", profile.dshApi)
            .putString("provider_openrouter_order", profile.openRouterProviderOrder)
            .putBoolean("provider_openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        if (agent != null) {
            val prefix = providerPrefix(agent)
            editor
                .putString("${prefix}kind", profile.kind.name)
                .putString("${prefix}base_url", profile.baseUrl)
                .putString("${prefix}model", profile.model)
                .putString("${prefix}dsh_api", profile.dshApi)
                .putString("${prefix}openrouter_order", profile.openRouterProviderOrder)
                .putBoolean("${prefix}openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        }
        editor.apply()
    }

    /** Saves [profile] as the profile of [agent] only, leaving the generic keys (the selected agent's) untouched. */
    fun saveAgentProfile(profile: ProviderProfile, agent: AgentKind) {
        val prefix = providerPrefix(agent)
        preferences.edit()
            .putString("${prefix}kind", profile.kind.name)
            .putString("${prefix}base_url", profile.baseUrl)
            .putString("${prefix}model", profile.model)
            .putString("${prefix}dsh_api", profile.dshApi)
            .putString("${prefix}openrouter_order", profile.openRouterProviderOrder)
            .putBoolean("${prefix}openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
            .apply()
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val prefix = agent?.let(::providerPrefix)
        val hasAgentProfile = prefix != null && preferences.contains("${prefix}kind")
        val sourcePrefix = if (hasAgentProfile) prefix.orEmpty() else "provider_"
        val storedKind = runCatching {
            ProviderKind.valueOf(preferences.getString("${sourcePrefix}kind", null).orEmpty())
        }.getOrNull()
        val storedBaseUrl = storedKind?.let {
            preferences.getString("${sourcePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl
        }.orEmpty()
        val storedModel = storedKind?.let {
            preferences.getString("${sourcePrefix}model", it.defaultModel) ?: it.defaultModel
        }.orEmpty()
        val legacyClaudeDefaultInDeepSeek = agent == AgentKind.DEEPSEEK_HARNESS &&
            storedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            storedBaseUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            storedModel == ProviderKind.ANTHROPIC.defaultModel
        val kind = when {
            agent == null -> storedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!hasAgentProfile || legacyClaudeDefaultInDeepSeek) -> ProviderKind.DEEPSEEK
            storedKind != null && storedKind in providersForAgent(agent) -> storedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            else -> ProviderKind.ANTHROPIC
        }
        val useStoredValues = storedKind == kind
        val savedModel = if (useStoredValues) {
            preferences.getString("${sourcePrefix}model", kind.defaultModel) ?: kind.defaultModel
        } else {
            kind.defaultModel
        }
        val model = if (
            kind == ProviderKind.DEEPSEEK &&
            savedModel in setOf("deepseek-chat", "deepseek-reasoner")
        ) {
            kind.defaultModel.also {
                preferences.edit().putString("${sourcePrefix}model", it).apply()
            }
        } else {
            savedModel
        }
        return ProviderProfile(
            kind = kind,
            baseUrl = if (useStoredValues) {
                preferences.getString("${sourcePrefix}base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl
            } else {
                kind.defaultBaseUrl
            },
            model = model,
            hasSecret = vault.contains(kind.name),
            dshApi = if (useStoredValues) {
                preferences.getString("${sourcePrefix}dsh_api", defaultDshApiForProvider(kind))
                    ?: defaultDshApiForProvider(kind)
            } else {
                defaultDshApiForProvider(kind)
            },
            openRouterProviderOrder = if (useStoredValues) {
                preferences.getString("${sourcePrefix}openrouter_order", "").orEmpty()
            } else {
                ""
            },
            openRouterAllowFallbacks = if (useStoredValues) {
                preferences.getBoolean("${sourcePrefix}openrouter_allow_fallbacks", true)
            } else {
                true
            },
        )
    }

    private fun providerPrefix(agent: AgentKind): String = "provider_${agent.stableId.replace('-', '_')}_"

    fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("language", p.language)
                put("slug", p.slug)
                put("rootPath", p.rootPath)
                put("updatedAtMillis", p.updatedAtMillis)
                put("kind", p.kind.name)
                put("type", p.type.name)
                p.androidTemplate?.let { put("androidTemplate", it.name) }
            })
        }
        preferences.edit().putString("projects_json", arr.toString()).apply()
    }

    fun loadProjects(): List<Project> {
        val raw = preferences.getString("projects_json", null) ?: return emptyList()
        var needsSave = false
        var dropped = 0
        val list = runCatching {
            val arr = JSONArray(raw)
            val usedSlugs = mutableSetOf<String>()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)
                val id = obj?.optString("id").orEmpty()
                val name = obj?.optString("name").orEmpty()
                if (obj == null || id.isBlank() || name.isBlank()) {
                    dropped++
                    return@mapNotNull null
                }
                val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
                if (!obj.has("kind") || storedKind == "QUICK_CHAT") needsSave = true
                val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
                var slug = requestedSlug
                if (!usedSlugs.add(slug)) {
                    slug = "$requestedSlug-${id.take(6)}"
                    var suffix = 2
                    while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
                }
                if (slug != obj.optString("slug")) needsSave = true
                var millis = obj.optLong("updatedAtMillis", 0L)
                if (millis <= 0L) {
                    val workspaceDir = File(context.filesDir, "workspaces/$id")
                    millis = if (workspaceDir.exists() && workspaceDir.lastModified() > 0L) {
                        workspaceDir.lastModified()
                    } else {
                        System.currentTimeMillis() - 3600_000L
                    }
                    needsSave = true
                }
                val workspaceBase = File(context.filesDir, "workspaces").canonicalFile
                val rawRoot = obj.optString("rootPath", "")
                val validRoot = rawRoot.takeIf { root ->
                    if (root.isBlank()) {
                        true
                    } else if (root.startsWith('/') || root.contains("..") || root.contains('\u0000')) {
                        false
                    } else {
                        runCatching {
                            val candidate = File(workspaceBase, root).canonicalFile
                            candidate.toPath().startsWith(workspaceBase.toPath())
                        }.getOrDefault(false)
                    }
                } ?: ""
                Project(
                    id = id,
                    name = name,
                    description = obj.optString("description", ""),
                    language = obj.optString("language", ""),
                    slug = slug,
                    rootPath = validRoot,
                    updatedAtMillis = millis,
                    kind = when (storedKind) {
                        "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
                        else -> runCatching { ProjectKind.valueOf(storedKind) }
                            .getOrDefault(ProjectKind.PROJECT)
                    },
                    type = runCatching {
                        ProjectType.valueOf(obj.optString("type", ProjectType.GENERAL.name))
                    }.getOrDefault(ProjectType.GENERAL),
                    androidTemplate = obj.optString("androidTemplate").takeIf(String::isNotBlank)?.let {
                        runCatching { AndroidTemplate.valueOf(it) }.getOrNull()
                    },
                )
            }
        }.getOrElse {
            backupCorruptProjects(raw)
            return emptyList()
        }

        // Entries that could not be parsed are dropped on the next save, so keep the original around.
        if (dropped > 0) backupCorruptProjects(raw)
        if (needsSave && list.isNotEmpty()) {
            saveProjects(list)
        }
        return list
    }

    /** Keeps the unparseable projects JSON so a later save cannot silently destroy it. */
    private fun backupCorruptProjects(raw: String) {
        android.util.Log.w("AppPreferences", "projects_json had unreadable entries; keeping a backup")
        preferences.edit().putString("projects_json_corrupt_backup", raw).apply()
    }

    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }

    /** Moves an unreadable file aside so the next save does not overwrite it. */
    private fun quarantine(file: File) {
        android.util.Log.w("AppPreferences", "Quarantining unreadable ${file.name}")
        runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AppPreferences", it) }
    }

    /** Writes via a temp file that is fsynced before the rename, so a power loss cannot leave an empty file. */
    private fun writeAtomically(destination: File, content: String) {
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        java.io.FileOutputStream(temporary).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // rename(2) replaces the destination atomically; File.renameTo only reports false, without the reason.
        val renamed = runCatching { android.system.Os.rename(temporary.absolutePath, destination.absolutePath) }
        if (renamed.isFailure) {
            android.util.Log.w("AppPreferences", "Atomic rename failed for ${destination.name}; falling back to copy", renamed.exceptionOrNull())
            // Last resort: at least make the copy durable before the temp file goes away.
            temporary.inputStream().use { src ->
                java.io.FileOutputStream(destination).use { dst ->
                    src.copyTo(dst)
                    dst.fd.sync()
                }
            }
            temporary.delete()
        }
    }

    private fun safeStorageId(id: String): String {
        val trimmed = id.trim()
        require(
            trimmed.isNotEmpty() &&
                trimmed != "." &&
                trimmed != ".." &&
                !trimmed.contains('/') &&
                !trimmed.contains('\\') &&
                !trimmed.contains("..") &&
                !trimmed.contains('\u0000'),
        ) { "Unsafe storage identifier" }
        return trimmed
    }

    @Synchronized
    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val safeProject = safeStorageId(projectId)
        val projectDir = File(chatsDir, safeProject).also { it.mkdirs() }
        val arr = JSONArray()
        chats.forEach { chat ->
            arr.put(JSONObject().apply {
                put("id", safeStorageId(chat.id))
                put("title", chat.title)
                put("createdAtMillis", chat.createdAtMillis)
                put("updatedAtMillis", chat.updatedAtMillis)
                put("isolated", chat.isolated)
            })
        }
        writeAtomically(File(projectDir, "index.json"), arr.toString())
    }

    @Synchronized
    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val safeProject = safeStorageId(projectId)
        val projectDir = File(chatsDir, safeProject).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        if (index.exists()) {
            val parsed = runCatching {
                val arr = JSONArray(index.readText())
                // One malformed entry must not hide every other chat in the project.
                (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val obj = arr.getJSONObject(i)
                        ProjectChat(
                            id = safeStorageId(obj.getString("id")),
                            title = obj.optString("title", "Chat"),
                            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                            updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                            isolated = obj.optBoolean("isolated", false),
                        )
                    }.getOrNull()
                }.sortedByDescending { it.updatedAtMillis }
            }.getOrNull()
            if (parsed != null) return parsed
            // Unreadable index: keep it for inspection, then rebuild from the chat files that are still on disk.
            quarantine(index)
            val recovered = recoverChatIndex(safeProject, projectDir)
            if (recovered.isNotEmpty()) return recovered
        }

        val legacy = File(chatsDir, "$safeProject.json")
        val legacyMessages = loadLegacyMessages(legacy)
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveProjectChats(safeProject, listOf(chat))
        if (legacyMessages.isNotEmpty()) saveMessages(safeProject, chat.id, legacyMessages)
        return listOf(chat)
    }

    /** Rebuilds the chat list from `<chatId>.json` files when `index.json` is lost or corrupt. */
    private fun recoverChatIndex(projectId: String, projectDir: File): List<ProjectChat> {
        val recovered = projectDir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && f.name != "index.json" && !f.name.startsWith(".")
        }.orEmpty().mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            if (runCatching { safeStorageId(id) }.isFailure) return@mapNotNull null
            val modified = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            ProjectChat(
                id = id,
                title = loadLegacyMessages(file).firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Recovered chat",
                createdAtMillis = modified,
                updatedAtMillis = modified,
            )
        }.sortedByDescending { it.updatedAtMillis }
        if (recovered.isNotEmpty()) saveProjectChats(projectId, recovered)
        return recovered
    }

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        val safeProject = safeStorageId(projectId)
        val safeChat = safeStorageId(chatId)
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("attachments", JSONArray().apply {
                    m.attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("displayName", attachment.displayName)
                            put("relativePath", attachment.relativePath)
                            put("mimeType", attachment.mimeType)
                            put("sizeBytes", attachment.sizeBytes)
                        })
                    }
                })
                put("workedMillis", m.workedMillis)
                m.agent?.let { put("agent", it) }
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
            })
        }
        val projectDir = File(chatsDir, safeProject).also { it.mkdirs() }
        writeAtomically(File(projectDir, "$safeChat.json"), arr.toString())
    }

    @Synchronized
    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val safeProject = safeStorageId(projectId)
        val safeChat = safeStorageId(chatId)
        val file = File(File(chatsDir, safeProject), "$safeChat.json")
        return loadLegacyMessages(file)
    }

    @Synchronized
    fun deleteProjectChats(projectId: String) {
        val safeProject = safeStorageId(projectId)
        File(chatsDir, safeProject).deleteRecursively()
        File(chatsDir, "$safeProject.json").delete()
    }

    private fun loadLegacyMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseMessage(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse {
            quarantine(file)
            emptyList()
        }
    }

    private fun parseMessage(obj: JSONObject): ChatMessage {
        return ChatMessage(
            id = obj.getString("id"),
            fromUser = obj.getBoolean("fromUser"),
            text = obj.getString("text"),
            createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
                .getOrDefault(Instant.now()),
            attachments = obj.optJSONArray("attachments")?.let { attachments ->
                (0 until attachments.length()).mapNotNull { index ->
                    runCatching {
                        attachments.getJSONObject(index).let { attachment ->
                            ChatAttachment(
                                id = attachment.optString("id").ifBlank { UUID.randomUUID().toString() },
                                displayName = attachment.getString("displayName"),
                                relativePath = attachment.getString("relativePath"),
                                mimeType = attachment.optString("mimeType", "application/octet-stream"),
                                sizeBytes = attachment.optLong("sizeBytes", 0L),
                            )
                        }
                    }.getOrNull()
                }
            }.orEmpty(),
            workedMillis = obj.optLong("workedMillis", 0L),
            agent = obj.optString("agent", "").ifBlank { null },
            workItems = obj.optJSONArray("workItems")?.let { workItems ->
                (0 until workItems.length()).mapNotNull { index ->
                    runCatching {
                        workItems.getJSONObject(index).let { item ->
                            ActivityItem(
                                title = item.optString("title"),
                                detail = item.optString("detail"),
                                isComplete = item.optBoolean("isComplete", true),
                                isCommand = item.optBoolean("isCommand", false),
                            )
                        }
                    }.getOrNull()
                }
            }.orEmpty(),
        )
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}
