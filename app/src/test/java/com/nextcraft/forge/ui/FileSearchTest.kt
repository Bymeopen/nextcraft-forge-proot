package com.nextcraft.forge.ui

import com.nextcraft.forge.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchTest {
    private fun dir(path: String) = WorkspaceEntry(path, path.substringAfterLast('/'), true, path.count { it == '/' })
    private fun file(path: String) = WorkspaceEntry(path, path.substringAfterLast('/'), false, path.count { it == '/' }, 100)

    private val tree = listOf(
        dir("app"),
        dir("app/src"),
        file("app/src/Main.kt"),
        dir("app/src/util"),
        file("app/src/util/Strings.kt"),
        file("build.gradle.kts"),
        dir("docs"),
        file("docs/main-guide.md"),
        file("README.md"),
    )

    private fun FileSearchResult.paths() = entries.map { it.path }

    @Test
    fun blankQueryReturnsTheListingUnchanged() {
        val r = searchWorkspaceEntries(tree, "   ")
        assertEquals(tree, r.entries)
        assertTrue(r.matches.isEmpty())
    }

    @Test
    fun matchesNamesCaseInsensitivelyAndKeepsTheFoldersThatLeadThere() {
        val r = searchWorkspaceEntries(tree, "MAIN")
        assertEquals(setOf("app/src/Main.kt", "docs/main-guide.md"), r.matches)
        // matches plus their ancestors, in the original tree order; unrelated rows are gone
        assertEquals(listOf("app", "app/src", "app/src/Main.kt", "docs", "docs/main-guide.md"), r.paths())
    }

    @Test
    fun aFolderMatchShowsTheFolderAndItsAncestorsButNotItsChildren() {
        val r = searchWorkspaceEntries(tree, "util")
        assertEquals(setOf("app/src/util"), r.matches)
        assertEquals(listOf("app", "app/src", "app/src/util"), r.paths())
    }

    @Test
    fun aQueryWithASlashMatchesTheWholePath() {
        val r = searchWorkspaceEntries(tree, "src/util")
        assertEquals(setOf("app/src/util", "app/src/util/Strings.kt"), r.matches)
        assertEquals(listOf("app", "app/src", "app/src/util", "app/src/util/Strings.kt"), r.paths())
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(setOf("README.md"), searchWorkspaceEntries(tree, "  readme ").matches)
    }

    @Test
    fun noMatchGivesAnEmptyResult() {
        val r = searchWorkspaceEntries(tree, "zzz")
        assertTrue(r.entries.isEmpty())
        assertTrue(r.matches.isEmpty())
    }

    @Test
    fun anAncestorIsNotCountedAsAMatchUnlessItsNameMatches() {
        val r = searchWorkspaceEntries(tree, "strings")
        assertEquals(setOf("app/src/util/Strings.kt"), r.matches)
        assertTrue("app" !in r.matches && "app/src" !in r.matches)
    }
}
