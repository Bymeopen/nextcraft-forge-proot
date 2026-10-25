package com.nextcraft.forge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.nextcraft.forge.R
import java.util.Locale

/** What a spoken sentence means in the chat composer. */
internal sealed interface VoiceCommand {
    /** Words to put in the message box; [send] when the speaker ended with a send phrase. */
    data class Dictate(val text: String, val send: Boolean) : VoiceCommand
    data object Stop : VoiceCommand
    data object Clear : VoiceCommand
}

/**
 * Turns a recognised sentence into a [VoiceCommand]. Whole-sentence commands (stop, clear, send) are matched exactly
 * so that dictating a normal sentence never triggers them; sending after dictation needs the phrase "send now".
 */
internal object VoiceCommands {
    private val stopPhrases = setOf("stop", "stop agent", "berhenti", "hentikan", "hentikan agen", "hentikan agent")
    private val clearPhrases = setOf("clear", "clear text", "hapus teks", "bersihkan", "bersihkan teks")
    private val sendOnly = setOf("send", "send message", "kirim", "kirim pesan")
    private val sendSuffixes = listOf("send now", "kirim sekarang")

    fun parse(raw: String): VoiceCommand {
        val text = raw.trim()
        val key = text.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',').trim()
        return when {
            key in stopPhrases -> VoiceCommand.Stop
            key in clearPhrases -> VoiceCommand.Clear
            key in sendOnly -> VoiceCommand.Dictate("", send = true)
            else -> {
                val suffix = sendSuffixes.firstOrNull { key == it || key.endsWith(" $it") }
                if (suffix == null) {
                    VoiceCommand.Dictate(text, send = false)
                } else {
                    val body = text.trimEnd('.', '!', '?', ',').trim().dropLast(suffix.length).trim().trimEnd(',', '.')
                    VoiceCommand.Dictate(body, send = true)
                }
            }
        }
    }
}

/** BCP-47 tag the recogniser understands: the app language, with Android's legacy codes ("in", "iw") modernised. */
internal fun speechLanguageTag(appLanguage: String, deviceTag: String): String {
    val raw = appLanguage.takeUnless { it == "system" || it.isBlank() } ?: deviceTag
    val tag = raw.replace('_', '-')
    val lang = tag.substringBefore('-')
    val modern = when (lang.lowercase(Locale.ROOT)) { "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> lang }
    return modern + tag.removePrefix(lang)
}

/** What a recogniser error means for the person: [R.string] to show, or null when it is not worth a message. */
internal fun voiceErrorMessage(error: Int): Int? = when (error) {
    // Silence or nothing recognised is not worth a message; a cancelled client is our own stop.
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.voice_error_network
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_error_language
    else -> R.string.voice_error
}

/**
 * Speech-to-text for the composer: partial words while speaking, the final sentence when the speaker pauses. It keeps
 * listening after each sentence (until [stop], a command that ends dictation, silence, or an error), so a long prompt
 * is one tap instead of one tap per sentence.
 */
@Stable
internal class VoiceController(
    private val context: Context,
    private val languageTag: () -> String,
    private val onPartial: State<(String) -> Unit>,
    /** Gets each finished sentence; return true to keep listening for the next one. */
    private val onFinal: State<(String) -> Boolean>,
) {
    var listening by mutableStateOf(false)
        private set

    /** Loudness of the voice, 0..1, smoothed; drives the mic's pulse. */
    var level by mutableFloatStateOf(0f)
        private set
    private var wantListening = false
    private val handler = Handler(Looper.getMainLooper())
    internal var requestPermission: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            // Typical range is about -2 (quiet room) to 10 (loud speech).
            level = level * 0.6f + ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) * 0.4f
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { onPartial.value(it) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            level = 0f
            val keepGoing = firstResult(results)?.let { onFinal.value(it) } ?: false
            // A short pause lets the engine release the microphone before it is asked again.
            if (keepGoing && wantListening) handler.postDelayed({ if (wantListening) start() }, RESTART_DELAY_MS)
            else wantListening = false
        }

        override fun onError(error: Int) {
            listening = false
            wantListening = false
            level = 0f
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // A stuck engine stays busy until it is rebuilt; the next tap gets a fresh one.
                runCatching { recognizer?.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("VoiceInput", it) }
                recognizer = null
            }
            voiceErrorMessage(error)?.let(::toast)
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun toast(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    fun toggle() {
        if (listening) {
            stop()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            requestPermission()
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            toast(R.string.voice_unavailable)
            return
        }
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
            // Dictating a prompt means pausing to think: wait a little longer before deciding the sentence is over.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        wantListening = true
        listening = true
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            wantListening = false
            toast(R.string.voice_error)
        }
    }

    /** Ends the recording; the recogniser still delivers what it heard so far. */
    fun stop() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.stopListening() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("VoiceInput", it) }
    }

    fun release() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("VoiceInput", it) }
        recognizer = null
        listening = false
        level = 0f
    }

    private companion object {
        const val RESTART_DELAY_MS = 250L
        const val SILENCE_MS = 1800L
    }
}

/**
 * Creates the [VoiceController] of a composer. [languageCode] is the app language ("system" follows the device);
 * the callbacks always see the latest composer state.
 */
@Composable
internal fun rememberVoiceController(
    languageCode: String,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Boolean,
): VoiceController {
    val context = LocalContext.current
    val partial = rememberUpdatedState(onPartial)
    val finalState = rememberUpdatedState(onFinal)
    val language = rememberUpdatedState(languageCode)
    val controller = remember {
        VoiceController(
            context = context,
            languageTag = { speechLanguageTag(language.value, Locale.getDefault().toLanguageTag()) },
            onPartial = partial,
            onFinal = finalState,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else Toast.makeText(context, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
    }
    controller.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}
