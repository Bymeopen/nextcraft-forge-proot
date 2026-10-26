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
    fun skillNamesAndFrontmatter() {
        assertEquals("code-review", ClaudeSkillStore.skillName("Code Review!"))
        assertNull(ClaudeSkillStore.skillName("???"))
        val text = ClaudeSkillStore.render("code-review", "Use when: reviewing a diff", "Check the tests.")
        assertEquals("code-review" to "Use when: reviewing a diff", ClaudeSkillStore.parseFrontmatter(text))
        assertTrue(text.endsWith("Check the tests.\n"))
        assertEquals("plain text" , ClaudeSkillStore.parseFrontmatter(ClaudeSkillStore.render("a", "plain text", "x")).second)
    }
}
