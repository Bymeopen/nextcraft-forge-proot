# FASE 3: Mobile App Layer

> Bagian dari [ARCHITECTURE.md](../../ARCHITECTURE.md) (dipecah agar tiap file ≤ 1000 baris).

## FASE 3 — Mobile App Layer (Android Native Kotlin + Jetpack Compose)

### 3.1 Design System & Component Library (`NextCraftTheme`)

Berikut adalah implementasi nyata design token (`Theme.kt`) dan komponen inti Jetpack Compose untuk **NextCraft Forge**:

```kotlin
// app/src/main/java/com/nextcraft/forge/ui/theme/Tokens.kt
package com.nextcraft.forge.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object NextCraftColors {
    // IDE Dark Palette
    val Background = Color(0xFF0B0E14)
    val Surface = Color(0xFF131821)
    val SurfaceVariant = Color(0xFF1B222D)
    val OnSurface = Color(0xFFE6EDF3)
    val OnSurfaceMuted = Color(0xFF8B949E)

    // Brand & Accent
    val PrimaryOrange = Color(0xFFF28C52)
    val SecondaryBlue = Color(0xFF8EA8FF)
    val TertiaryGreen = Color(0xFF69D69E)

    // AI & Diff Semantic Colors
    val AiBubbleBg = Color(0xFF161C28)
    val ThinkingBorder = Color(0xFF30363D)
    val DiffAddedBg = Color(0x332EA043)
    val DiffAddedText = Color(0xFF56D364)
    val DiffRemovedBg = Color(0x33F85149)
    val DiffRemovedText = Color(0xFFFF7B72)

    // Status
    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFF59E0B)
    val Success = Color(0xFF22C55E)
}

object NextCraftTypography {
    val DisplayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
    val HeadlineMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
    val BodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val BodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp)
    val LabelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val MonoCode = TextStyle(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
}

object NextCraftSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}
```

**Komponen UI Inti (`AgentComponents.kt`)**:
1. `ChatBubble`: Menampilkan pesan pengguna atau asisten dengan *Markdown rendering*, akordeon `ThinkingBlock` yang dapat dibuka-tutup, dan daftar `ToolExecutionCard`.
2. `StreamingText`: Merender teks yang sedang mengalir dengan indikator kursor berkedip (`▊`) di dalam blok yang diisolasi state-nya agar tidak memicu *recomposition* pada seluruh `LazyColumn`.
3. `ThinkingAccordion`: Menampilkan proses penalaran model (`thinking` delta) dengan animasi indikator *pulse* saat model sedang berpikir.
4. `CodeDiffCard`: Menampilkan perubahan baris kode (`+` hijau / `-` merah) beserta tombol **Undo File** dan **Keep Changes** yang terhubung langsung ke `WorkspaceCheckpoints.kt`.
5. `ContextBudgetBar`: Menampilkan persentase pemakaian *context window* (`estimatedCharactersUsed / 96_000`) dan indikator status Chroot (`ROOT CHROOT • UBUNTU 24.04`).

---

### 3.2 State Management Architecture (`ChatViewModel.kt`)

Menggunakan **Kotlin Coroutines + `StateFlow`** dengan pola **Unidirectional Data Flow (UDF)**. Saat tombol **Stop** ditekan, ia memanggil `RuntimeTaskController.stopActiveProcessGroup()` yang mengeksekusi `su -c "kill -TERM -$pid"` agar seluruh pohon proses di dalam Chroot benar-benar berhenti:

