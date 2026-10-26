package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaChatHintTest {
    private fun file(name: String) = ChatAttachment(displayName = name, relativePath = "attachments/$name", mimeType = "application/octet-stream", sizeBytes = 1)

    @Test
    fun addsNoteForBinaryAttachment() {
        val out = ReaChatHint.augment("what is this?", listOf(file("app.APK")), reaInstalled = true)
        assertTrue("rea analyze" in out)
    }

    @Test
    fun addsNoteWhenPromptNamesBinaryWork() {
        assertTrue("rea apk" in ReaChatHint.augment("Please decompile this", emptyList(), reaInstalled = true))
        assertTrue("rea apk" in ReaChatHint.augment("analisis firmware ini", emptyList(), reaInstalled = true))
    }

    @Test
    fun leavesOrdinaryChatsAndMissingReaUntouched() {
        assertEquals("fix the login bug", ReaChatHint.augment("fix the login bug", listOf(file("notes.txt")), reaInstalled = true))
        assertEquals("decompile this", ReaChatHint.augment("decompile this", emptyList(), reaInstalled = false))
    }
}
