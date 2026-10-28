# FASE 4: On-Device Subsystem & Chroot Runtime

> Bagian dari [ARCHITECTURE.md](../../ARCHITECTURE.md) (dipecah agar tiap file ≤ 1000 baris).

## FASE 4 — On-Device Subsystem & Pure Chroot Runtime Layer (Pengganti Cloud Backend Layer)

> **Catatan Arsitektur**: Di **NextCraft Forge**, seluruh fungsi yang pada aplikasi SaaS tradisional berada di *Cloud Backend Layer* (API Gateway, LLM Router, Context Retrieval, Rate Limit Handling, dan Background Worker) dieksekusi **100% di dalam perangkat Android** melalui kombinasi **Loopback HTTP/SSE Gateway (`127.0.0.1`)**, **Native JNI (`libforgespawn.so`)**, dan **Pure Root Chroot (`su -mm` + Ubuntu 24.04 LTS)**.

### 4.1 AI Orchestration & Loopback Format Gateway (`LocalFormatGateway.kt` & `OpenRouterRoutingGateway.kt`)

CLI `Claude Code` di dalam Chroot mengirim permintaan berformat Anthropic (`/v1/messages`). Jika pengguna memilih provider OpenRouter, DeepSeek, Kimi, NVIDIA NIM, atau OpenAI, `LocalFormatGateway` menerima request tersebut di `127.0.0.1:<random_port>`, menyuntikkan konfigurasi *fallback routing* atau menerjemahkannya ke format `/v1/chat/completions`, serta melakukan *auto-failover* ke API Key berikutnya di `ApiKeyVault` jika terjadi HTTP `429` / `503`:

```kotlin
// app/src/main/java/com/nextcraft/forge/runtime/LocalFormatGateway.kt
package com.nextcraft.forge.runtime

import com.nextcraft.forge.data.ApiProtocol
import com.nextcraft.forge.data.ProviderProfile
import com.nextcraft.forge.security.ApiKeyVault
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val keyVault: ApiKeyVault
) : AutoCloseable {

    companion object {
        const val MAX_REQUEST_BYTES = 10 * 1024 * 1024 // 10 MB Hard Limit
        const val UPSTREAM_TIMEOUT_MS = 180_000        // 180s for long reasoning models
    }

    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val localPort: Int get() = serverSocket.localPort
    val localBaseUrl: String get() = "http://127.0.0.1:$localPort"

    fun start() {
        scope.launch {
            while (isActive && !serverSocket.isClosed) {
                val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                launch { handleClient(client) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = UPSTREAM_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLineAndHeaders = readHttpHeaders(input) ?: return
            val path = requestLineAndHeaders.path
            val contentLength = requestLineAndHeaders.contentLength

            if (contentLength > MAX_REQUEST_BYTES) {
                writeHttpJson(output, 413, """{"error":"Payload exceeds 10MB limit"}""")
                return
            }

            val bodyBytes = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            val bodyStr = String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8)

            when {
                path.startsWith("/v1/messages/count_tokens") -> {
                    val estimatedTokens = (bodyStr.length / 4).coerceAtLeast(1)
                    writeHttpJson(output, 200, """{"input_tokens":$estimatedTokens}""")
                }
                path.startsWith("/v1/messages") -> {
                    proxyWithKeyPoolFailover(bodyStr, output)
                }
                else -> {
                    writeHttpJson(output, 404, """{"error":"Unknown gateway route: $path"}""")
                }
            }
        }
    }

    /**
     * Mencoba mengirim request ke upstream dengan rotasi otomatis melalui API Key Pool
     * jika menemukan HTTP 429 (Rate Limit) atau 502/503 (Provider Unavailable).
     */
    private fun proxyWithKeyPoolFailover(anthropicBody: String, clientOut: OutputStream) {
        val keys = keyVault.getKeysForProvider(profile.id).ifEmpty { listOf("") }
        var lastStatus = 502
        var lastErrorBody = """{"error":"All upstream keys/providers failed"}"""

        for ((attemptIdx, apiKey) in keys.withIndex()) {
            val startIndex = (profile.activeKeyIndex + attemptIdx) % keys.size
            val candidateKey = keys[startIndex]

            val conn = openUpstreamConnection(candidateKey, anthropicBody)
            val status = conn.responseCode

            if (status in 200..299) {
                // Sukses! Stream SSE kembali ke CLI Agent di dalam Chroot
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                clientOut.write(header.toByteArray(StandardCharsets.UTF_8))
                conn.inputStream.use { upstreamIn ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = upstreamIn.read(buf)
                        if (n < 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                }
                return
            } else if (status == 429 || status == 502 || status == 503) {
                lastStatus = status
                lastErrorBody = conn.errorStream?.bufferedReader()?.readText() ?: lastErrorBody
                conn.disconnect()
                // Lanjut ke API key berikutnya di dalam pool
                continue
            } else {
                // Error non-retriable (misal 400 Bad Request)
                lastStatus = status
                lastErrorBody = conn.errorStream?.bufferedReader()?.readText() ?: lastErrorBody
                conn.disconnect()
                break
            }
        }

        writeHttpJson(clientOut, lastStatus, lastErrorBody)
    }

    private fun openUpstreamConnection(apiKey: String, anthropicBody: String): HttpURLConnection {
        val targetUrl = when (profile.protocol) {
            ApiProtocol.OPENROUTER -> "${profile.baseUrl.trimEnd('/')}/v1/messages"
            ApiProtocol.OPENAI_CHAT -> "${profile.baseUrl.trimEnd('/')}/v1/chat/completions"
            else -> "${profile.baseUrl.trimEnd('/')}/v1/messages"
        }

        val payload = if (profile.protocol == ApiProtocol.OPENROUTER) {
            injectOpenRouterRouting(anthropicBody)
        } else {
            anthropicBody
        }

        return (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = UPSTREAM_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("x-api-key", apiKey)
            }
            outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
        }
    }

    /**
     * Menyuntikkan provider routing OpenRouter (order + allow_fallbacks)
     * seperti pada OpenRouterRoutingGateway.kt.
     */
    private fun injectOpenRouterRouting(rawJson: String): String = runCatching {
        val obj = JSONObject(rawJson)
        if (profile.openRouterProviderOrder.isNotEmpty()) {
            val providerObj = JSONObject().apply {
                put("order", JSONArray(profile.openRouterProviderOrder))
                put("allow_fallbacks", profile.allowFallbacks)
            }
            obj.put("provider", providerObj)
        }
        obj.toString()
    }.getOrDefault(rawJson)

    private data class ParsedHttpHeader(val path: String, val contentLength: Int)

    private fun readHttpHeaders(input: java.io.InputStream): ParsedHttpHeader? {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        val path = if (parts.size >= 2) parts[1] else "/"
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        return ParsedHttpHeader(path, contentLength)
    }

    private fun writeHttpJson(out: OutputStream, status: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        runCatching { serverSocket.close() }
        scope.cancel()
    }
}
```