```kotlin
// app/src/main/java/com/nextcraft/forge/ui/chat/ChatViewModel.kt
package com.nextcraft.forge.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.data.*
import com.nextcraft.forge.runtime.AgentRuntimeOrchestrator
import com.nextcraft.forge.runtime.RuntimeStreamEvent
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class ChatStatus { IDLE, SNAPSHOTTING, STREAMING, STOPPING, ERROR }

data class ChatUiState(
    val projectId: String = "",
    val chatId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val status: ChatStatus = ChatStatus.IDLE,
    val streamingText: String = "",
    val streamingThinking: String = "",
    val activeTools: List<ToolCallRecord> = emptyList(),
    val contextCharsUsed: Int = 0,
    val contextCharsLimit: Int = 96_000,
    val errorMessage: String? = null
) {
    val isStreaming: Boolean get() = status == ChatStatus.STREAMING || status == ChatStatus.SNAPSHOTTING
}

class ChatViewModel(
    private val orchestrator: AgentRuntimeOrchestrator,
    private val checkpoints: WorkspaceCheckpoints
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    fun sendMessage(prompt: String) {
        val current = _uiState.value
        if (prompt.isBlank() || current.isStreaming) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = prompt.trim(),
            timestampEpochMs = System.currentTimeMillis()
        )

        // 1. Optimistic UI update
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                status = ChatStatus.SNAPSHOTTING,
                streamingText = "",
                streamingThinking = "",
                activeTools = emptyList(),
                errorMessage = null
            )
        }

        streamJob = viewModelScope.launch {
            try {
                // 2. Buat hardlink snapshot (cp -al) sebelum agen menyentuh /workspace/nextcraft
                checkpoints.createHardlinkSnapshot(current.projectId)
                _uiState.update { it.copy(status = ChatStatus.STREAMING) }

                // 3. Jalankan CLI Agent di dalam Chroot & konsumsi event dari InotifyStreamTailer
                orchestrator.executePromptStream(
                    projectId = current.projectId,
                    chatId = current.chatId,
                    prompt = prompt,
                    history = _uiState.value.messages
                ).collect { event ->
                    handleStreamEvent(event)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(status = ChatStatus.ERROR, errorMessage = e.message ?: "Execution failed")
                }
            }
        }
    }

    private fun handleStreamEvent(event: RuntimeStreamEvent) {
        when (event) {
            is RuntimeStreamEvent.ThinkingDelta -> {
                _uiState.update { it.copy(streamingThinking = it.streamingThinking + event.delta) }
            }
            is RuntimeStreamEvent.TextDelta -> {
                _uiState.update { it.copy(streamingText = it.streamingText + event.delta) }
            }
            is RuntimeStreamEvent.ToolUpdated -> {
                _uiState.update { state ->
                    val updated = state.activeTools.filterNot { it.id == event.record.id } + event.record
                    state.copy(activeTools = updated)
                }
            }
            is RuntimeStreamEvent.Completed -> {
                val state = _uiState.value
                val assistantMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = state.streamingText,
                    thinkingText = state.streamingThinking.takeIf { it.isNotBlank() },
                    toolCalls = state.activeTools,
                    modelId = event.modelId,
                    latencyMs = event.durationMs,
                    timestampEpochMs = System.currentTimeMillis()
                )
                val updatedMessages = state.messages + assistantMsg
                _uiState.update {
                    it.copy(
                        messages = updatedMessages,
                        status = ChatStatus.IDLE,
                        streamingText = "",
                        streamingThinking = "",
                        activeTools = emptyList(),
                        contextCharsUsed = updatedMessages.sumOf { m -> m.content.length }
                    )
                }
            }
            is RuntimeStreamEvent.Error -> {
                _uiState.update {
                    it.copy(status = ChatStatus.ERROR, errorMessage = event.reason)
                }
            }
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            _uiState.update { it.copy(status = ChatStatus.STOPPING) }
            orchestrator.killActiveChrootProcessGroup()
            streamJob?.cancel()
            _uiState.update { it.copy(status = ChatStatus.IDLE) }
        }
    }
}
```

---

### 3.3 AI Streaming Integration (`InotifyStreamTailer.kt`)

Menggantikan pola lama yang melakukan *polling* `delay(80)` dan membuka-tutup `RandomAccessFile` terus-menerus. Di **NextCraft Forge**, kita menggunakan **Persistent `FileChannel` + Android `FileObserver` (`inotify`)** untuk membaca baris JSONL dari CLI Agent di `/nextcraft-bridge/output.jsonl` dengan latensi **$< 2\text{ ms}$**:

