package com.nextcraft.forge.ui

import com.nextcraft.forge.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The file manager's ordering rules: folders first, hidden entries opt-in, three orders inside each group. */
class FileSortTest {
    private fun file(name: String, size: Long = 0, modified: Long = 0) =
        WorkspaceEntry(path = name, name = name, isDirectory = false, depth = 0, sizeBytes = size, modifiedMillis = modified)

    private fun dir(name: String, modified: Long = 0) =
        WorkspaceEntry(path = name, name = name, isDirectory = true, depth = 0, childCount = 0, modifiedMillis = modified)

    private val sample = listOf(
        file("b.txt", size = 10, modified = 300),
        dir("src", modified = 100),
        file("A.md", size = 500, modified = 100),
        dir("assets", modified = 400),
        file(".env", size = 1, modified = 900),
        dir(".git", modified = 800),
    )

    private fun names(sort: FileSort, showHidden: Boolean = false) =
        sortWorkspaceEntries(sample, sort, showHidden).map { it.name }

    @Test
    fun foldersComeFirstThenFilesByNameIgnoringCase() {
        assertEquals(listOf("assets", "src", "A.md", "b.txt"), names(FileSort.NAME))
    }

    @Test
    fun hiddenEntriesAreOptIn() {
        assertEquals(listOf("assets", "src", "A.md", "b.txt"), names(FileSort.NAME, showHidden = false))
        assertEquals(listOf(".git", "assets", "src", ".env", "A.md", "b.txt"), names(FileSort.NAME, showHidden = true))
    }

    @Test
    fun modifiedOrderIsNewestFirstInsideEachGroup() {
        assertEquals(listOf("assets", "src", "b.txt", "A.md"), names(FileSort.MODIFIED))
    }

    @Test
    fun sizeOrderIsLargestFirstAndFoldersStayOnTop() {
        assertEquals(listOf("assets", "src", "A.md", "b.txt"), names(FileSort.SIZE))
    }

    @Test
    fun emptyFolderStaysEmpty() {
        assertEquals(emptyList<WorkspaceEntry>(), sortWorkspaceEntries(emptyList(), FileSort.NAME, showHidden = true))
    }
}
