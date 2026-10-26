package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCaptureStepsTest {
    private val steps = appCaptureSteps()

    @Test
    fun namesAreUniqueSafeFileNames() {
        assertEquals(steps.size, steps.map { it.name }.toSet().size)
        steps.forEach { assertTrue("${it.name} must be a plain file name", Regex("[a-z0-9-]+").matches(it.name)) }
    }

    @Test
    fun coversTheProjectPanesAndTheMainGlobalScreens() {
        val panes = steps.mapNotNull { it.pane }.toSet()
        assertEquals(setOf(ProjectPane.CHAT, ProjectPane.FILES, ProjectPane.TERMINAL, ProjectPane.PREVIEW), panes)
        val tabs = steps.filter { it.pane == null }.map { it.tab }.toSet()
        assertTrue(tabs.containsAll(listOf(ForgeTab.PROJECTS, ForgeTab.AGENT, ForgeTab.TOOLS, ForgeTab.SETTINGS)))
    }

    @Test
    fun paneStepsStayInsideTheProjectsTab() {
        steps.filter { it.pane != null }.forEach { assertEquals(ForgeTab.PROJECTS, it.tab) }
    }
}
