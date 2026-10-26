package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorsTest {
    @Test
    fun connectorIdsAreSafe() {
        assertEquals("context7", McpNames.id(" Context7 "))
        assertEquals("my-notes-server", McpNames.id("My Notes/Server!"))
        assertNull(McpNames.id("***"))
        assertEquals("forge-context7", McpConnector("context7", "Context7", McpTransport.HTTP, "https://x").key)
    }

    @Test
    fun commandLinesAreSplitLikeAShell() {
        assertEquals(listOf("npx", "-y", "@scope/pkg"), McpNames.splitCommandLine("npx -y @scope/pkg"))
        assertEquals(listOf("node", "my server.js", "--flag"), McpNames.splitCommandLine("node 'my server.js'  --flag"))
        assertEquals(listOf("echo", "a b"), McpNames.splitCommandLine("echo \"a b\""))
        assertEquals(emptyList<String>(), McpNames.splitCommandLine("   "))
    }

    @Test
    fun envNames() {
        assertTrue(McpNames.isEnvName("API_KEY"))
        assertTrue(!McpNames.isEnvName("1KEY"))
        assertTrue(!McpNames.isEnvName("A-B"))
    }

    @Test
    fun openSourcePresetsAndAntigravityMcpSync() {
        val presets = McpConnector.OPEN_SOURCE_PRESETS
        assertTrue(presets.any { it.name == "Figma" && it.secretName == "FIGMA_API_KEY" })
        assertTrue(presets.any { it.name == "Context7" && it.transport == McpTransport.HTTP })
        assertTrue(presets.any { it.name == "Mobile MCP" && it.scope == McpTargetScope.MOBILE })
        assertTrue(presets.any { it.name == "Unity-Godot MCP" && it.scope == McpTargetScope.GAME })
        assertEquals("Mobile MCP", McpConnector.presetsForProject(isMobileTarget = true).first().name)
        assertEquals("Unity-Godot MCP", McpConnector.presetsForProject(isGameTarget = true).first().name)
        assertTrue(presets.all { McpNames.id(it.name) != null })
        assertTrue(presets.filter { it.secretName.isNotEmpty() }.all { McpNames.isEnvName(it.secretName) })

        val tempRootfs = java.nio.file.Files.createTempDirectory("mcp-sync-test").toFile()
        try {
            val managed = org.json.JSONObject()
                .put("forge-figma", org.json.JSONObject().put("type", "stdio").put("command", "npx"))
            AntigravityCustomizations.apply(tempRootfs, "/workspace/demo", managed) { file, body ->
                file.parentFile?.mkdirs()
                file.writeText(body)
            }
            val mcpFile = java.io.File(tempRootfs, "root/.gemini/config/mcp_config.json")
            assertTrue(mcpFile.isFile)
            val parsed = org.json.JSONObject(mcpFile.readText()).getJSONObject("mcpServers")
            assertEquals("npx", parsed.getJSONObject("forge-figma").getString("command"))
        } finally {
            tempRootfs.deleteRecursively()
        }
    }

    @Test
    fun skillNamesAndFrontmatter() {
        assertEquals("code-review", ClaudeSkillStore.skillName("Code Review!"))
        assertNull(ClaudeSkillStore.skillName("???"))
        val text = ClaudeSkillStore.render("code-review", "Use when: reviewing a diff", "Check the tests.")
        assertEquals("code-review" to "Use when: reviewing a diff", ClaudeSkillStore.parseFrontmatter(text))
        assertTrue(text.endsWith("Check the tests.\n"))
        assertEquals("plain text" , ClaudeSkillStore.parseFrontmatter(ClaudeSkillStore.render("a", "plain text", "x")).second)

        val tempDir = java.nio.file.Files.createTempDirectory("skills-test").toFile()
        try {
            val store = ClaudeSkillStore(tempDir)
            store.save("../Code Review!", "Safe skill", "Body text")
            assertTrue(java.io.File(tempDir, "code-review/SKILL.md").isFile)
            assertEquals("Body text", store.body("../Code Review!"))
            assertTrue(store.delete("../Code Review!"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
