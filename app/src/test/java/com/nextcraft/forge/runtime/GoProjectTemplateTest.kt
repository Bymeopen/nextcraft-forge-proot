package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoProjectTemplateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesAModuleNamedAfterTheProject() {
        val dir = File(tmp.root, "ws")
        GoProjectTemplateGenerator.generate(dir, "My Cool App")
        assertEquals("module my-cool-app\n\ngo 1.22\n", File(dir, "go.mod").readText())
        val main = File(dir, "main.go").readText()
        assertTrue("package main" in main)
        assertTrue("const appName = \"my-cool-app\"" in main)
        assertTrue("-X main.version" in main)
        assertTrue("func TestGreeting" in File(dir, "main_test.go").readText())
        assertEquals("dist/\n", File(dir, ".gitignore").readText())
    }

    @Test
    fun goSourceKeepsItsTabsAndEscapes() {
        val main = GoProjectTemplateGenerator.files("x")["main.go"]!!
        assertTrue("\n\treturn fmt.Sprintf(\"Hello, %s!\", name)\n" in main)
        assertTrue("\\n\", appName" in main)
    }

    @Test
    fun theGeneratedProjectIsDetectedAsACrossBuildProject() {
        val dir = File(tmp.root, "ws")
        GoProjectTemplateGenerator.generate(dir, "demo")
        val ids = detectBuildTargets(dir).map { it.id }
        assertTrue(ids.containsAll(listOf("go:windows-amd64", "go:linux-amd64", "go:linux-arm64")))
    }

    @Test
    fun refusesToOverwriteAnExistingModule() {
        val dir = tmp.newFolder("ws")
        File(dir, "go.mod").writeText("module keep\n")
        val failed = runCatching { GoProjectTemplateGenerator.generate(dir, "demo") }.isFailure
        assertTrue(failed)
        assertEquals("module keep\n", File(dir, "go.mod").readText())
    }

    @Test
    fun iosWorkflowIsWrittenOnceAndNeverOverwritten() {
        val dir = tmp.newFolder("ios")
        assertNull(findXcodeProject(dir))
        assertFalse(writeIosWorkflow(dir))
        File(dir, "Demo.xcodeproj").mkdirs()
        assertEquals("Demo.xcodeproj", findXcodeProject(dir))
        assertTrue(writeIosWorkflow(dir))
        val yaml = File(dir, IOS_WORKFLOW_PATH).readText()
        assertTrue("-project 'Demo.xcodeproj' -scheme 'Demo'" in yaml)
        File(dir, IOS_WORKFLOW_PATH).writeText("mine")
        assertFalse(writeIosWorkflow(dir))
        assertEquals("mine", File(dir, IOS_WORKFLOW_PATH).readText())
    }

    @Test
    fun aWorkspaceIsPreferredOverAProject() {
        val dir = tmp.newFolder("both")
        File(dir, "A.xcodeproj").mkdirs()
        File(dir, "A.xcworkspace").mkdirs()
        assertEquals("A.xcworkspace", findXcodeProject(dir))
    }
}
