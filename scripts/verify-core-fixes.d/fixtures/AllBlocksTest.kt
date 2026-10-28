package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.network.LocalNetwork
import com.nextcraft.forge.network.ModelResponseParser
import com.nextcraft.forge.network.ProviderApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

fun main() {
    val tmp = Files.createTempDirectory("forge-all-blocks").toFile()
    try {
        // 1. Test BUG-020: WorkspaceCheckpoints.safeWorkspaceFile allows legitimate "foo..bar.txt"
        //    but blocks "../" traversal and blocks symlinked intermediate directories!
        val wsRoot = File(tmp, "wsroot").apply { mkdirs() }
        val outsideDir = File(tmp, "outside").apply { mkdirs() }
        File(outsideDir, "secret.txt").writeText("TOP_SECRET")

        val cp = WorkspaceCheckpoints(tmp)
        val validDoubleDot = cp.safeWorkspaceFile(wsRoot, "src/v1..v2.diff")
        check(validDoubleDot.name == "v1..v2.diff") { "Expected v1..v2.diff to be allowed" }

        val blockedTraversal = runCatching { cp.safeWorkspaceFile(wsRoot, "src/../../outside/secret.txt") }.isFailure
        check(blockedTraversal) { "Expected traversal path to be rejected" }

        val symlinkSub = File(wsRoot, "linked_sub")
        Files.createSymbolicLink(symlinkSub.toPath(), outsideDir.toPath())
        val blockedSymlinkAncestor = runCatching { cp.safeWorkspaceFile(wsRoot, "linked_sub/newfile.txt") }.isFailure
        check(blockedSymlinkAncestor) { "Expected file inside symlinked ancestor directory to be rejected" }
        symlinkSub.delete()

        // 2. Test BUG-006 + BUG-020 end-to-end via WorkspaceCheckpoints.createCheckpoint & buildChangeDetails
        val codeFile = File(wsRoot, "App.kt")
        codeFile.writeText("fun main() = println(1)\n")
        val beforeSnap = cp.snapshot(wsRoot)
        cp.createCheckpoint("proj-1", wsRoot)
        java.io.RandomAccessFile(codeFile, "rw").use { raf ->
            raf.setLength(0)
            raf.write("fun main() = println(999)\n".toByteArray())
        }
        val changed = cp.changedFiles(wsRoot, beforeSnap)
        check(changed == listOf("App.kt")) { "Expected App.kt in changedFiles, got $changed" }
        cp.saveChangedPaths("proj-1", changed)
        check(cp.readChangedPaths("proj-1") == listOf("App.kt")) { "Expected App.kt in readChangedPaths" }
        val details = cp.buildChangeDetails("proj-1", wsRoot, changed)
        check(details.size == 1 && details[0].additions == 1 && details[0].deletions == 1) {
            "Expected 1 addition and 1 deletion when baseline is isolated from O_TRUNC, got ${details[0]}"
        }

        // 3. Test BUG-021: readTailText and InotifyLogTailer bounded line buffer (no OOM on >512KB line)
        val logFile = File(tmp, "stream.log")
        logFile.writeText("hello\nworld\n")
        check(logFile.readTailText(6) == "world\n") { "Unexpected readTailText output" }

        val bigLineFile = File(tmp, "bigline.log")
        val chunk = "A".repeat(600 * 1024) + "\n"
        bigLineFile.writeText(chunk)
        InotifyLogTailer(bigLineFile).use { tailer ->
            val lines = mutableListOf<String>()
            tailer.drainLines(timeoutMs = 0L, flushIncomplete = true) { lines += it }
            check(lines.size == 1) { "Expected 1 drained line, got ${lines.size}" }
            check(lines[0].endsWith("... [truncated]")) { "Expected oversized line to be capped and marked truncated" }
            check(lines[0].length <= 512 * 1024 + 32) { "Oversized line was not bounded" }
        }

        // 4. Test BUG-022: RuntimeLaunchConfigBuilder uses profile.resolvedBaseUrl even when baseUrl is empty
        val customAnthropic = ProviderProfile(
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "",
            model = "claude-sonnet-4-6",
        )
        val launchCfg = RuntimeLaunchConfigBuilder.build(customAnthropic, authToken = "sk-ant-api03-test")
        check(launchCfg.environment["ANTHROPIC_BASE_URL"] == "https://api.anthropic.com") {
            "Expected resolvedBaseUrl fallback for ANTHROPIC_BASE_URL, got '${launchCfg.environment["ANTHROPIC_BASE_URL"]}'"
        }
        check(launchCfg.environment["ANTHROPIC_API_KEY"] == "sk-ant-api03-test") {
            "Expected ANTHROPIC_API_KEY to be populated for Anthropic API key"
        }
        check(launchCfg.environment["ANTHROPIC_AUTH_TOKEN"] == "") {
            "Expected ANTHROPIC_AUTH_TOKEN to be empty for Anthropic API key so Claude CLI does not send invalid Bearer header"
        }
        check(launchCfg.environment["CLAUDE_CODE_OAUTH_TOKEN"] == "") {
            "Expected CLAUDE_CODE_OAUTH_TOKEN to be empty for Anthropic API key"
        }

        val oauthUnderAnthropicCfg = RuntimeLaunchConfigBuilder.build(customAnthropic, authToken = "sk-ant-oat01-oauth-token")
        check(oauthUnderAnthropicCfg.environment["CLAUDE_CODE_OAUTH_TOKEN"] == "sk-ant-oat01-oauth-token") {
            "Expected sk-ant-oat* token under ANTHROPIC profile to route to CLAUDE_CODE_OAUTH_TOKEN"
        }
        check(oauthUnderAnthropicCfg.environment["ANTHROPIC_API_KEY"] == "") {
            "Expected ANTHROPIC_API_KEY to be cleared for sk-ant-oat* OAuth token"
        }
        check(oauthUnderAnthropicCfg.environment["ANTHROPIC_AUTH_TOKEN"] == "") {
            "Expected ANTHROPIC_AUTH_TOKEN to be cleared for sk-ant-oat* OAuth token"
        }

        val apiKeyUnderClaudeLoginCfg = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(kind = ProviderKind.CLAUDE, baseUrl = "", model = "default"),
            authToken = "sk-ant-api03-key-under-claude",
        )
        check(apiKeyUnderClaudeLoginCfg.environment["ANTHROPIC_API_KEY"] == "sk-ant-api03-key-under-claude") {
            "Expected sk-ant-api* key under CLAUDE profile to route to ANTHROPIC_API_KEY"
        }
        check(apiKeyUnderClaudeLoginCfg.environment["ANTHROPIC_AUTH_TOKEN"] == "") {
            "Expected ANTHROPIC_AUTH_TOKEN to be empty when sk-ant-api* key is used under CLAUDE profile"
        }
        check(apiKeyUnderClaudeLoginCfg.environment["CLAUDE_CODE_OAUTH_TOKEN"] == "") {
            "Expected CLAUDE_CODE_OAUTH_TOKEN to be empty when sk-ant-api* key is used under CLAUDE profile"
        }

        val openRouterCfg = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(kind = ProviderKind.LLM_ROUTER),
            authToken = "sk-or-v1-router-key",
        )
        check(openRouterCfg.environment["ANTHROPIC_AUTH_TOKEN"] == "sk-or-v1-router-key") {
            "Expected ANTHROPIC_AUTH_TOKEN for OpenRouter"
        }
        check(openRouterCfg.environment["ANTHROPIC_API_KEY"] == "") {
            "Expected ANTHROPIC_API_KEY to be empty for OpenRouter Bearer auth"
        }

        // 5. Test Block 4: AndroidProjectTemplateGenerator (Compose & XML) + AndroidBuildSupport
        val composeProjDir = File(tmp, "android-compose")
        check(AndroidProjectTemplateGenerator.packageName("Forge Notes") == "com.nextcraft.forge_notes") {
            "Unexpected packageName for Forge Notes"
        }
        AndroidProjectTemplateGenerator.generate(
            destination = composeProjDir,
            appName = "Forge Notes",
            template = AndroidTemplate.COMPOSE,
        )
        check(File(composeProjDir, ".nextcraft/android-project.json").isFile) {
            "Expected .nextcraft/android-project.json to be generated"
        }
        check(File(composeProjDir, ".nextcraft/android-project.json").isFile) {
            "Expected .nextcraft/android-project.json to be generated"
        }
        check(findAndroidProjectRoot(composeProjDir)?.canonicalFile == composeProjDir.canonicalFile) {
            "Expected generated Compose project to be detected by findAndroidProjectRoot"
        }
        check(androidGradleCommand(composeProjDir, "assembleDebug").contains("assembleDebug --console=plain --stacktrace")) {
            "Expected valid androidGradleCommand output"
        }
        check(runCatching { androidGradleCommand(composeProjDir, "assembleDebug; id") }.isFailure) {
            "androidGradleCommand must reject task names containing shell metacharacters"
        }

        // Test findDebugApk blocks output-metadata.json path traversal
        val debugApkDir = File(composeProjDir, "app/build/outputs/apk/debug").apply { mkdirs() }
        File(outsideDir, "evil.apk").apply { writeText("FAKE_APK") }
        File(debugApkDir, "output-metadata.json").writeText(
            """{"elements":[{"outputFile":"../../../../../../outside/evil.apk"}]}"""
        )
        check(findDebugApk(composeProjDir) == null) {
            "findDebugApk must reject outputFile path traversal outside project root"
        }
        val validApk = File(debugApkDir, "app-debug.apk").apply { writeText("VALID_APK") }
        File(debugApkDir, "output-metadata.json").writeText(
            """{"elements":[{"outputFile":"app-debug.apk"}]}"""
        )
        check(findDebugApk(composeProjDir)?.canonicalFile == validApk.canonicalFile) {
            "findDebugApk should locate valid app-debug.apk"
        }

        // 6. Test Block 4: LocalNetwork scheme & host security + localhost port scanner
        check(LocalNetwork.isPlainHttpAllowed("https://api.openai.com/v1")) { "HTTPS should be allowed" }
        check(LocalNetwork.isPlainHttpAllowed("http://127.0.0.1:11434/v1")) { "Loopback HTTP should be allowed" }
        check(LocalNetwork.isPlainHttpAllowed("http://localhost:11434/v1")) { "localhost HTTP should be allowed" }
        check(LocalNetwork.isLocalHost("192.168.1.25")) { "LAN host should be recognized by isLocalHost" }
        check(LocalNetwork.isLocalHost("100.90.1.2")) { "Tailscale host should be recognized by isLocalHost" }
        check(LocalNetwork.isLocalHost("[::1]")) { "IPv6 loopback should be recognized by isLocalHost" }
        check(!LocalNetwork.isPlainHttpAllowed("http://api.openai.com/v1")) { "Public HTTP must be rejected" }
        check(!LocalNetwork.isPlainHttpAllowed("file:///etc/passwd")) { "file:// scheme must be rejected" }
        check(!LocalNetwork.isPlainHttpAllowed("ftp://127.0.0.1/test")) { "ftp:// scheme must be rejected" }

        ServerSocket(0).use { server ->
            val port = server.localPort
            check(LocalNetwork.isLocalPortOpen(port)) { "Expected bound ServerSocket port $port to be detected open" }
            check(LocalNetwork.scanOpenLocalPorts(listOf(port)) == listOf(port)) {
                "Expected scanOpenLocalPorts to return [$port]"
            }
        }

        // 7. Test Block 4: ProviderApiClient validationBody & ModelResponseParser
        val apiClient = ProviderApiClient()
        val orBody = apiClient.validationBody(
            model = "anthropic/claude-sonnet-4",
            protocol = ProviderProtocol.OPENROUTER,
            openRouterProviderOrder = "Anthropic, Google",
            openRouterAllowFallbacks = false,
        )
        check(orBody.contains("\"allow_fallbacks\":false") && orBody.contains("\"Anthropic\"")) {
            "Expected OpenRouter provider routing in validationBody, got: $orBody"
        }
        val parsedModels = ModelResponseParser.parse(
            """{"data":[{"id":"meta-llama/llama-3:free","display_name":"Llama 3 Free"},{"id":"claude-3-7-sonnet","display_name":"Claude 3.7 Sonnet"}]}"""
        )
        check(parsedModels.size == 2 && parsedModels[0].isFree && parsedModels[0].id == "meta-llama/llama-3:free") {
            "Expected free model sorted first in ModelResponseParser, got: $parsedModels"
        }

        // 8. Test BUG-028: NativeSpawn loads safely without UnsatisfiedLinkError & NativeSpawnProcess fallback works
        val fallbackOut = File(tmp, "native-fallback.log")
        val fallbackProc = NativeSpawnProcess.start(
            argv = listOf("/bin/sh", "-c", "printf 'fallback-spawn-ok\\n'"),
            environment = mapOf("TEST_VAR" to "1"),
            cwd = tmp.absolutePath,
            outputFile = fallbackOut,
        )
        check(fallbackProc.waitFor() == 0) { "Expected NativeSpawnProcess fallback to exit 0" }
        check(fallbackOut.readText().trim() == "fallback-spawn-ok") {
            "Expected fallback output 'fallback-spawn-ok', got '${fallbackOut.readText()}'"
        }

        // 9. Test BUG-030: UTF-8 continuation byte skipping in readTailText & completeUtf8PrefixLength
        val utf8File = File(tmp, "utf8-tail.txt")
        utf8File.writeText("A".repeat(100) + "🚀日本語テスト完了")
        // Read a tail that cuts right inside the 4-byte '🚀' (F0 9F 9A 80)
        val fullBytes = utf8File.readBytes()
        val tailCutSize = fullBytes.size - 101 // cuts 1 byte into '🚀'
        val tailText = utf8File.readTailText(tailCutSize)
        check(!tailText.contains('\uFFFD')) {
            "readTailText must not produce U+FFFD replacement char when cutting mid-codepoint: $tailText"
        }
        check(tailText == "日本語テスト完了") {
            "Expected clean UTF-8 tail '日本語テスト完了', got '$tailText'"
        }
        val rocketBytes = "Hi🚀".toByteArray(Charsets.UTF_8) // 'H','i', F0, 9F, 9A, 80 (6 bytes)
        check(completeUtf8PrefixLength(rocketBytes, 4) == 2) {
            "completeUtf8PrefixLength should back up before incomplete 4-byte UTF-8 sequence"
        }
        check(completeUtf8PrefixLength(rocketBytes, 6) == 6) {
            "completeUtf8PrefixLength should keep complete 4-byte UTF-8 sequence"
        }

        // 10. Test BUG-031: WorkspaceCheckpoints updates baseline when changes.json is empty
        val cpProject = "cp-test"
        val cpWorkspace = cp.ensureWorkspace(cpProject)
        val trackedFile = File(cpWorkspace, "Main.kt").apply { writeText("v1") }
        cp.createCheckpoint(cpProject, cpWorkspace)
        // Turn 1 made no pending changes (changes.json is empty), then user externally edits Main.kt to v2
        trackedFile.writeText("v2")
        cp.createCheckpoint(cpProject, cpWorkspace)
        val baselineFile = File(cp.checkpointDir(cpProject), "project/Main.kt")
        check(baselineFile.readText() == "v2") {
            "Expected createCheckpoint to refresh baseline when changes.json is empty, got '${baselineFile.readText()}'"
        }
        // Turn 2 modifies Main.kt to v3 and records it in changes.json
        trackedFile.writeText("v3")
        cp.saveChangedPaths(cpProject, listOf("Main.kt"))
        // Turn 3 starts while Main.kt is still unreviewed in changes.json: baseline must stay v2
        cp.createCheckpoint(cpProject, cpWorkspace)
        check(baselineFile.readText() == "v2") {
            "Expected createCheckpoint to preserve baseline v2 when unreviewed changes exist"
        }
        check(cp.revertFile(cpProject, cpWorkspace, "Main.kt")) {
            "Expected revertFile to succeed"
        }
        check(trackedFile.readText() == "v2") {
            "Expected revertFile to restore Main.kt to v2, got '${trackedFile.readText()}'"
        }

        // 11. Test BUG-032 & BUG-034: OpenRouter base URL trailing /v1 normalization
        val openRouterProfile = ProviderProfile(
            kind = ProviderKind.LLM_ROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            model = "anthropic/claude-sonnet-4",
        )
        val orLaunch = RuntimeLaunchConfigBuilder.build(openRouterProfile, authToken = "sk-or-test")
        check(orLaunch.environment["ANTHROPIC_BASE_URL"] == "https://openrouter.ai/api") {
            "Expected OpenRouter ANTHROPIC_BASE_URL without trailing /v1, got '${orLaunch.environment["ANTHROPIC_BASE_URL"]}'"
        }
        val dshOrRoute = DshRouteMapper.forProfile(openRouterProfile)
        check(dshOrRoute.custom?.baseUrl == "https://openrouter.ai/api") {
            "Expected DshRouteMapper OpenRouter baseUrl without trailing /v1, got '${dshOrRoute.custom?.baseUrl}'"
        }

        // 12. Test BUG-035: parseAndroidBuildIssues strips kotlinc 'e: file://' prefix
        val kotlincOutput = "e: file:///workspace/forge-notes/app/src/main/java/com/nextcraft/forge_notes/MainActivity.kt:14:9 Unresolved reference: brokenSymbol"
        val issues = parseAndroidBuildIssues(kotlincOutput, composeProjDir, 1)
        check(issues.size == 1 && issues[0].filePath == "app/src/main/java/com/nextcraft/forge_notes/MainActivity.kt" && issues[0].line == 14) {
            "Expected parseAndroidBuildIssues to resolve kotlinc e: file:// path, got: $issues"
        }

        // 13. Test C-3, M-18, L-3, L-4, L-5, L-10, L-12
        check(!LocalNetwork.isLocalHost("arbitrarysinglelabel")) {
            "L-10: Single-label non-.local hostname must not be treated as local"
        }
        check(LocalNetwork.isLocalHost("devbox.local")) {
            "L-10: Explicit .local mDNS hostname should be recognized by isLocalHost"
        }
        check(classifyToolRisk("Bash", "rm -rf /workspace") == RiskLevel.HIGH) {
            "C-3: Destructive rm -rf command must be classified as HIGH risk"
        }
        check(classifyToolRisk("Read", "") == RiskLevel.SAFE) {
            "C-3: Read tool must be classified as SAFE risk"
        }
        val binFile = File(cpWorkspace, "binary.bin").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())) }
        val binDetails = cp.buildChangeDetails(cpProject, cpWorkspace, listOf("binary.bin"))
        check(binDetails.size == 1 && binDetails[0].binary) {
            "M-18: Binary file diff should be detected as binary, got ${binDetails[0]}"
        }
        binFile.delete()
        check(extractGoogleOAuthUrl("Open in browser: https://evil.example.com/oauth2/auth?client_id=1234567890") == null) {
            "L-4: Non-Google OAuth URL must be rejected"
        }
        check(extractGoogleOAuthUrl("Open in browser: https://accounts.google.com/o/oauth2/v2/auth?client_id=1234567890") == "https://accounts.google.com/o/oauth2/v2/auth?client_id=1234567890") {
            "L-4: Official Google OAuth URL must be extracted"
        }
        check(!isSignedInScreen("Error: authentication failed - Antigravity CLI Google AI")) {
            "L-5: Screen containing error/failed must not match signed-in state"
        }
        check(isSignedInScreen("Antigravity CLI - Google AI ? for shortcuts")) {
            "L-5: Clean signed-in screen must be recognized"
        }
        val escapedPrompt = antigravityWorkspacePrompt("slug\"<inject>", "hello")
        check(!escapedPrompt.contains("slug\"<inject>") && escapedPrompt.contains("slug&quot;&lt;inject&gt;")) {
            "L-12: antigravityWorkspacePrompt must XML-escape projectSlug"
        }

        // BUG-036: AgentKind.fromStored accepts both stableId and enum name
        check(AgentKind.fromStored("deepseek-harness") == AgentKind.DEEPSEEK_HARNESS)
        check(AgentKind.fromStored("DEEPSEEK_HARNESS") == AgentKind.DEEPSEEK_HARNESS)
        check(AgentKind.fromStored("antigravity") == AgentKind.ANTIGRAVITY)
        check(AgentKind.fromStored("ANTIGRAVITY") == AgentKind.ANTIGRAVITY)

        // BUG-037: LocalFormatGateway tool_result ordering & OPENAI_RESPONSES protocol translation
        val chatGateway = LocalFormatGateway(
            ProviderProfile(kind = ProviderKind.DEEPSEEK, model = "deepseek-chat"),
            "sk-test-123",
        )
        try {
            val toOpenAiMethod = LocalFormatGateway::class.java.getDeclaredMethod("toOpenAi", JSONObject::class.java).apply {
                isAccessible = true
            }
            val mixedUserTurn = JSONObject()
                .put("model", "deepseek-chat")
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject().put("role", "assistant").put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "tool_use")
                                        .put("id", "call_1")
                                        .put("name", "read_file")
                                        .put("input", JSONObject().put("path", "a.kt")),
                                ),
                            ),
                        )
                        .put(
                            JSONObject().put("role", "user").put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "tool_result")
                                            .put("tool_use_id", "call_1")
                                            .put("content", "file contents"),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", "Now update it"),
                                    ),
                            ),
                        ),
                )
            val openAiChatBody = toOpenAiMethod.invoke(chatGateway, mixedUserTurn) as JSONObject
            val chatMsgs = openAiChatBody.getJSONArray("messages")
            check(chatMsgs.length() == 3) { "BUG-037: Expected 3 OpenAI messages, got ${chatMsgs.length()}" }
            check(chatMsgs.getJSONObject(0).getString("role") == "assistant")
            check(chatMsgs.getJSONObject(1).getString("role") == "tool") {
                "BUG-037: role='tool' must immediately follow assistant tool_calls before user text"
            }
            check(chatMsgs.getJSONObject(2).getString("role") == "user")
        } finally {
            chatGateway.close()
        }

        val responsesGateway = LocalFormatGateway(
            ProviderProfile(
                kind = ProviderKind.OPENCODE_ZEN,
                model = "gpt-5-codex",
                baseUrl = "https://opencode.ai/zen/v1",
            ),
            "sk-test-responses",
        )
        try {
            val toOpenAiMethod = LocalFormatGateway::class.java.getDeclaredMethod("toOpenAi", JSONObject::class.java).apply {
                isAccessible = true
            }
            val fromOpenAiMethod = LocalFormatGateway::class.java.getDeclaredMethod("fromOpenAi", JSONObject::class.java, String::class.java).apply {
                isAccessible = true
            }
            val anthropicReq = JSONObject()
                .put("model", "gpt-5-codex")
                .put("max_tokens", 2048)
                .put("system", "Be concise")
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "tool_result").put("tool_use_id", "call_9").put("content", "ok"))
                                .put(JSONObject().put("type", "text").put("text", "Next step")),
                        ),
                    ),
                )
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "bash")
                            .put("description", "Run shell")
                            .put("input_schema", JSONObject().put("type", "object")),
                    ),
                )
            val responsesBody = toOpenAiMethod.invoke(responsesGateway, anthropicReq) as JSONObject
            check(responsesBody.getString("instructions") == "Be concise")
            check(responsesBody.getInt("max_output_tokens") == 2048)
            val inputArr = responsesBody.getJSONArray("input")
            check(inputArr.getJSONObject(0).getString("type") == "function_call_output")
            check(inputArr.getJSONObject(1).getString("role") == "user")

            val upstreamResponses = JSONObject()
                .put("id", "resp_123")
                .put(
                    "output",
                    JSONArray()
                        .put(
                            JSONObject().put("type", "message").put(
                                "content",
                                JSONArray().put(JSONObject().put("type", "output_text").put("text", "Running bash")),
                            ),
                        )
                        .put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("call_id", "call_42")
                                .put("name", "bash")
                                .put("arguments", "{\"cmd\":\"ls\"}"),
                        ),
                )
                .put("usage", JSONObject().put("input_tokens", 12).put("output_tokens", 7))
            val translatedAnthropic = fromOpenAiMethod.invoke(responsesGateway, upstreamResponses, "gpt-5-codex") as JSONObject
            check(translatedAnthropic.getString("stop_reason") == "tool_use")
            val translatedContent = translatedAnthropic.getJSONArray("content")
            check(translatedContent.length() == 2)
            check(translatedContent.getJSONObject(0).getString("text") == "Running bash")
            check(translatedContent.getJSONObject(1).getString("type") == "tool_use")
            check(translatedContent.getJSONObject(1).getString("id") == "call_42")
        } finally {
            responsesGateway.close()
        }

        // BUG-039: InotifyLogTailer UTF-8 boundary truncation at MAX_TAIL_LINE_BYTES (524,288)
        val utf8TailFile = File(tmp, "utf8-overflow.log")
        val prefixBytes = ByteArray(512 * 1024 - 1) { 'x'.code.toByte() }
        // Append 3-byte Euro sign (€ = 0xE2 0x82 0xAC) right across the 512KB boundary
        val euroBytes = byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte(), '\n'.code.toByte())
        utf8TailFile.writeBytes(prefixBytes + euroBytes)
        val utf8Lines = mutableListOf<String>()
        InotifyLogTailer(utf8TailFile).use { tailer ->
            tailer.drainLines(timeoutMs = 0L, flushIncomplete = true) { utf8Lines += it }
        }
        check(utf8Lines.size == 1 && utf8Lines[0].endsWith("... [truncated]") && !utf8Lines[0].contains('\uFFFD')) {
            "BUG-039: InotifyLogTailer must not emit corrupt U+FFFD when 512KB truncation splits a multi-byte UTF-8 sequence"
        }

        // BUG-040: WorkspaceCheckpoints acceptFile & empty directory pruning on revert
        val pruneWs = cp.ensureWorkspace("proj-prune")
        val existingFile = File(pruneWs, "existing.txt").apply { writeText("v1") }
        cp.createCheckpoint("proj-prune", pruneWs)
        val nestedNewFile = File(pruneWs, "a/b/c/new.kt").apply {
            parentFile?.mkdirs()
            writeText("package a.b.c")
        }
        existingFile.writeText("v2")
        cp.saveChangedPaths("proj-prune", listOf("a/b/c/new.kt", "existing.txt"))
        check(cp.acceptFile("proj-prune", pruneWs, "existing.txt"))
        check(cp.readChangedPaths("proj-prune") == listOf("a/b/c/new.kt"))
        check(cp.revertFile("proj-prune", pruneWs, "a/b/c/new.kt"))
        check(!nestedNewFile.exists() && !File(pruneWs, "a").exists() && pruneWs.isDirectory) {
            "BUG-040: Reverting a newly created file in nested subdirectories must prune empty parent directories up to workspace"
        }

        // UPGRADE-001: InotifyLogTailer.readLines, hasUnreadBytes, and hasPendingPartialLine
        val tailStreamFile = File(tmp, "inotify-stream.log")
        tailStreamFile.writeText("line-1\npartial")
        InotifyLogTailer(tailStreamFile).use { tailer ->
            check(tailer.hasUnreadBytes())
            val firstBatch = tailer.readLines(timeoutMs = 0L)
            check(firstBatch == listOf("line-1")) { "UPGRADE-001: Expected [line-1], got $firstBatch" }
            check(!tailer.hasUnreadBytes())
            check(tailer.hasPendingPartialLine())
            val flushed = tailer.readLines(timeoutMs = 0L, flushIncomplete = true)
            check(flushed == listOf("partial")) { "UPGRADE-001: Expected [partial], got $flushed" }
            check(!tailer.hasPendingPartialLine())
        }

        // UPGRADE-002: LocalFormatGateway real-time OpenAI SSE -> Anthropic SSE streaming translation
        val sseGateway = LocalFormatGateway(
            ProviderProfile(kind = ProviderKind.DEEPSEEK, model = "deepseek-reasoner"),
            "sk-test-sse",
        )
        try {
            val sseOut = java.io.ByteArrayOutputStream()
            val openAiSseChunks = sequenceOf(
                """data: {"id":"chatcmpl-1","choices":[{"delta":{"reasoning_content":"Step 1: think"}}]}""",
                """data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"Hello world"}}]}""",
                """data: {"id":"chatcmpl-1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_sse_1","function":{"name":"bash","arguments":"{\"command\":"}}]}}]}""",
                """data: {"id":"chatcmpl-1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"ls\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":15,"completion_tokens":9}}""",
                "data: [DONE]",
            )
            sseGateway.streamOpenAiSseLines(
                openAiSseChunks,
                java.io.BufferedOutputStream(sseOut),
                "deepseek-reasoner",
            )
            val anthropicSse = sseOut.toString(Charsets.UTF_8.name())
            check(anthropicSse.contains("\"type\":\"thinking_delta\"") && anthropicSse.contains("Step 1: think")) {
                "UPGRADE-002: Expected thinking_delta in Anthropic SSE stream, got:\n$anthropicSse"
            }
            check(anthropicSse.contains("\"type\":\"text_delta\"") && anthropicSse.contains("Hello world")) {
                "UPGRADE-002: Expected text_delta in Anthropic SSE stream, got:\n$anthropicSse"
            }
            check(anthropicSse.contains("\"type\":\"tool_use\"") && anthropicSse.contains("\"id\":\"call_sse_1\"")) {
                "UPGRADE-002: Expected tool_use content_block_start in Anthropic SSE stream, got:\n$anthropicSse"
            }
            check(anthropicSse.contains("\"type\":\"input_json_delta\"")) {
                "UPGRADE-002: Expected input_json_delta in Anthropic SSE stream, got:\n$anthropicSse"
            }
            check(anthropicSse.contains("\"stop_reason\":\"tool_use\"") && anthropicSse.contains("event: message_stop")) {
                "UPGRADE-002: Expected stop_reason=tool_use and message_stop in Anthropic SSE stream"
            }
        } finally {
            sseGateway.close()
        }

        // UPGRADE-003: Incremental SHA-256/mtime-cached baseline checkpoint sync
        val incWs = cp.ensureWorkspace("proj-inc")
        val unchangedFile = File(incWs, "unchanged.txt").apply {
            writeText("stable-content")
            setLastModified(1_700_000_000_000L)
        }
        val modifiedFile = File(incWs, "modified.txt").apply { writeText("v1") }
        val deletedFile = File(incWs, "deleted.txt").apply { writeText("to-be-deleted") }
        cp.createCheckpoint("proj-inc", incWs)
        val backupDir = File(cp.checkpointDir("proj-inc"), "project")
        val backupUnchanged = File(backupDir, "unchanged.txt")
        check(backupUnchanged.lastModified() == 1_700_000_000_000L)
        // Mutate backupUnchanged mtime slightly? No, keep it identical to source mtime and check inode preservation!
        val inodeBefore = Files.getAttribute(backupUnchanged.toPath(), "unix:ino")
        modifiedFile.writeText("v2-updated")
        deletedFile.delete()
        cp.createCheckpoint("proj-inc", incWs)
        val inodeAfter = Files.getAttribute(backupUnchanged.toPath(), "unix:ino")
        check(inodeBefore == inodeAfter) {
            "UPGRADE-003: Unchanged file in checkpoint baseline should not be re-copied on incremental sync"
        }
        check(File(backupDir, "modified.txt").readText() == "v2-updated") {
            "UPGRADE-003: Modified file in checkpoint baseline must be updated"
        }
        check(!File(backupDir, "deleted.txt").exists()) {
            "UPGRADE-003: Deleted workspace file must be pruned from checkpoint baseline"
        }

        // UPGRADE-004: Expanded classifyToolRisk patterns + interactive high-risk approval & auto-deny timeout
        check(classifyToolRisk("Bash", "wget https://example.com/x.sh") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "chmod 777 /workspace/script.sh") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "dd if=/dev/zero of=/dev/null") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "mkfs.ext4 /dev/loop0") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "chown -R /workspace") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "rm -r -f build") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "python3 -c 'import os'") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "ls && rm --recursive x") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "echo ${'$'}(id)") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "echo x > /etc/passwd") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "find . -exec rm {} +") == RiskLevel.HIGH)
        check(classifyToolRisk("Bash", "git status") == RiskLevel.REVIEW)
        check(classifyToolRisk("Bash", "git add . && git commit -m x") == RiskLevel.REVIEW)
        check(classifyToolRisk("Bash", "gradle :app:compileDebugKotlin -q 2>&1 | tail -40") == RiskLevel.REVIEW)
        check(classifyToolRisk("Bash", "find app/src/main -name '*.kt' 2>/dev/null | xargs wc -l") == RiskLevel.REVIEW)
        check(classifyToolRisk("Bash", "grep -Ei 'update|vault' app/src") == RiskLevel.REVIEW)
        check(classifyToolRisk("Bash", "grep \"a\\|b\" app/src") == RiskLevel.REVIEW)
        check(classifyToolRisk("Bash", "grep a | rm -rf /") == RiskLevel.HIGH)
        check(classifyToolRisk("Edit", "", listOf("/opt/pocket/permission-hook.sh")) == RiskLevel.HIGH)
        check(classifyToolRisk("Write", "", listOf("/workspace/p/a.txt")) == RiskLevel.REVIEW)

        // Verify ProviderRuntimeErrorDetector ignores assistant/tool content, truncated JSON, and 429 api_retry
        check(ProviderRuntimeErrorDetector.detect("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"authentication failed expired quota rate limit HTTP 401"}]}}""") == null) {
            "ProviderRuntimeErrorDetector must ignore tool_result contents"
        }
        check(ProviderRuntimeErrorDetector.detect("""{"type":"user","message":{"role":"user","content":"authentication failed expired quota... [truncated]""") == null) {
            "ProviderRuntimeErrorDetector must ignore truncated stream-json lines"
        }
        check(ProviderRuntimeErrorDetector.detect("""{"type":"system","subtype":"api_retry","error_status":429}""") == null) {
            "ProviderRuntimeErrorDetector must not abort on transient 429 api_retry"
        }
        check(ProviderRuntimeErrorDetector.detect("""{"type":"system","subtype":"api_retry","error_status":401}""") == "The provider rejected the saved API key.") {
            "ProviderRuntimeErrorDetector must detect 401 api_retry"
        }
        check(ProviderRuntimeErrorDetector.detect("""{"type":"result","is_error":true,"result":"API Error: 401 {\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"}""") == "The provider rejected the saved API key.") {
            "ProviderRuntimeErrorDetector must detect 401 authentication error in result"
        }
        check(ProviderRuntimeErrorDetector.detect("""{"type":"result","is_error":true,"result":"API Error: 401 {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid bearer token\"}}"}""") == "The provider rejected the saved API key.") {
            "ProviderRuntimeErrorDetector must detect 401 Invalid bearer token error in result"
        }

        kotlinx.coroutines.runBlocking {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            val ctx = unsafe.allocateInstance(android.content.ContextWrapper::class.java) as android.content.Context
            val claudeInteractive = ClaudeRuntimeBridge(
                context = ctx,
                secretFor = { "sk-test" },
                interactiveHighRiskApprovals = { true },
            )
            val bridgeDir = File(tmp, "runtime-bridge").apply { mkdirs() }
            val reqFile = File(bridgeDir, "req-1.request").apply {
                writeText("""{"tool_name":"Bash","tool_input":{"command":"rm -rf build","description":"Clean build"}}""")
            }
            claudeInteractive.processPermissionRequestFile("sess-1", reqFile)
            val respFile = File(tmp, "runtime-bridge-responses/req-1.response")
            check(!respFile.exists()) {
                "UPGRADE-004: Interactive high-risk approval must wait for user response before writing .response file"
            }
            claudeInteractive.respondToApproval(
                com.nextcraft.forge.model.ToolRequest(
                    approvalId = "req-1",
                    sessionId = "sess-1",
                    toolName = "Bash",
                    explanation = "Clean build",
                    affectedPaths = emptyList(),
                    commandPreview = "rm -rf build",
                    risk = RiskLevel.HIGH,
                ),
                approved = true,
            )
            check(respFile.readText() == "allow") {
                "UPGRADE-004: Approved high-risk tool request must write 'allow' to .response file"
            }

            // Test auto-deny timeout on expired high-risk request
            val reqTimeoutFile = File(bridgeDir, "req-timeout.request").apply {
                writeText("""{"tool_name":"Bash","tool_input":{"command":"git push --force"}}""")
            }
            claudeInteractive.processPermissionRequestFile("sess-1", reqTimeoutFile)
            claudeInteractive.expirePendingPermissions(
                sessionId = "sess-1",
                nowElapsedRealtime = android.os.SystemClock.elapsedRealtime() + 60_000L,
                timeoutMs = 40_000L,
            )
            check(File(tmp, "runtime-bridge-responses/req-timeout.response").readText() == "deny") {
                "UPGRADE-004: Timed-out high-risk tool request must auto-write 'deny'"
            }

            // BUG-042: Verify RuntimeInstaller safeChild & resolveSafeSymlinkTarget handle:
            // 1) Android /data/user/0 -> /data/data symlinked parent path
            // 2) Relative symlinks (bin -> usr/bin) and guest-absolute symlinks (/usr/bin/mawk)
            // 3) Blocking escaping relative and absolute symlinks
            val installer = unsafe.allocateInstance(RuntimeInstaller::class.java) as RuntimeInstaller
            val safeChildMethod = RuntimeInstaller::class.java.getDeclaredMethod("safeChild", File::class.java, String::class.java).apply {
                isAccessible = true
            }
            val resolveSymMethod = RuntimeInstaller::class.java.getDeclaredMethod(
                "resolveSafeSymlinkTarget",
                java.nio.file.Path::class.java,
                java.nio.file.Path::class.java,
                String::class.java,
                String::class.java,
            ).apply {
                isAccessible = true
            }
            val realDataDir = File(tmp, "data_data/files/runtime/ubuntu.installing").apply { mkdirs() }
            val userZeroDir = File(tmp, "data_user_0").apply { parentFile?.mkdirs() }
            Files.createSymbolicLink(userZeroDir.toPath(), File(tmp, "data_data").toPath())
            val viaSymlinkRoot = File(userZeroDir, "files/runtime/ubuntu.installing")
            val rootPath = viaSymlinkRoot.canonicalFile.toPath()

            val targetAwk = safeChildMethod.invoke(installer, viaSymlinkRoot, "etc/alternatives/awk") as File
            check(targetAwk.toPath().startsWith(rootPath)) {
                "BUG-042: safeChild must return canonicalized path under rootPath even when destination has symlinked parent"
            }
            // Valid guest-absolute symlink (/usr/bin/mawk)
            val resolvedAbs = resolveSymMethod.invoke(
                installer,
                rootPath,
                targetAwk.toPath().parent,
                "/usr/bin/mawk",
                "Archive symlink escapes runtime",
            ) as java.nio.file.Path
            check(resolvedAbs == rootPath.resolve("usr/bin/mawk")) {
                "BUG-042: Guest-absolute symlink /usr/bin/mawk must resolve inside rootPath"
            }
            // Valid relative symlink (../lib/nodejs/bin/node)
            val targetNode = safeChildMethod.invoke(installer, viaSymlinkRoot, "usr/local/bin/node") as File
            val resolvedRel = resolveSymMethod.invoke(
                installer,
                rootPath,
                targetNode.toPath().parent,
                "../lib/nodejs/bin/node",
                "Archive symlink escapes runtime",
            ) as java.nio.file.Path
            check(resolvedRel == rootPath.resolve("usr/local/lib/nodejs/bin/node")) {
                "BUG-042: Relative symlink ../lib/nodejs/bin/node must resolve inside rootPath"
            }
            // Escaping relative symlink must fail
            val escapedRel = runCatching {
                resolveSymMethod.invoke(
                    installer,
                    rootPath,
                    targetAwk.toPath().parent,
                    "../../../../outside",
                    "Archive symlink escapes runtime",
                )
            }.isFailure
            check(escapedRel) { "BUG-042: Escaping relative symlink must be rejected" }
            // Escaping guest-absolute symlink must fail
            val escapedAbs = runCatching {
                resolveSymMethod.invoke(
                    installer,
                    rootPath,
                    targetAwk.toPath().parent,
                    "/../../../outside",
                    "Archive symlink escapes runtime",
                )
            }.isFailure
            check(escapedAbs) { "BUG-042: Escaping guest-absolute symlink must be rejected" }
        }
    } finally {
        tmp.deleteRecursively()
    }
}
