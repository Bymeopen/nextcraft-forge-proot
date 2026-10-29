package com.nextcraft.forge.data

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.util.Log
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.PromptRule
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RouteMode
import com.nextcraft.forge.model.RoutingConfig
import com.nextcraft.forge.model.defaultDshApiForProvider
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.ErrorLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class AppPreferences(private val context: Context) {
    private val store: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
            store.getBoolean(key, default)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            store.edit {putBoolean(key, value)}
        }
    }

    private fun strPref(key: String, default: String = "") = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            store.getString(key, default) ?: default

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            store.edit {putString(key, value)}
        }
    }

    private fun intPref(key: String, default: Int = 0) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int =
            store.getInt(key, default)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            store.edit {putInt(key, value)}
        }
    }

    private fun longPref(key: String, default: Long = 0L) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long =
            store.getLong(key, default)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
            store.edit {putLong(key, value)}
        }
    }

    var onboardingComplete: Boolean by boolPref("onboarding_complete", false)
    var runtimeSetupComplete: Boolean by boolPref("runtime_setup_complete", false)
    var backgroundPromptsShown: Boolean by boolPref("background_prompts_shown", false)
    var backgroundSetupComplete: Boolean by boolPref("background_setup_complete", false)

    var initialLanguageSelected: Boolean
        get() = store.getBoolean("initial_language_selected", onboardingComplete || runtimeSetupComplete)
        set(value) {
            store.edit {putBoolean("initial_language_selected", value)}
        }

    var runtimeEngine: String by strPref("runtime_engine", "auto")
    var agentKind: String by strPref("agent_kind", AgentKind.CLAUDE_CODE.stableId)

    var routing: RoutingConfig
        get() = decodeRoutingConfig()
        set(value) = encodeRoutingConfig(value)

    var routeCounter: Int by intPref("routing_counter", 0)
    var primaryAgentKind: String by strPref("primary_agent_kind", "")

    var antigravityModel: String
        get() = store.getString("agent_antigravity_model", DEFAULT_AGY_MODEL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_AGY_MODEL
        set(value) {
            store.edit {putString("agent_antigravity_model", value)}
        }

    var claudeAccountEmail: String by strPref("claude_account_email", "")
    var claudeEffort: String by strPref("agent_claude_effort", "")
    var antigravityEffort: String by strPref("agent_antigravity_effort", "low")
    var antigravitySignedIn: Boolean by boolPref("agent_antigravity_signed_in", false)
    var antigravityAccountEmail: String by strPref("agent_antigravity_account_email", "")
    var githubLogin: String by strPref("github_login", "")
    var autoKeepChanges: Boolean by boolPref("auto_keep_changes", true)
    var androidHostTools: Boolean by boolPref("android_host_tools", false)
    var gameTemplatesEnabled: Boolean by boolPref("game_templates_enabled", false)
    var autoSyncGitHub: Boolean by boolPref("auto_sync_github", true)
    var updateNotifications: Boolean by boolPref("update_notifications", true)
    var autoInstallUpdates: Boolean by boolPref("auto_install_updates", true)
    var lastNotifiedUpdateCode: Long by longPref("last_notified_update_code", 0L)
    var scheduleEnabled: Boolean by boolPref("schedule_enabled", true)
    var reaChatHint: Boolean by boolPref("rea_chat_hint", false)

    var pendingProjectWipe: String?
        get() = store.getString("pending_project_wipe", null)?.takeIf { it.isNotBlank() }
        set(value) {
            store.edit {
                if (value == null) remove("pending_project_wipe") else putString("pending_project_wipe", value)
            }
        }

    var autoBranchChats: Boolean by boolPref("auto_branch_chats", false)
    var autoApproveAllTools: Boolean by boolPref("auto_approve_all_tools", true)

    var interactiveHighRiskApprovals: Boolean
        get() = store.getBoolean("interactive_high_risk_approvals", !autoApproveAllTools)
        set(value) {
            store.edit {putBoolean("interactive_high_risk_approvals", value)}
        }

    var autoVerifyBuild: Boolean by boolPref("auto_verify_build", false)
    var autoFixBuild: Boolean by boolPref("auto_fix_build", false)
    var dshVersion: String by strPref("dsh_version", "")
    var themeMode: String by strPref("theme_mode", "dark")
    var glassSolid: Boolean by boolPref("glass_solid", false)
    var languageCode: String by strPref("language_code", "system")
    var legacySeededCredentialRemoved: Boolean by boolPref("legacy_seeded_credential_removed", false)
    var testProviderDefaultsVersion: Int by intPref("test_provider_defaults_version", 0)
    var lastAppUpdateCheckMillis: Long by longPref("last_app_update_check_millis", 0L)
    var debugUpdateManifestUrl: String by strPref("debug_update_manifest_url", "")
    var latestReleaseNotes: String by strPref("latest_release_notes", "")

    var selectedDevStacks: Set<String>
        get() {
            val serialized = store.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val array = JSONArray(serialized)
                buildSet {
                    for (idx in 0 until array.length()) {
                        val token = array.optString(idx).trim()
                        if (token.isNotEmpty()) add(token)
                    }
                }
            }.getOrDefault(emptySet())
        }
        set(value) {
            val array = JSONArray()
            for (item in value.sorted()) {
                array.put(item)
            }
            store.edit {putString("selected_dev_stacks", array.toString())}
        }

    /** Routing mode, agent list, weights and rules of one chat, or null when that chat never saved its own. */
    fun loadChatRouting(projectId: String, chatId: String): RoutingConfig? {
        val scope = chatScope(projectId, chatId)
        return if (store.contains("routing_mode$scope")) decodeRoutingConfig(scope) else null
    }

    fun saveChatRouting(projectId: String, chatId: String, cfg: RoutingConfig) =
        encodeRoutingConfig(cfg, chatScope(projectId, chatId))

    /** The agent a chat answers with in Single mode and for untagged Manual messages, or null when never set. */
    fun loadChatAgent(projectId: String, chatId: String): AgentKind? =
        AgentKind.fromStoredOrNull(store.getString("chat_agent${chatScope(projectId, chatId)}", null))

    fun saveChatAgent(projectId: String, chatId: String, agent: AgentKind) {
        store.edit {putString("chat_agent${chatScope(projectId, chatId)}", agent.stableId)}
    }

    private fun chatScope(projectId: String, chatId: String) = "_chat_${projectId}_$chatId"

    private fun decodeRoutingConfig(scope: String = ""): RoutingConfig {
        val parsedAgents = store.getString("routing_agents$scope", null)
            ?.splitToSequence(',')
            ?.mapNotNull { AgentKind.fromStoredOrNull(it) }
            ?.distinct()
            ?.toList()
            .orEmpty()

        val parsedWeights = store.getString("routing_weights$scope", null)
            ?.splitToSequence(',')
            ?.mapNotNull { entry ->
                val delimIdx = entry.indexOf(':')
                if (delimIdx <= 0) return@mapNotNull null
                val kind = AgentKind.fromStoredOrNull(entry.substring(0, delimIdx)) ?: return@mapNotNull null
                val weightVal = entry.substring(delimIdx + 1).toIntOrNull() ?: return@mapNotNull null
                kind to weightVal
            }
            ?.toMap()
            .orEmpty()

        val parsedRules = runCatching {
            val rawRules = store.getString("routing_rules$scope", "[]") ?: "[]"
            val rulesJson = JSONArray(rawRules)
            buildList {
                for (i in 0 until rulesJson.length()) {
                    val node = rulesJson.optJSONObject(i) ?: continue
                    val targetAgent = AgentKind.fromStoredOrNull(node.optString("agent")) ?: continue
                    val kwArray = node.optJSONArray("keywords") ?: continue
                    val kwList = buildList {
                        for (k in 0 until kwArray.length()) {
                            val word = kwArray.optString(k)
                            if (word.isNotEmpty()) add(word)
                        }
                    }
                    if (kwList.isNotEmpty()) add(PromptRule(kwList, targetAgent))
                }
            }
        }.getOrDefault(emptyList())

        return RoutingConfig(
            mode = RouteMode.fromStored(store.getString("routing_mode$scope", null)),
            agents = parsedAgents.ifEmpty { AgentKind.entries },
            weights = parsedWeights,
            rules = parsedRules,
        )
    }

    private fun encodeRoutingConfig(cfg: RoutingConfig, scope: String = "") {
        val rulesArray = JSONArray().apply {
            for (rule in cfg.rules) {
                put(
                    JSONObject()
                        .put("agent", rule.agent.stableId)
                        .put("keywords", JSONArray(rule.keywords)),
                )
            }
        }
        val agentCsv = cfg.agents.joinToString(",") { it.stableId }
        val weightCsv = cfg.weights.entries.joinToString(",") { "${it.key.stableId}:${it.value}" }
        store.edit {
                putString("routing_mode$scope", cfg.mode.stableId)
                .putString("routing_agents$scope", agentCsv)
                .putString("routing_rules$scope", rulesArray.toString())
                .putString("routing_weights$scope", weightCsv)
            }
    }

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val prefKey = buildConversationPrefKey(agent, projectId, chatId)
        store.edit(commit = true) {
            if (conversationId.isNullOrBlank()) remove(prefKey) else putString(prefKey, conversationId)
        }
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        store.getString(buildConversationPrefKey(agent, projectId, chatId), null)

    fun clearAgentConversations(agent: AgentKind) {
        val idToken = agent.stableId
        val prefixV1 = "agent_conversation_${idToken}_"
        val prefixV2 = "agent_conversation_v2_${idToken}_"
        val matchingKeys = store.all.keys.filter { it.startsWith(prefixV1) || it.startsWith(prefixV2) }
        if (matchingKeys.isEmpty()) return
        store.edit {
            for (k in matchingKeys) remove(k)
        }
    }

    private fun buildConversationPrefKey(agent: AgentKind, projectId: String, chatId: String): String {
        val vTag = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${vTag}${agent.stableId}_${projectId}_$chatId"
    }

    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        store.edit {
            writeProfileFields(this, "provider_", profile)
            if (agent != null) {
                writeProfileFields(this, agentKeyPrefix(agent), profile)
            }
        }
    }

    fun saveAgentProfile(profile: ProviderProfile, agent: AgentKind) {
        store.edit {
            writeProfileFields(this, agentKeyPrefix(agent), profile)
        }
    }

    private fun writeProfileFields(
        editor: SharedPreferences.Editor,
        prefix: String,
        profile: ProviderProfile,
    ) {
        editor.putString("${prefix}kind", profile.kind.name)
            .putString("${prefix}base_url", profile.baseUrl)
            .putString("${prefix}model", profile.model)
            .putString("${prefix}dsh_api", profile.dshApi)
            .putString("${prefix}openrouter_order", profile.openRouterProviderOrder)
            .putBoolean("${prefix}openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val scopedPrefix = agent?.let(::agentKeyPrefix)
        val scopedExists = scopedPrefix != null && store.contains("${scopedPrefix}kind")
        val activePrefix = if (scopedExists) scopedPrefix.orEmpty() else "provider_"

        val rawKindName = store.getString("${activePrefix}kind", null).orEmpty()
        val parsedKind = runCatching { ProviderKind.valueOf(rawKindName) }.getOrNull()
        val rawUrl = parsedKind?.let { store.getString("${activePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl }.orEmpty()
        val rawModel = parsedKind?.let { store.getString("${activePrefix}model", it.defaultModel) ?: it.defaultModel }.orEmpty()

        val isUnconfiguredAnthropicOnDsh = agent == AgentKind.DEEPSEEK_HARNESS &&
            parsedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            rawUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            rawModel == ProviderKind.ANTHROPIC.defaultModel

        val resolvedKind = when {
            agent == null -> parsedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!scopedExists || isUnconfiguredAnthropicOnDsh) -> ProviderKind.DEEPSEEK
            parsedKind != null && parsedKind in providersForAgent(agent) -> parsedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            else -> ProviderKind.ANTHROPIC
        }

        val keepSavedFields = parsedKind == resolvedKind
        val candidateModel = if (keepSavedFields) {
            store.getString("${activePrefix}model", resolvedKind.defaultModel) ?: resolvedKind.defaultModel
        } else {
            resolvedKind.defaultModel
        }

        val finalModel = if (resolvedKind == ProviderKind.DEEPSEEK && candidateModel in LEGACY_DEEPSEEK_MODELS) {
            resolvedKind.defaultModel.also { migrated ->
                store.edit {putString("${activePrefix}model", migrated)}
            }
        } else {
            candidateModel
        }

        return ProviderProfile(
            kind = resolvedKind,
            baseUrl = if (keepSavedFields) {
                store.getString("${activePrefix}base_url", resolvedKind.defaultBaseUrl) ?: resolvedKind.defaultBaseUrl
            } else {
                resolvedKind.defaultBaseUrl
            },
            model = finalModel,
            hasSecret = vault.contains(resolvedKind.name),
            dshApi = if (keepSavedFields) {
                store.getString("${activePrefix}dsh_api", defaultDshApiForProvider(resolvedKind))
                    ?: defaultDshApiForProvider(resolvedKind)
            } else {
                defaultDshApiForProvider(resolvedKind)
            },
            openRouterProviderOrder = if (keepSavedFields) {
                store.getString("${activePrefix}openrouter_order", "").orEmpty()
            } else {
                ""
            },
            openRouterAllowFallbacks = if (keepSavedFields) {
                store.getBoolean("${activePrefix}openrouter_allow_fallbacks", true)
            } else {
                true
            },
        )
    }

    private fun agentKeyPrefix(agent: AgentKind): String =
        "provider_${agent.stableId.replace('-', '_')}_"

    fun saveProjects(projects: List<Project>) {
        val jsonList = JSONArray()
        for (item in projects) {
            val node = JSONObject()
                .put("id", item.id)
                .put("name", item.name)
                .put("description", item.description)
                .put("language", item.language)
                .put("slug", item.slug)
                .put("rootPath", item.rootPath)
                .put("updatedAtMillis", item.updatedAtMillis)
                .put("kind", item.kind.name)
                .put("type", item.type.name)
            if (item.androidTemplate != null) {
                node.put("androidTemplate", item.androidTemplate.name)
            }
            jsonList.put(node)
        }
        store.edit {putString("projects_json", jsonList.toString())}
    }

    fun loadProjects(): List<Project> {
        val serialized = store.getString("projects_json", null) ?: return emptyList()
        var dirty = false
        var skippedCount = 0

        val loaded = runCatching {
            val jsonList = JSONArray(serialized)
            val seenSlugs = HashSet<String>(jsonList.length())
            val workspacesRoot = File(context.filesDir, "workspaces").canonicalFile

            buildList(jsonList.length()) {
                for (idx in 0 until jsonList.length()) {
                    val node = jsonList.optJSONObject(idx)
                    val projId = node?.optString("id").orEmpty()
                    val projName = node?.optString("name").orEmpty()
                    if (node == null || projId.isBlank() || projName.isBlank()) {
                        skippedCount++
                        continue
                    }

                    val rawKind = node.optString("kind", ProjectKind.PROJECT.name)
                    if (!node.has("kind") || rawKind == "QUICK_CHAT") dirty = true

                    val baseSlug = node.optString("slug").ifBlank { projectSlug(projName) }
                    var uniqueSlug = baseSlug
                    if (!seenSlugs.add(uniqueSlug)) {
                        uniqueSlug = "$baseSlug-${projId.take(6)}"
                        var counter = 2
                        while (!seenSlugs.add(uniqueSlug)) {
                            uniqueSlug = "$baseSlug-${counter++}"
                        }
                    }
                    if (uniqueSlug != node.optString("slug")) dirty = true

                    var tsMillis = node.optLong("updatedAtMillis", 0L)
                    if (tsMillis <= 0L) {
                        val folder = File(context.filesDir, "workspaces/$projId")
                        tsMillis = if (folder.exists() && folder.lastModified() > 0L) {
                            folder.lastModified()
                        } else {
                            System.currentTimeMillis() - 3_600_000L
                        }
                        dirty = true
                    }

                    val sanitizedRoot = validateWorkspaceSubpath(workspacesRoot, node.optString("rootPath", ""))
                    val resolvedKind = if (rawKind == "QUICK_CHAT") {
                        ProjectKind.QUICK_PROJECT
                    } else {
                        runCatching { ProjectKind.valueOf(rawKind) }.getOrDefault(ProjectKind.PROJECT)
                    }
                    val resolvedType = runCatching {
                        ProjectType.valueOf(node.optString("type", ProjectType.GENERAL.name))
                    }.getOrDefault(ProjectType.GENERAL)
                    val resolvedTemplate = node.optString("androidTemplate")
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { AndroidTemplate.valueOf(it) }.getOrNull() }

                    add(
                        Project(
                            id = projId,
                            name = projName,
                            description = node.optString("description", ""),
                            language = node.optString("language", ""),
                            slug = uniqueSlug,
                            rootPath = sanitizedRoot,
                            updatedAtMillis = tsMillis,
                            kind = resolvedKind,
                            type = resolvedType,
                            androidTemplate = resolvedTemplate,
                        ),
                    )
                }
            }
        }.getOrElse {
            preserveCorruptProjectsJson(serialized)
            return emptyList()
        }

        if (skippedCount > 0) preserveCorruptProjectsJson(serialized)
        if (dirty && loaded.isNotEmpty()) saveProjects(loaded)
        return loaded
    }

    private fun validateWorkspaceSubpath(workspacesRoot: File, candidatePath: String): String {
        if (candidatePath.isBlank()) return ""
        if (candidatePath.startsWith('/') || candidatePath.contains("..") || candidatePath.contains('\u0000')) {
            return ""
        }
        val insideWorkspace = runCatching {
            File(workspacesRoot, candidatePath).canonicalFile.toPath().startsWith(workspacesRoot.toPath())
        }.getOrDefault(false)
        return if (insideWorkspace) candidatePath else ""
    }

    private fun preserveCorruptProjectsJson(rawPayload: String) {
        Log.w(LOG_TAG, "projects_json had unreadable entries; keeping a backup")
        store.edit {putString("projects_json_corrupt_backup", rawPayload)}
    }

    private val chatStorageRoot: File = File(context.filesDir, "chats").apply { mkdirs() }

    private fun moveCorruptFileAside(target: File) {
        Log.w(LOG_TAG, "Quarantining unreadable ${target.name}")
        val quarantineDest = File(target.parentFile, "${target.name}.corrupt-${System.currentTimeMillis()}")
        runCatching { target.renameTo(quarantineDest) }
            .onFailure { ErrorLog.swallowed(LOG_TAG, it) }
    }

    // AppPreferences is instantiated all over the app, so the per-instance @Synchronized of the callers does not stop two
    // instances from sharing one ".name.tmp" staging file; the lock here is process-wide.
    private fun writeDurableFile(target: File, payload: String): Unit = synchronized(FILE_WRITE_LOCK) {
        val stagingFile = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(stagingFile).use { stream ->
            stream.write(payload.encodeToByteArray())
            stream.fd.sync()
        }
        val osRenameResult = runCatching { Os.rename(stagingFile.absolutePath, target.absolutePath) }
        if (osRenameResult.isFailure) {
            Log.w(LOG_TAG, "Atomic rename failed for ${target.name}; falling back to copy", osRenameResult.exceptionOrNull())
            stagingFile.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            stagingFile.delete()
        }
    }

    private fun requireSafeFileToken(token: String): String {
        val clean = token.trim()
        val isValid = clean.isNotEmpty() &&
            clean != "." &&
            clean != ".." &&
            '/' !in clean &&
            '\\' !in clean &&
            !clean.contains("..") &&
            '\u0000' !in clean
        require(isValid) { "Unsafe storage identifier" }
        return clean
    }

    @Synchronized
    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val projectToken = requireSafeFileToken(projectId)
        val folder = File(chatStorageRoot, projectToken).apply { mkdirs() }
        val jsonList = JSONArray()
        for (chat in chats) {
            jsonList.put(
                JSONObject()
                    .put("id", requireSafeFileToken(chat.id))
                    .put("title", chat.title)
                    .put("createdAtMillis", chat.createdAtMillis)
                    .put("updatedAtMillis", chat.updatedAtMillis)
                    .put("isolated", chat.isolated),
            )
        }
        writeDurableFile(File(folder, "index.json"), jsonList.toString())
    }

    @Synchronized
    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val projectToken = requireSafeFileToken(projectId)
        val folder = File(chatStorageRoot, projectToken).apply { mkdirs() }
        val indexFile = File(folder, "index.json")

        if (indexFile.exists()) {
            val decoded = runCatching {
                val array = JSONArray(indexFile.readText())
                buildList(array.length()) {
                    for (idx in 0 until array.length()) {
                        val parsed = runCatching {
                            val item = array.getJSONObject(idx)
                            ProjectChat(
                                id = requireSafeFileToken(item.getString("id")),
                                title = item.optString("title", "Chat"),
                                createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
                                updatedAtMillis = item.optLong("updatedAtMillis", System.currentTimeMillis()),
                                isolated = item.optBoolean("isolated", false),
                            )
                        }.getOrNull()
                        if (parsed != null) add(parsed)
                    }
                }.sortedByDescending { it.updatedAtMillis }
            }.getOrNull()

            if (decoded != null) return decoded
            moveCorruptFileAside(indexFile)
            val rebuilt = rebuildIndexFromTranscripts(projectToken, folder)
            if (rebuilt.isNotEmpty()) return rebuilt
        }

        val legacyTranscript = File(chatStorageRoot, "$projectToken.json")
        val importedMessages = readTranscriptFile(legacyTranscript)
        val timestamp = System.currentTimeMillis()
        val initialTitle = importedMessages.firstOrNull { it.fromUser }?.text?.let(::summarizeAsTitle) ?: "Main chat"
        val defaultChat = ProjectChat(
            id = "main",
            title = initialTitle,
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp,
        )
        saveProjectChats(projectToken, listOf(defaultChat))
        if (importedMessages.isNotEmpty()) {
            saveMessages(projectToken, defaultChat.id, importedMessages)
        }
        return listOf(defaultChat)
    }

    private fun rebuildIndexFromTranscripts(projectToken: String, folder: File): List<ProjectChat> {
        val candidates = folder.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && f.name != "index.json" && !f.name.startsWith(".")
        }.orEmpty()

        val recoveredChats = candidates.mapNotNull { transcriptFile ->
            val chatId = transcriptFile.name.removeSuffix(".json")
            if (runCatching { requireSafeFileToken(chatId) }.isFailure) return@mapNotNull null
            val mtime = transcriptFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val firstPrompt = readTranscriptFile(transcriptFile).firstOrNull { it.fromUser }?.text
            ProjectChat(
                id = chatId,
                title = firstPrompt?.let(::summarizeAsTitle) ?: "Recovered chat",
                createdAtMillis = mtime,
                updatedAtMillis = mtime,
            )
        }.sortedByDescending { it.updatedAtMillis }

        if (recoveredChats.isNotEmpty()) {
            saveProjectChats(projectToken, recoveredChats)
        }
        return recoveredChats
    }

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        val projectToken = requireSafeFileToken(projectId)
        val chatToken = requireSafeFileToken(chatId)
        val serializedList = JSONArray()

        for (msg in messages) {
            val attArray = JSONArray().apply {
                for (att in msg.attachments) {
                    put(
                        JSONObject()
                            .put("id", att.id)
                            .put("displayName", att.displayName)
                            .put("relativePath", att.relativePath)
                            .put("mimeType", att.mimeType)
                            .put("sizeBytes", att.sizeBytes),
                    )
                }
            }
            val workArray = JSONArray().apply {
                for (work in msg.workItems) {
                    put(
                        JSONObject()
                            .put("title", work.title)
                            .put("detail", work.detail)
                            .put("isComplete", work.isComplete)
                            .put("isCommand", work.isCommand),
                    )
                }
            }
            val msgNode = JSONObject()
                .put("id", msg.id)
                .put("fromUser", msg.fromUser)
                .put("text", msg.text)
                .put("createdAt", msg.createdAt.toString())
                .put("attachments", attArray)
                .put("workedMillis", msg.workedMillis)
                .put("workItems", workArray)
            if (msg.agent != null) {
                msgNode.put("agent", msg.agent)
            }
            serializedList.put(msgNode)
        }

        val folder = File(chatStorageRoot, projectToken).apply { mkdirs() }
        writeDurableFile(File(folder, "$chatToken.json"), serializedList.toString())
    }

    @Synchronized
    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val projectToken = requireSafeFileToken(projectId)
        val chatToken = requireSafeFileToken(chatId)
        val transcriptFile = File(File(chatStorageRoot, projectToken), "$chatToken.json")
        return readTranscriptFile(transcriptFile)
    }

    @Synchronized
    fun deleteProjectChats(projectId: String) {
        val projectToken = requireSafeFileToken(projectId)
        File(chatStorageRoot, projectToken).deleteRecursively()
        File(chatStorageRoot, "$projectToken.json").delete()
    }

    private fun readTranscriptFile(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val jsonList = JSONArray(file.readText())
            buildList(jsonList.length()) {
                for (idx in 0 until jsonList.length()) {
                    val decoded = runCatching { decodeMessageNode(jsonList.getJSONObject(idx)) }.getOrNull()
                    if (decoded != null) add(decoded)
                }
            }
        }.getOrElse {
            moveCorruptFileAside(file)
            emptyList()
        }
    }

    private fun decodeMessageNode(node: JSONObject): ChatMessage {
        val parsedAttachments = node.optJSONArray("attachments")?.let { arr ->
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val parsed = runCatching {
                        val att = arr.getJSONObject(i)
                        ChatAttachment(
                            id = att.optString("id").ifBlank { UUID.randomUUID().toString() },
                            displayName = att.getString("displayName"),
                            relativePath = att.getString("relativePath"),
                            mimeType = att.optString("mimeType", "application/octet-stream"),
                            sizeBytes = att.optLong("sizeBytes", 0L),
                        )
                    }.getOrNull()
                    if (parsed != null) add(parsed)
                }
            }
        }.orEmpty()

        val parsedWork = node.optJSONArray("workItems")?.let { arr ->
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val parsed = runCatching {
                        val work = arr.getJSONObject(i)
                        ActivityItem(
                            title = work.optString("title"),
                            detail = work.optString("detail"),
                            isComplete = work.optBoolean("isComplete", true),
                            isCommand = work.optBoolean("isCommand", false),
                        )
                    }.getOrNull()
                    if (parsed != null) add(parsed)
                }
            }
        }.orEmpty()

        return ChatMessage(
            id = node.getString("id"),
            fromUser = node.getBoolean("fromUser"),
            text = node.getString("text"),
            createdAt = runCatching { Instant.parse(node.getString("createdAt")) }.getOrDefault(Instant.now()),
            attachments = parsedAttachments,
            workedMillis = node.optLong("workedMillis", 0L),
            agent = node.optString("agent", "").ifBlank { null },
            workItems = parsedWork,
        )
    }

    private fun summarizeAsTitle(rawText: String): String {
        val collapsed = rawText.replace(WHITESPACE_COLLAPSE_REGEX, " ").trim()
        return if (collapsed.length <= 42) collapsed else "${collapsed.take(39).trimEnd()}…"
    }

    private companion object {
        const val LOG_TAG = "AppPreferences"
        val FILE_WRITE_LOCK = Any()
        const val PREFS_FILE_NAME = "nextcraft_preferences"
        const val DEFAULT_AGY_MODEL = "claude-sonnet-5-5-low"
        val LEGACY_DEEPSEEK_MODELS = setOf("deepseek-chat", "deepseek-reasoner")
        val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")
    }
}
