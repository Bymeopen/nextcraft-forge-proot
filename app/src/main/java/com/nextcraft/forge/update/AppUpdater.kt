package com.nextcraft.forge.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.stringOrEmpty
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

class NoPublishedReleaseException : IOException("No app release has been published yet.")

class AppUpdater(
    private val context: Context,
    private val manifestUrlOverride: String = "",
) {
    fun check(): AppUpdateInfo? {
        val targetManifestUrl = manifestUrlOverride.ifBlank { resolveDefaultManifestUrl() }.trim()
        check(targetManifestUrl.startsWith("https://")) { "No HTTPS update manifest URL is configured." }
        val conn = (URL(targetManifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val statusCode = conn.responseCode
            check(conn.url.protocol.equals("https", ignoreCase = true)) {
                "Update manifest redirect to non-HTTPS URL blocked."
            }
            if (statusCode == 404) throw NoPublishedReleaseException()
            if (statusCode !in 200..299) throw IOException("Update manifest request failed (HTTP $statusCode)")
            val payload = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (sb.length < MANIFEST_CHAR_CAP) {
                    val n = reader.read(buf, 0, minOf(buf.size, MANIFEST_CHAR_CAP - sb.length))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            val manifest = JSONObject(payload)
            val declaredPkg = manifest.stringOrEmpty("packageName")
            if (declaredPkg.isNotBlank() && declaredPkg != context.packageName) return null
            val releaseNotes = manifest.stringOrEmpty("notes").trim()
            if (releaseNotes.isNotBlank()) {
                runCatching { AppPreferences(context).latestReleaseNotes = releaseNotes }
            }
            val remoteCode = manifest.optLong("versionCode")
            if (remoteCode <= installedVersionCode()) return null
            val flavor = currentVariantName()
            val entry = manifest.optJSONObject("artifacts")?.optJSONObject(flavor)
                ?: manifest.optJSONObject(flavor)
                ?: manifest
            val downloadEndpoint = entry.stringOrEmpty("url").ifBlank { entry.stringOrEmpty("apkUrl") }.trim()
            if (!downloadEndpoint.startsWith("https://")) return null
            AppUpdateInfo(
                versionCode = remoteCode,
                versionName = manifest.stringOrEmpty("versionName", remoteCode.toString()),
                apkUrl = downloadEndpoint,
                sha256 = entry.stringOrEmpty("sha256").trim().lowercase(),
                sizeBytes = entry.optLong("sizeBytes", -1L),
                notes = manifest.stringOrEmpty("notes"),
            )
        } finally {
            conn.disconnect()
        }
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        require(info.apkUrl.startsWith("https://")) { "Update APK URL must use HTTPS." }
        val flavor = currentVariantName()
        val stagingFolder = File(context.filesDir, "updates").apply { mkdirs() }
        val tempPartFile = File(stagingFolder, "nextcraft-forge-$flavor.apk.part")
        val finalApkFile = File(stagingFolder, "nextcraft-forge-$flavor.apk")
        try {
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                val statusCode = conn.responseCode
                check(conn.url.protocol.equals("https", ignoreCase = true)) {
                    "Update download redirect to non-HTTPS URL blocked."
                }
                check(statusCode in 200..299) { "Update download failed (HTTP $statusCode)." }
                val contentLen = conn.contentLengthLong.takeIf { it > 0L } ?: info.sizeBytes
                val byteCeiling = if (info.sizeBytes in 1L..APK_BYTE_CAP) info.sizeBytes else APK_BYTE_CAP
                check(contentLen <= byteCeiling) { "Update download exceeds the allowed size." }
                conn.inputStream.use { netIn ->
                    tempPartFile.outputStream().use { fileOut ->
                        val chunk = ByteArray(131_072)
                        var received = 0L
                        while (true) {
                            val readCount = netIn.read(chunk)
                            if (readCount < 0) break
                            fileOut.write(chunk, 0, readCount)
                            received += readCount
                            check(received <= byteCeiling) { "Update download exceeds the allowed size." }
                            progress(received, contentLen)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            check(info.sha256.isNotBlank()) { "Update manifest is missing SHA-256 checksum — refusing install." }
            val computedDigest = computeFileSha256(tempPartFile)
            check(computedDigest.equals(info.sha256, ignoreCase = true)) {
                "Downloaded update failed SHA-256 checksum verification."
            }
            validateDownloadedArchive(tempPartFile, info.versionCode)
            if (finalApkFile.exists() && !finalApkFile.delete()) {
                error("Could not replace previous staged update APK.")
            }
            check(tempPartFile.renameTo(finalApkFile)) { "Could not finalize downloaded update APK." }
            return finalApkFile
        } catch (err: Throwable) {
            tempPartFile.delete()
            throw err
        }
    }

    @Suppress("DEPRECATION")
    private fun validateDownloadedArchive(apkFile: File, targetVersionCode: Long) {
        val certFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val pkgArchive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, certFlags)
            ?: error("Downloaded file is not a valid Android APK archive.")
        check(pkgArchive.packageName == context.packageName) {
            "Downloaded APK package (${pkgArchive.packageName}) does not match installed app (${context.packageName})."
        }
        val pkgVer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgArchive.longVersionCode
        } else {
            pkgArchive.versionCode.toLong()
        }
        check(pkgVer == targetVersionCode && pkgVer > installedVersionCode()) {
            "Downloaded APK version ($pkgVer) does not match expected update version ($targetVersionCode)."
        }
        val hostPkg = context.packageManager.getPackageInfo(context.packageName, certFlags)
        val archiveSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgArchive.signingInfo?.apkContentsSigners
        } else {
            pkgArchive.signatures
        }
        val hostSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hostPkg.signingInfo?.apkContentsSigners
        } else {
            hostPkg.signatures
        }
        val archiveHashes = archiveSigners?.map { computeBytesSha256(it.toByteArray()) }?.toSet().orEmpty()
        val hostHashes = hostSigners?.map { computeBytesSha256(it.toByteArray()) }?.toSet().orEmpty()
        check(archiveHashes.isNotEmpty() && hostHashes.isNotEmpty() && archiveHashes == hostHashes) {
            "Downloaded APK signing certificate does not match the installed app."
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        readBuildConfigLong("VERSION_CODE")?.let { return it }
        return runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong()
        }.getOrDefault(1L)
    }

    private fun currentVariantName(): String =
        readBuildConfigString("APP_VARIANT")?.takeIf(String::isNotBlank) ?: "debug"

    private fun resolveDefaultManifestUrl(): String =
        readBuildConfigString("APP_UPDATE_MANIFEST_URL").orEmpty()

    private fun loadBuildConfigClass(): Class<*>? =
        runCatching { Class.forName("${context.packageName}.BuildConfig") }.getOrNull()
            ?: runCatching { Class.forName("com.nextcraft.forge.BuildConfig") }.getOrNull()

    private fun readBuildConfigString(key: String): String? = runCatching {
        loadBuildConfigClass()?.getField(key)?.get(null) as? String
    }.getOrNull()

    private fun readBuildConfigLong(key: String): Long? = runCatching {
        when (val raw = loadBuildConfigClass()?.getField(key)?.get(null)) {
            is Long -> raw
            is Int -> raw.toLong()
            else -> null
        }
    }.getOrNull()

    private fun computeFileSha256(target: File): String = target.inputStream().use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(131_072)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            md.update(chunk, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeBytesSha256(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_CHAR_CAP = 256 * 1024
        const val APK_BYTE_CAP = 512L * 1024 * 1024
    }
}