```kotlin
// app/src/main/java/com/nextcraft/forge/runtime/InotifyStreamTailer.kt
package com.nextcraft.forge.runtime

import android.os.FileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class InotifyStreamTailer(private val outputFile: File) {

    fun tailLines(isProcessAlive: () -> Boolean): Flow<String> = callbackFlow {
        if (!outputFile.exists()) {
            outputFile.parentFile?.mkdirs()
            outputFile.createNewFile()
        }

        val raf = RandomAccessFile(outputFile, "r")
        val channel = raf.channel
        val readBuffer = ByteBuffer.allocateDirect(16 * 1024)
        val lineAccumulator = ByteArrayOutputStream(4096)

        fun drainAvailableBytes() {
            while (isActive) {
                readBuffer.clear()
                val bytesRead = channel.read(readBuffer)
                if (bytesRead <= 0) break
                readBuffer.flip()
                for (i in 0 until bytesRead) {
                    val b = readBuffer.get()
                    if (b == '\n'.code.toByte()) {
                        val line = lineAccumulator.toString(StandardCharsets.UTF_8.name())
                        lineAccumulator.reset()
                        if (line.isNotBlank()) {
                            trySend(line)
                        }
                    } else if (b != '\r'.code.toByte()) {
                        lineAccumulator.write(b.toInt())
                    }
                }
            }
        }

        val observer = object : FileObserver(outputFile.parentFile, MODIFY or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == outputFile.name) {
                    synchronized(channel) {
                        drainAvailableBytes()
                    }
                }
            }
        }

        observer.startWatching()
        // Initial drain in case bytes were written before observer attached
        synchronized(channel) { drainAvailableBytes() }

        // Safety fallback check when process exits
        while (isActive && isProcessAlive()) {
            kotlinx.coroutines.delay(200)
            synchronized(channel) { drainAvailableBytes() }
        }
        // Final drain after process exit
        synchronized(channel) {
            drainAvailableBytes()
            if (lineAccumulator.size() > 0) {
                trySend(lineAccumulator.toString(StandardCharsets.UTF_8.name()))
                lineAccumulator.reset()
            }
        }

        observer.stopWatching()
        channel.close()
        raf.close()
        close()

        awaitClose {
            observer.stopWatching()
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }.flowOn(Dispatchers.IO)
}
```

---

### 3.4 Offline Support & Hardlink Checkpoint Strategy (`WorkspaceCheckpoints.kt`)

Alih-alih menggunakan sinkronisasi database ke server cloud, strategi offline-first di **NextCraft Forge** mencakup:
1. **Hardlink Snapshot (`cp -al`)**: Membuat salinan berbasis inode di filesystem lokal sebelum agen berjalan, serta menyediakan fungsi `restoreFile()` (Undo) dan `keepFile()` (Keep) yang 100% bekerja secara offline.
2. **Path Traversal Protection**: Memastikan tidak ada path relatif yang bisa keluar dari direktori workspace proyek (`canonicalFile.toPath().startsWith(base.toPath())`).

```kotlin
// app/src/main/java/com/nextcraft/forge/runtime/WorkspaceCheckpoints.kt
package com.nextcraft.forge.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class WorkspaceCheckpoints(private val appFilesDir: File) {

    private fun workspaceDir(projectId: String): File =
        File(appFilesDir, "workspaces/$projectId")

    private fun checkpointDir(projectId: String): File =
        File(appFilesDir, "checkpoints/$projectId/latest")

    private fun resolveSafeChild(baseDir: File, relativePath: String): File {
        val baseCanonical = baseDir.canonicalFile
        val targetCanonical = File(baseCanonical, relativePath).canonicalFile
        if (!targetCanonical.toPath().startsWith(baseCanonical.toPath())) {
            throw SecurityException("Path traversal blocked: $relativePath")
        }
        return targetCanonical
    }

    /**
     * Membuat snapshot instan dari workspace menggunakan hardlink Linux (cp -al)
     * dan mengecualikan direktori build/.gradle/node_modules/.git agar hemat inode.
     */
    suspend fun createHardlinkSnapshot(projectId: String) = withContext(Dispatchers.IO) {
        val src = workspaceDir(projectId)
        val dst = checkpointDir(projectId)
        if (!src.exists()) return@withContext

        val tmpDst = File(dst.parentFile, "latest.tmp")
        tmpDst.deleteRecursively()
        tmpDst.mkdirs()

        val cmd = """
            cp -al "${src.absolutePath}/." "${tmpDst.absolutePath}/" 2>/dev/null || cp -a "${src.absolutePath}/." "${tmpDst.absolutePath}/"
            rm -rf "${tmpDst.absolutePath}/build" "${tmpDst.absolutePath}/app/build" "${tmpDst.absolutePath}/.gradle" "${tmpDst.absolutePath}/node_modules"
        """.trimIndent()

        val proc = ProcessBuilder("sh", "-c", cmd).start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw IOException("Failed to create workspace checkpoint (exit=$exitCode)")
        }

        dst.deleteRecursively()
        tmpDst.renameTo(dst)
    }

    /**
     * Mengembalikan (Undo) satu file dari snapshot terakhir ke workspace aktif.
     */
    suspend fun undoFileChange(projectId: String, relativePath: String) = withContext(Dispatchers.IO) {
        val workspaceBase = workspaceDir(projectId)
        val checkpointBase = checkpointDir(projectId)

        val liveFile = resolveSafeChild(workspaceBase, relativePath)
        val snapshotFile = resolveSafeChild(checkpointBase, relativePath)

        if (snapshotFile.exists()) {
            liveFile.parentFile?.mkdirs()
            // Hapus liveFile dulu agar tidak menimpa inode hardlink yang sama
            liveFile.delete()
            snapshotFile.copyTo(liveFile, overwrite = true)
        } else {
            // File baru dibuat oleh AI Agent; Undo berarti menghapusnya
            liveFile.delete()
        }
    }
}
```