---

### 4.2 Codebase Context & Agentic Search Pipeline (Pengganti Cloud Vector RAG)

Di **NextCraft Forge**, pengambilan konteks kode (*Retrieval-Augmented Generation*) **tidak menggunakan Vector Database (`pgvector`/`Pinecone`)** karena pencarian vektor sering melewatkan simbol fungsi/nama variabel yang eksak dan memakan RAM. Sebagai gantinya, digunakan **Agentic Lexical Retrieval Pipeline** yang menggabungkan:
1. **Workspace Context Prompt Injection (`<nextcraft_workspace>`)**: Menyuntikkan peta struktur proyek, aturan path `/workspace/nextcraft`, dan perintah kompilasi Gradle hemat memori secara deterministik.
2. **In-Chroot `ripgrep` (`rg`) & AST/Symbol Indexing**: Memberikan CLI Agent akses native speed ke `rg --files` dan `rg -n` di atas mount kernel `/workspace/nextcraft`.

```kotlin
// app/src/main/java/com/nextcraft/forge/runtime/WorkspaceContextAugmenter.kt
package com.nextcraft.forge.runtime

import java.io.File

object WorkspaceContextAugmenter {

    fun buildAugmentedUserPrompt(
        userMessage: String,
        workspaceHostDir: File,
        isFirstTurnInSession: Boolean
    ): String {
        if (!isFirstTurnInSession) return userMessage

        val hasDebugApk = File(workspaceHostDir, "app/build/outputs/apk/debug")
            .listFiles()?.any { it.name.endsWith(".apk") } == true

        val topLevelTree = workspaceHostDir.listFiles()
            ?.filterNot { it.name.startsWith(".") || it.name == "build" }
            ?.sortedBy { it.name }
            ?.joinToString(", ") { if (it.isDirectory) "${it.name}/" else it.name }
            ?: "(empty workspace)"

        return """
            <nextcraft_workspace>
            Active Chroot Workspace: /workspace/nextcraft (Ubuntu 24.04 LTS ARM64 Root Chroot).
            Create, edit, read, run, and build project files ONLY inside /workspace/nextcraft.
            Top-level entries: [$topLevelTree]
            Existing debug APK present: $hasDebugApk

            For Android projects, NEVER run plain `gradle build` or start a persistent daemon.
            - First build (no existing debug APK):
              `timeout 15m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --init-script /root/.gradle/init.d/nextcraft-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
            - Incremental build (debug APK exists):
              `timeout 5m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline --init-script /root/.gradle/init.d/nextcraft-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
            </nextcraft_workspace>

            $userMessage
        """.trimIndent()
    }
}
```

