package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBranchesTest {
    @Test
    fun branchIdIsShortAndPathSafe() {
        assertEquals("eeeeeeee", ChatBranches.branchId("aaaaaaaa-bbbb-cccc-dddd-EEEEEEEEEEEE"))
        assertEquals("main", ChatBranches.branchId("main"))
        assertEquals("main", ChatBranches.branchId("../.."))
    }

    @Test
    fun pseudoIdsAndSlugsFitTheChrootNamingRules() {
        val slug = ChatBranches.branchSlug("a".repeat(70), "1a2b3c4d")
        assertTrue(slug.length <= 63)
        assertTrue(Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(slug))
        assertEquals("forge-sandbox-c1a2b3c4d", ChatBranches.branchSlug("forge-sandbox", "1a2b3c4d"))
        assertEquals("p1-c1a2b3c4d", ChatBranches.pseudoProjectId("p1", "1a2b3c4d"))
    }

    @Test
    fun commitMessageIsOneCleanLine() {
        assertEquals("Fix the login", ChatBranches.commitMessage("\n  Fix the login\nsecond line"))
        assertEquals("Update", ChatBranches.commitMessage("   "))
        assertEquals(100, ChatBranches.commitMessage("x".repeat(300)).length)
    }

    @Test
    fun statusOutputIsParsed() {
        val status = ChatBranches.parseStatus(
            """
            ahead=3
            uncommitted=2
            conflicts=a.kt,b/c.kt,
            stat=40 7 5
            behind=1
            C 1a2b3c Add login screen
            C 9f8e7d Fix typo
            """.trimIndent(),
        )
        assertEquals(3, status.commits)
        assertEquals(2, status.uncommitted)
        assertEquals(listOf("a.kt", "b/c.kt"), status.conflicts)
        assertEquals(40, status.additions)
        assertEquals(7, status.deletions)
        assertEquals(5, status.filesChanged)
        assertEquals(1, status.behind)
        assertEquals(listOf(BranchCommit("1a2b3c", "Add login screen"), BranchCommit("9f8e7d", "Fix typo")), status.recent)
    }

    @Test
    fun mergeResultsAreRecognised() {
        assertEquals(BranchMergeResult.Done, ChatBranches.parseMergeResult("noise\nRESULT=merged\n", true))
        assertEquals(BranchMergeResult.Done, ChatBranches.parseMergeResult("RESULT=updated", true))
        assertEquals(
            BranchMergeResult.Conflict(listOf("a.kt", "b.kt")),
            ChatBranches.parseMergeResult("RESULT=conflict\nfiles=a.kt,b.kt,\n", true),
        )
        assertTrue(ChatBranches.parseMergeResult("fatal: bad object", false) is BranchMergeResult.Failed)
        assertEquals(
            BranchMergeResult.Failed("git merge failed without conflicts"),
            ChatBranches.parseMergeResult("RESULT=failed\nmsg=git merge failed without conflicts\n", true),
        )
    }

    @Test
    fun repoInfoIsParsed() {
        val info = ChatBranches.parseRepoInfo("repo=yes\nbranch=main\nuncommitted=4\nC a1b2c3 First\nC d4e5f6 Second")
        assertTrue(info.isRepo)
        assertEquals("main", info.branch)
        assertEquals(4, info.uncommitted)
        assertEquals(listOf(BranchCommit("a1b2c3", "First"), BranchCommit("d4e5f6", "Second")), info.recent)
        assertEquals(ProjectRepoInfo(), ChatBranches.parseRepoInfo("repo=none"))
    }

    @Test
    fun branchNamesAreMadeSafe() {
        assertEquals("fix-login-screen", ChatBranches.branchName("  Fix  Login Screen "))
        assertEquals("feature/new-ui", ChatBranches.branchName("feature/New UI"))
        assertEquals("a.b", ChatBranches.branchName("a..b"))
        assertEquals(null, ChatBranches.branchName("***"))
        assertEquals(null, ChatBranches.branchName("chat/abc"))
        assertEquals(null, ChatBranches.branchName("x.lock"))
    }

    @Test
    fun repoInfoListsBranches() {
        val info = ChatBranches.parseRepoInfo("repo=yes\nbranch=dev\nuncommitted=0\nB main\nB dev")
        assertEquals(listOf("main", "dev"), info.branches)
    }
}