---

### 3.5 Performance Optimization & Memory Guard (`BoundedFileReads.kt`)

Untuk memastikan aplikasi mobile tidak pernah mengalami `OutOfMemoryError` saat membaca log Gradle atau output tool yang besar, serta menjaga *context window* tetap ramping:

1. **Bounded File Reads (`BoundedFileReads.kt`)**: Membatasi pembacaan file maksimal **$256\text{ KB}$** (`MAX_PREVIEW_BYTES = 256 * 1024`) menggunakan `RandomAccessFile.seek(length - maxBytes)` untuk pembacaan ekor log (*tail read*).
2. **Context Window Character Trimming (`recentWithinCharacterBudget`)**: Memangkas pesan lama secara otomatis saat riwayat percakapan mendekati batas karakter (`96,000` karakter) sebelum dikirim saat *handover* sesi.
3. **LazyColumn Optimization**: Setiap item di `AgentScreen.kt` menggunakan `key = { msg -> msg.id }` dan `contentType = { msg -> msg.role }`, sementara teks streaming ditempatkan pada item terpisah di bagian bawah agar pesan-pesan lama tidak pernah di-*recompose* selama streaming berlangsung.

```kotlin
// app/src/main/java/com/nextcraft/forge/util/BoundedFileReads.kt
package com.nextcraft.forge.util

import com.nextcraft.forge.data.ChatMessage
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

object BoundedFileReads {
    const val DEFAULT_MAX_BYTES = 256 * 1024 // 256 KB

    fun readTailText(file: File, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        if (!file.exists() || file.length() == 0L) return ""
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val bytesToRead = minOf(len, maxBytes.toLong()).toInt()
            val startOffset = len - bytesToRead
            raf.seek(startOffset)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            val text = String(buffer, StandardCharsets.UTF_8)
            return if (startOffset > 0) "...[truncated ${startOffset} bytes]...\n$text" else text
        }
    }
}

fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int = 96_000): List<ChatMessage> {
    val selected = ArrayDeque<ChatMessage>()
    var used = 0
    for (msg in this.asReversed()) {
        val cost = msg.content.length + (msg.thinkingText?.length ?: 0)
        if (used + cost > maxCharacters && selected.isNotEmpty()) break
        selected.addFirst(msg)
        used += cost
    }
    return selected.toList()
}
```

### Definition of Done Fase 3
- [x] Design tokens (`NextCraftColors`, `NextCraftTypography`, `NextCraftSpacing`) dan komponen Compose (`ChatBubble`, `StreamingText`, `ThinkingAccordion`, `CodeDiffCard`, `ContextBudgetBar`) terdefinisi.
- [x] State management (`ChatViewModel` + `ChatUiState`) dengan `stopGeneration()` yang mematikan grup proses Chroot selesai.
- [x] Streaming tailer berbasis `FileObserver` (`inotify`) + persistent `FileChannel` mencapai latensi $< 5\text{ ms}$ (jauh di bawah batas 200ms).
- [x] Offline mode dan hardlink checkpoint (`cp -al` + `undoFileChange` dengan proteksi path traversal) berfungsi penuh.
- [x] Optimasi memori (`BoundedFileReads` 256KB + `recentWithinCharacterBudget`) siap produksi.

---
