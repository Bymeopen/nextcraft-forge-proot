package com.nextcraft.forge.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Runs the branch shell scripts against real git in a temp dir (skipped when bash or git is missing). */
class ChatBranchesScriptTest {
    private lateinit var root: File
    private lateinit var project: File
    private val branch get() = File(project, ".forge/chats/main")

    @Before
    fun setUp() {
        assumeTrue("bash and git are required", exec(File("."), "bash", "-c", "command -v git").first == 0)
        root = File.createTempFile("forge-branch", "").apply { delete(); mkdirs() }
        project = File(root, "proj").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun exec(dir: File, vararg cmd: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", dir.absolutePath)
            put("GIT_CONFIG_NOSYSTEM", "1")
            putAll(env)
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "script timed out" }
        return p.exitValue() to out
    }

    /** The scripts address the project as /workspace/<slug>; point that at the temp project instead. */
    private fun run(script: String, vararg env: Pair<String, String>): Pair<Int, String> {
        val local = script.replace("M=\"/workspace/\$FORGE_SLUG\"", "M=\"${project.absolutePath}\"")
        return exec(root, "bash", "-c", local, env = mapOf("FORGE_SLUG" to "proj", "FORGE_ID" to "main") + env)
    }

    private fun git(dir: File, vararg args: String) =
        exec(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", *args).also { assertEquals(it.second, 0, it.first) }.second

    @Test
    fun gitHelperReportsRealFailures() {
        // Regression: G() used `local rc=$?` after an `if`, which is always 0, so every git failure was swallowed.
        val (code, _) = run(ChatBranches.script("cd \"§M\"\nG rev-parse -q --verify no-such-ref"))
        assertNotEquals(0, code)
    }

    @Test
    fun ensureCarriesUncommittedFilesOfAFreshProjectIntoTheBranch() {
        File(project, "uji.txt").writeText("halo")
        val (code, out) = run(ChatBranches.ENSURE_SCRIPT)
        assertEquals(out, 0, code)
        assertEquals("halo", File(branch, "uji.txt").readText())
        assertTrue(git(branch, "tag").contains("forge-base"))
    }

    @Test
    fun ensureSnapshotsUncommittedChangesOfARepoWithHistory() {
        git(project, "init", "-q", "-b", "main")
        File(project, "a.txt").writeText("one")
        git(project, "add", "-A")
        git(project, "commit", "-q", "-m", "first")
        File(project, "a.txt").writeText("two")
        File(project, "b.txt").writeText("new")
        val (code, out) = run(ChatBranches.ENSURE_SCRIPT)
        assertEquals(out, 0, code)
        assertEquals("two", File(branch, "a.txt").readText())
        assertEquals("new", File(branch, "b.txt").readText())
    }

    @Test
    fun ensureIsIdempotent() {
        File(project, "a.txt").writeText("one")
        assertEquals(0, run(ChatBranches.ENSURE_SCRIPT).first)
        File(branch, "c.txt").writeText("kept")
        assertEquals(0, run(ChatBranches.ENSURE_SCRIPT).first)
        assertEquals("kept", File(branch, "c.txt").readText())
    }

    @Test
    fun mergeReportsAConflictInsteadOfMerged() {
        File(project, "a.txt").writeText("base")
        assertEquals(0, run(ChatBranches.ENSURE_SCRIPT).first)
        File(branch, "a.txt").writeText("from branch")
        assertEquals(0, run(ChatBranches.COMMIT_SCRIPT, "MSG" to "branch edit").first)
        File(project, "a.txt").writeText("from main")
        val (code, out) = run(ChatBranches.MERGE_SCRIPT, "TITLE" to "merge")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("RESULT=conflict"))
        assertTrue(out, !out.contains("RESULT=merged"))
        assertEquals("from main", File(project, "a.txt").readText())
    }
}