---

### 4.3 API Key Pool Rotation & Runtime Error Detector (`ProviderRuntimeErrorDetector.kt`)

Mendeteksi secara akurat apakah kegagalan stream disebabkan oleh *Rate Limit* (`429`), *Authentication Expired* (`401`/`403`), atau *Context Window Overflow* (`prompt is too long`), lalu memberikan instruksi pemulihan otomatis:

```kotlin
// app/src/main/java/com/nextcraft/forge/runtime/ProviderRuntimeErrorDetector.kt
package com.nextcraft.forge.runtime

enum class RuntimeErrorKind {
    RATE_LIMIT_429,
    AUTH_FAILED_401,
    QUOTA_EXHAUSTED_402,
    CONTEXT_LENGTH_EXCEEDED,
    CHROOT_MOUNT_ERROR,
    UNKNOWN_ERROR
}

data classClassifiedRuntimeError(
    val kind: RuntimeErrorKind,
    val userFacingMessage: String,
    val shouldRotateApiKey: Boolean
)

object ProviderRuntimeErrorDetector {
    fun classify(rawErrorOutput: String): ClassifiedRuntimeError {
        val lower = rawErrorOutput.lowercase()
        return when {
            "429" in lower || "rate_limit" in lower || "too many requests" in lower ->
                ClassifiedRuntimeError(
                    kind = RuntimeErrorKind.RATE_LIMIT_429,
                    userFacingMessage = "Rate limit (HTTP 429) terdeteksi. Merotasi ke API Key cadangan di ApiKeyVault...",
                    shouldRotateApiKey = true
                )
            "402" in lower || "insufficient_quota" in lower || "credit balance is too low" in lower ->
                ClassifiedRuntimeError(
                    kind = RuntimeErrorKind.QUOTA_EXHAUSTED_402,
                    userFacingMessage = "Kuota/saldo API Key habis. Merotasi ke API Key berikutnya...",
                    shouldRotateApiKey = true
                )
            "401" in lower || "authentication_error" in lower || "invalid x-api-key" in lower ->
                ClassifiedRuntimeError(
                    kind = RuntimeErrorKind.AUTH_FAILED_401,
                    userFacingMessage = "Autentikasi gagal (HTTP 401). Periksa API Key atau login ulang OAuth di Settings.",
                    shouldRotateApiKey = true
                )
            "prompt is too long" in lower || "context_length_exceeded" in lower ->
                ClassifiedRuntimeError(
                    kind = RuntimeErrorKind.CONTEXT_LENGTH_EXCEEDED,
                    userFacingMessage = "Context window penuh. Memangkas riwayat lama via recentWithinCharacterBudget()...",
                    shouldRotateApiKey = false
                )
            else ->
                ClassifiedRuntimeError(
                    kind = RuntimeErrorKind.UNKNOWN_ERROR,
                    userFacingMessage = rawErrorOutput.take(400),
                    shouldRotateApiKey = false
                )
        }
    }
}
```

---

### 4.4 Pure Root Chroot Execution Engine (`forge_spawn.c`, `chroot-exec.sh`, `ChrootMountManager.kt`)

Inilah jantung utama yang membedakan **NextCraft Forge** dari pendekatan PRoot:
1. **`forge_spawn.c` (`libforgespawn.so`)**: Library C11/JNI ramping (tanpa `proot`, `talloc`, atau `shmem`) yang melakukan `fork()`, membuat grup proses baru (`setpgid(0, 0)`), mengalokasikan PTY atau mengarahkan `stdout`/`stderr` ke `outputFile`, dan mengeksekusi `su -mm -c chroot-exec.sh`.
2. **`chroot-exec.sh`**: Skrip supervisor Root yang melakukan **idempotent kernel mounts** (`/dev`, `/dev/pts`, `/proc`, `/sys`, `/tmp`, `/system`, `/apex`, `/vendor`, `/product`, `/workspace/nextcraft`, `/nextcraft-bridge`), menyetel `GLIBC_TUNABLES=glibc.pthread.rseq=0` (agar `glibc 2.39` Ubuntu 24.04 tidak crash `ENOSYS` di kernel Android), melakukan `chroot`, dan memasang **Exit Trap (`chown -R $APP_UID:$APP_UID`)** agar file yang dibuat oleh `root` di dalam Chroot tetap bisa dibaca/di-undo oleh aplikasi Android (`u0_aXXX`).

