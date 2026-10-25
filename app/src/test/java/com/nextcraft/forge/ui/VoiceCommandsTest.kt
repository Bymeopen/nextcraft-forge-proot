package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
    @Test
    fun plainSentenceIsDictated() {
        assertEquals(VoiceCommand.Dictate("Fix the login bug", send = false), VoiceCommands.parse("  Fix the login bug "))
    }

    @Test
    fun wholeSentenceCommands() {
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("Stop."))
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("berhenti"))
        assertEquals(VoiceCommand.Clear, VoiceCommands.parse("hapus teks"))
        assertEquals(VoiceCommand.Dictate("", send = true), VoiceCommands.parse("Kirim"))
    }

    @Test
    fun sendPhraseAfterDictation() {
        assertEquals(VoiceCommand.Dictate("Run the tests", send = true), VoiceCommands.parse("Run the tests, send now"))
        assertEquals(VoiceCommand.Dictate("Perbaiki tombol login", send = true), VoiceCommands.parse("Perbaiki tombol login kirim sekarang."))
    }

    @Test
    fun commandWordsInsideASentenceAreJustText() {
        assertEquals(VoiceCommand.Dictate("please stop the server", send = false), VoiceCommands.parse("please stop the server"))
        assertEquals(VoiceCommand.Dictate("how do I send a file", send = false), VoiceCommands.parse("how do I send a file"))
    }

    @Test
    fun speechLanguage_modernisesLegacyCodes_andFollowsTheDevice() {
        assertEquals("id", speechLanguageTag("in", "en-US"))
        assertEquals("id-ID", speechLanguageTag("in_ID", "en-US"))
        assertEquals("he", speechLanguageTag("iw", "en-US"))
        assertEquals("es", speechLanguageTag("es", "en-US"))
        assertEquals("en-US", speechLanguageTag("system", "en-US"))
        assertEquals("zh-CN", speechLanguageTag("zh-CN", "en-US"))
    }

    @Test
    fun silenceIsNotAnError_butNetworkAndBusyAreExplained() {
        org.junit.Assert.assertNull(voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_NO_MATCH))
        org.junit.Assert.assertNull(voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(com.nextcraft.forge.R.string.voice_error_network, voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_NETWORK))
        assertEquals(com.nextcraft.forge.R.string.voice_error_busy, voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(com.nextcraft.forge.R.string.voice_permission_denied, voiceErrorMessage(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun manifestDeclaresTheRecognitionServiceQuery() {
        // Without it Android 11+ reports "speech recognition not available" even when the service is installed.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        org.junit.Assert.assertTrue(manifest.contains("android.speech.RecognitionService"))
    }
}
