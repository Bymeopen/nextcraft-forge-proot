package com.nextcraft.forge.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nextcraft.forge.model.stringOrEmpty
import java.io.File
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

/** The manifest URL answered 404: the project has not published an app release (or manifest) yet. */
class NoPublishedReleaseException : java.io.IOException("No app release has been published yet.")

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL override. When non-empty, used in place of
     * `BuildConfig.APP_UPDATE_MANIFEST_URL`, which defaults to the `update-manifest.json` asset of the latest
     * GitHub release (see scripts/make-update-manifest.sh).
     */
    private val manifestUrlOverride: String = "",
) {
    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { defaultManifestUrl() }.trim()
        check(manifestUrl.startsWith("https://")) { "No HTTPS update manifest URL is configured." }
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code == 404) throw NoPublishedReleaseException()
            if (code !in 200..299) throw java.io.IOException("Update manifest request failed (HTTP $code)")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                buildString {
                    val buffer = CharArray(4 * 1024)
                    while (length < MAX_MANIFEST_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }
            val root = JSONObject(body)
            // A manifest for another package is not an update for this app (older side-by-side builds used com.nextcraft.forge.x).
            root.stringOrEmpty("packageName").takeIf { it.isNotBlank() }?.let { if (it != context.packageName) return null }
            val versionCode = root.optLong("versionCode")
            if (versionCode <= currentVersionCode()) return null
            val variant = appVariant()
            val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
                ?: root.optJSONObject(variant)
                ?: root
            val url = artifact.stringOrEmpty("url").ifBlank { artifact.stringOrEmpty("apkUrl") }.trim()
            if (!url.startsWith("https://")) return null
            AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.stringOrEmpty("versionName", versionCode.toString()),
                apkUrl = url,
                sha256 = artifact.stringOrEmpty("sha256").trim().lowercase(),
                sizeBytes = artifact.optLong("sizeBytes", -1L),
                notes = root.stringOrEmpty("notes"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        require(info.apkUrl.startsWith("https://")) { "Update APK URL must use HTTPS." }
        val variant = appVariant()
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "nextcraft-forge-$variant.apk.part")
        val target = File(directory, "nextcraft-forge-$variant.apk")
        try {
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                check(code in 200..299) { "Update download failed (HTTP $code)." }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                val maxBytes = if (info.sizeBytes in 1..MAX_APK_BYTES) info.sizeBytes else MAX_APK_BYTES
                check(total <= maxBytes) { "Update download exceeds the allowed size." }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            check(downloaded <= maxBytes) { "Update download exceeds the allowed size." }
                            progress(downloaded, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            check(info.sha256.isNotBlank()) {
                "Update manifest is missing SHA-256 checksum — refusing install."
            }
            val actual = sha256(partial)
            check(actual.equals(info.sha256, ignoreCase = true)) {
                "Downloaded update failed SHA-256 checksum verification."
            }
            verifyApk(partial, info.versionCode)
            if (target.exists() && !target.delete()) {
                error("Could not replace previous staged update APK.")
            }
            check(partial.renameTo(target)) { "Could not finalize downloaded update APK." }
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid Android APK archive.")
        check(archive.packageName == context.packageName) {
            "Downloaded APK package (${archive.packageName}) does not match installed app (${context.packageName})."
        }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        check(archiveVersion == expectedVersionCode && archiveVersion > currentVersionCode()) {
            "Downloaded APK version ($archiveVersion) does not match expected update version ($expectedVersionCode)."
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners
        } else {
            archive.signatures
        }
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners
        } else {
            installed.signatures
        }
        check(
            !archiveSignatures.isNullOrEmpty() &&
                !installedSignatures.isNullOrEmpty() &&
                archiveSignatures.map { sha256(it.toByteArray()) }.toSet() ==
                installedSignatures.map { sha256(it.toByteArray()) }.toSet(),
        ) {
            "Downloaded APK signing certificate does not match the installed app."
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        buildConfigLong("VERSION_CODE")?.let { return it }
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(1L)
    }

    private fun appVariant(): String =
        buildConfigString("APP_VARIANT")?.takeIf(String::isNotBlank) ?: "debug"

    private fun defaultManifestUrl(): String =
        buildConfigString("APP_UPDATE_MANIFEST_URL").orEmpty()

    private fun resolveBuildConfigClass(): Class<*>? =
        runCatching { Class.forName("${context.packageName}.BuildConfig") }.getOrNull()
            ?: runCatching { Class.forName("com.nextcraft.forge.BuildConfig") }.getOrNull()

    private fun buildConfigString(fieldName: String): String? = runCatching {
        val cls = resolveBuildConfigClass() ?: return@runCatching null
        cls.getField(fieldName).get(null) as? String
    }.getOrNull()

    private fun buildConfigLong(fieldName: String): Long? = runCatching {
        val cls = resolveBuildConfigClass() ?: return@runCatching null
        when (val value = cls.getField(fieldName).get(null)) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }
    }.getOrNull()

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_MANIFEST_CHARS = 256 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