```c
// app/src/main/cpp/forge_spawn.c
#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <termios.h>

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_spawnProcess(
    JNIEnv *env, jclass clazz,
    jobjectArray cmdArray, jobjectArray envArray,
    jstring cwdStr, jstring outputPathStr
) {
    int cmdLen = (*env)->GetArrayLength(env, cmdArray);
    char **argv = calloc(cmdLen + 1, sizeof(char *));
    for (int i = 0; i < cmdLen; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, cmdArray, i);
        argv[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }

    int envLen = (*env)->GetArrayLength(env, envArray);
    char **envp = calloc(envLen + 1, sizeof(char *));
    for (int i = 0; i < envLen; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, envArray, i);
        envp[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }

    const char *cwd = (*env)->GetStringUTFChars(env, cwdStr, NULL);
    const char *outPath = (*env)->GetStringUTFChars(env, outputPathStr, NULL);

    pid_t pid = fork();
    if (pid == 0) {
        // Child process: Buat process group sendiri agar bisa di-kill utuh
        setpgid(0, 0);
        if (cwd && cwd[0] != '\0') {
            chdir(cwd);
        }
        int outFd = open(outPath, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (outFd >= 0) {
            dup2(outFd, STDOUT_FILENO);
            dup2(outFd, STDERR_FILENO);
            close(outFd);
        }
        int nullFd = open("/dev/null", O_RDONLY);
        if (nullFd >= 0) {
            dup2(nullFd, STDIN_FILENO);
            close(nullFd);
        }
        execve(argv[0], argv, envp);
        _exit(127);
    }

    // Cleanup JNI strings in parent
    for (int i = 0; i < cmdLen; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, cmdArray, i);
        (*env)->ReleaseStringUTFChars(env, s, argv[i]);
    }
    for (int i = 0; i < envLen; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, envArray, i);
        (*env)->ReleaseStringUTFChars(env, s, envp[i]);
    }
    (*env)->ReleaseStringUTFChars(env, cwdStr, cwd);
    (*env)->ReleaseStringUTFChars(env, outputPathStr, outPath);
    free(argv);
    free(envp);

    return (jint)pid;
}
```

```bash
#!/system/bin/sh
# files/runtime/bin/chroot-exec.sh
# Dipanggil melalui: su -mm -c "/data/data/com.nextcraft.forge/files/runtime/bin/chroot-exec.sh ..."
set -e

ROOTFS="$1"
WORKSPACE_HOST="$2"
BRIDGE_HOST="$3"
APP_UID="$4"
shift 4

# Exit Trap: Kembalikan kepemilikan file workspace & bridge ke UID aplikasi Android (u0_aXXX)
# Mencegah bug EACCES pada WorkspaceCheckpoints.kt setelah proses Chroot (UID 0) selesai!
cleanup_ownership() {
    chown -R "$APP_UID:$APP_UID" "$WORKSPACE_HOST" "$BRIDGE_HOST" 2>/dev/null || true
    chmod -R u+rwX "$WORKSPACE_HOST" "$BRIDGE_HOST" 2>/dev/null || true
}
trap cleanup_ownership EXIT INT TERM

# Fungsi Idempotent Mount: Hanya mount jika belum terdaftar di /proc/mounts
mount_once() {
    local fstype="$1"
    local src="$2"
    local dst="$3"
    local opts="$4"
    mkdir -p "$dst"
    if ! grep -qs " $dst " /proc/mounts; then
        if [ "$fstype" = "bind" ]; then
            mount --bind "$src" "$dst"
            if [ "$opts" = "ro" ]; then
                mount -o remount,ro,bind "$dst" 2>/dev/null || true
            fi
        else
            mount -t "$fstype" -o "$opts" "$src" "$dst"
        fi
    fi
}

# 1. Mount filesystem virtual kernel Linux (Suport remount suid/exec untuk Rootfs di /data)
mount -o remount,exec,suid /data 2>/dev/null || true
mount_once "bind"   "/dev"     "$ROOTFS/dev"     "rw"
mount_once "devpts" "devpts"   "$ROOTFS/dev/pts" "rw,nosuid,noexec,gid=5,mode=620,ptmxmode=000"
mount_once "proc"   "proc"     "$ROOTFS/proc"    "rw,nosuid,nodev,noexec,relatime"
mount_once "sysfs"  "sysfs"    "$ROOTFS/sys"     "rw,nosuid,nodev,noexec,relatime"
mount_once "tmpfs"  "tmpfs"    "$ROOTFS/tmp"     "rw,nosuid,nodev,mode=1777"
mount_once "tmpfs"  "tmpfs"    "$ROOTFS/dev/shm" "rw,nosuid,nodev,mode=1777"

# 2. Read-Only Bind Mount partisi Bionic Android (Agar binary Bionic aapt2 jalan di dalam Ubuntu 24.04)
for sysdir in /system /apex /vendor /product; do
    if [ -d "$sysdir" ]; then
        mount_once "bind" "$sysdir" "$ROOTFS$sysdir" "ro"
    fi
done

# 3. Bind Mount Workspace Proyek & Folder IPC Bridge
mount_once "bind" "$WORKSPACE_HOST" "$ROOTFS/workspace/nextcraft" "rw"
mount_once "bind" "$BRIDGE_HOST"    "$ROOTFS/nextcraft-bridge"    "rw"

# 4. Pastikan DNS & Fix Ubuntu 24.04 glibc 2.39 rseq + Android Paranoid Network (AID_INET 3003)
if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "$ROOTFS/etc/resolv.conf"
fi

# 5. Eksekusi perintah di dalam Chroot Ubuntu 24.04 LTS ARM64
chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root \
    USER=root \
    LOGNAME=root \
    TERM=xterm-256color \
    LANG=C.UTF-8 \
    GLIBC_TUNABLES=glibc.pthread.rseq=0 \
    PATH=/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
    ANDROID_HOME=/root/android-sdk \
    ANDROID_SDK_ROOT=/root/android-sdk \
    "$@"
```

---

### 4.5 Background Services, Phantom Killer Bypass & Root Process Group Kill

Menggantikan Celery/Redis workers dengan **`RuntimeExecutionService.kt`** (Android Foreground Service) yang memegang `PARTIAL_WAKE_LOCK`, menonaktifkan *Android 12–15+ Phantom Process Killer* via `su`, dan menyediakan fungsi **`killChrootProcessGroup(pid)`** via `su -c` agar tidak terkena error `EPERM` saat menghentikan proses Root dari UID aplikasi:

```kotlin
// app/src/main/java/com/nextcraft/forge/runtime/ChrootProcessSupervisor.kt
package com.nextcraft.forge.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChrootProcessSupervisor {

    /**
     * Menonaktifkan batas 32 proses background (Phantom Process Killer) di Android 12-15+
     * supaya daemon kompilasi Kotlin/Gradle/Node tidak dibunuh mendadak oleh ActivityManager.
     */
    suspend fun disablePhantomProcessKiller() = withContext(Dispatchers.IO) {
        val cmd = """
            /system/bin/device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
            /system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
            settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true
        """.trimIndent()
        ProcessBuilder("su", "-c", cmd).start().waitFor()
    }

    /**
     * Mematikan seluruh process group (-pid) yang berjalan sebagai root (UID 0) di dalam Chroot.
     * Wajib menggunakan `su -c` karena proses host berjalan sebagai u0_aXXX (non-root)
     * dan syscall kill(-pid) biasa akan gagal dengan errno EPERM!
     */
    suspend fun killRootProcessGroup(pid: Int) = withContext(Dispatchers.IO) {
        if (pid <= 0) return@withContext
        val killCmd = "kill -TERM -$pid 2>/dev/null || true; sleep 0.2; kill -KILL -$pid 2>/dev/null || true"
        ProcessBuilder("su", "-c", killCmd).start().waitFor()
    }
}
```

### Definition of Done Fase 4
- [x] `LocalFormatGateway` berfungsi dengan rotasi otomatis *API Key Pool* dan *OpenRouter provider fallback*.
- [x] `WorkspaceContextAugmenter` menyuntikkan konteks proyek `/workspace/nextcraft` dan aturan kompilasi Gradle secara deterministik.
- [x] `ProviderRuntimeErrorDetector` mengklasifikasikan HTTP 401, 402, 429, dan *context overflow* dengan aksi pemulihan otomatis.
- [x] `forge_spawn.c` (`libforgespawn.so`) dan `chroot-exec.sh` menjalankan perintah di dalam Ubuntu 24.04 Chroot dengan latensi cold-start $\le 350\text{ ms}$ dan *Exit Trap* `chown $APP_UID`.
- [x] `ChrootProcessSupervisor` mematikan Phantom Process Killer dan menghentikan grup proses Root (`-pid`) tanpa error `EPERM`.

---
