package com.nextcraft.forge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class ApiKeyVault(context: Context) {
    private val preferences = context.getSharedPreferences("nextcraft_secrets", Context.MODE_PRIVATE)
    /**
     * The keystore alias that encrypts the stored secrets. A migration writes every re-encrypted secret together
     * with a new alias in one commit, so storage never mixes ciphertexts of two keys.
     */
    private val alias: String
        get() = preferences.getString(KEY_ALIAS_PREF, null) ?: LEGACY_KEY_ALIAS

    init {
        migrateAuthBoundKeyIfNeeded()
    }

    /**
     * Earlier builds created the master key with setUserAuthenticationRequired(true) and a 300 s
     * validity window, which made every decrypt fail (and self-destruct) a few minutes after each
     * unlock. When such a key is still present, re-encrypt every secret under a fresh unbound key.
     * Decryption of the old key only works shortly after an unlock, so this retries on each launch
     * until it succeeds and never deletes anything on failure.
     */
    @Synchronized
    private fun migrateAuthBoundKeyIfNeeded() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(alias)) return
            val oldKey = keyStore.getKey(alias, null) as? SecretKey ?: return
            val info = SecretKeyFactory.getInstance(oldKey.algorithm, "AndroidKeyStore")
                .getKeySpec(oldKey, KeyInfo::class.java) as KeyInfo
            if (!info.isUserAuthenticationRequired) return

            // Decrypt everything first; abort (without touching storage) if any entry fails.
            val secretIds = preferences.all.keys
                .filter { it.endsWith(".value") }
                .map { it.removeSuffix(".value") }
            val plaintexts = secretIds.associateWith { storageId ->
                val iv = Base64.decode(preferences.getString("$storageId.iv", null) ?: return, Base64.NO_WRAP)
                val encrypted = Base64.decode(preferences.getString("$storageId.value", null) ?: return, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, oldKey, GCMParameterSpec(128, iv))
                cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            }
            // Re-encrypt under a new alias and switch over in a single commit. Deleting the old key first (as before)
            // lost every secret not yet rewritten if one encryption or the commit then failed.
            val oldAlias = alias
            val newAlias = "$LEGACY_KEY_ALIAS-${System.currentTimeMillis()}"
            val newKey = createKey(newAlias)
            val edit = preferences.edit()
            try {
                plaintexts.forEach { (storageId, secret) ->
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, newKey)
                    val encrypted = cipher.doFinal(cleanSecret(secret).toByteArray(Charsets.UTF_8))
                    edit.putString("$storageId.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    edit.putString("$storageId.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                }
                edit.putString(KEY_ALIAS_PREF, newAlias)
                check(edit.commit()) { "Could not store the re-encrypted secrets" }
            } catch (e: Exception) {
                runCatching { keyStore.deleteEntry(newAlias) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ApiKeyVault", it) }
                throw e
            }
            runCatching { keyStore.deleteEntry(oldAlias) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ApiKeyVault", it) }
            android.util.Log.i("ApiKeyVault", "Migrated ${plaintexts.size} secrets to an unbound keystore key")
        }.onFailure { error ->
            android.util.Log.w("ApiKeyVault", "Keystore migration deferred: ${error::class.simpleName}")
        }
    }

    @Synchronized
    fun put(providerId: String, secret: String) {
        if (secret.isBlank()) return
        val entries = ensurePool(providerId)
        val active = entries.firstOrNull { it.id == activeId(providerId) } ?: entries.firstOrNull()
        if (active == null) {
            add(providerId, "Primary", secret)
        } else {
            putEncrypted(secretKey(providerId, active.id), secret)
        }
    }

    @Synchronized
    fun add(providerId: String, name: String, secret: String): ApiKeyInfo {
        require(secret.isNotBlank()) { "API key cannot be empty" }
        val entries = ensurePool(providerId).toMutableList()
        val entry = ApiKeyInfo(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "API key ${entries.size + 1}" }.take(60),
            isActive = entries.isEmpty(),
        )
        putEncrypted(secretKey(providerId, entry.id), secret)
        entries += entry.copy(isActive = false)
        savePool(providerId, entries)
        if (entries.size == 1) setActiveId(providerId, entry.id)
        return entry.copy(isActive = entries.size == 1)
    }

    @Synchronized
    fun list(providerId: String): List<ApiKeyInfo> {
        val entries = ensurePool(providerId)
        val active = activeId(providerId) ?: entries.firstOrNull()?.id
        return entries.map { it.copy(isActive = it.id == active) }
    }

    @Synchronized
    fun credentials(providerId: String): List<ApiKeyCredential> = list(providerId).mapNotNull { info ->
        getEncrypted(secretKey(providerId, info.id))?.let { secret ->
            ApiKeyCredential(info.id, info.name, secret, info.isActive)
        }
    }.sortedByDescending(ApiKeyCredential::isActive)

    @Synchronized
    fun activate(providerId: String, keyId: String): Boolean {
        if (ensurePool(providerId).none { it.id == keyId }) return false
        setActiveId(providerId, keyId)
        return true
    }

    /**
     * Rotates the active key to the next available key in the provider's pool
     * (used for automatic failover on HTTP 429 rate-limit or 402 quota errors).
     */
    @Synchronized
    fun rotateNext(providerId: String): String? {
        val entries = list(providerId)
        if (entries.size <= 1) return get(providerId)
        val currentIdx = entries.indexOfFirst { it.isActive }.coerceAtLeast(0)
        // Skip entries whose secret is missing or unreadable instead of returning null for the whole rotation.
        for (step in 1 until entries.size) {
            val candidate = entries[(currentIdx + step) % entries.size]
            val secret = getEncrypted(secretKey(providerId, candidate.id)) ?: continue
            setActiveId(providerId, candidate.id)
            return secret
        }
        return get(providerId)
    }

    @Synchronized
    fun remove(providerId: String, keyId: String) {
        val remaining = ensurePool(providerId).filterNot { it.id == keyId }
        removeEncrypted(secretKey(providerId, keyId))
        savePool(providerId, remaining)
        if (activeId(providerId) == keyId) {
            preferences.edit().remove(activeKey(providerId)).apply()
            remaining.firstOrNull()?.let { setActiveId(providerId, it.id) }
        }
    }

    private fun putEncrypted(storageId: String, secret: String) {
        val plaintext = cleanSecret(secret).toByteArray(Charsets.UTF_8)
        val (iv, encrypted) = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.iv to cipher.doFinal(plaintext)
        }.getOrElse { error ->
            // Never reset the master key here: that would make every other stored secret
            // (all provider keys + GITHUB_TOKEN) unreadable. Surface the failure instead.
            android.util.Log.w("ApiKeyVault", "Encrypt failed: ${error::class.simpleName}")
            throw IllegalStateException("Could not encrypt the API key. Unlock the device and try again.", error)
        }
        val saved = preferences.edit()
            .putString("$storageId.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("$storageId.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        // A failed commit (disk full) used to look like a saved key that then read back as missing.
        check(saved) { "Could not save the API key. Check free storage and try again." }
    }

    fun contains(providerId: String): Boolean = get(providerId) != null

    @Synchronized
    fun remove(providerId: String) {
        ensurePool(providerId).forEach { removeEncrypted(secretKey(providerId, it.id)) }
        preferences.edit()
            .remove("$providerId.iv")
            .remove("$providerId.value")
            .remove(poolKey(providerId))
            .remove(activeKey(providerId))
            .apply()
    }

    @Synchronized
    fun get(providerId: String): String? {
        val active = list(providerId).firstOrNull { it.isActive } ?: return null
        return getEncrypted(secretKey(providerId, active.id))
    }

    private fun getEncrypted(storageId: String): String? {
        val ivStr = preferences.getString("$storageId.iv", null) ?: return null
        val valStr = preferences.getString("$storageId.value", null) ?: return null
        return runCatching {
            val iv = Base64.decode(ivStr, Base64.NO_WRAP)
            val encrypted = Base64.decode(valStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cleanSecret(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        }.onFailure { error ->
            // Never delete silently: a corrupt entry (AEAD tag or base64 failure) stays on disk so the user can be
            // told and re-enter the key, and transient keystore failures (device locked, daemon busy) lose nothing.
            android.util.Log.w("ApiKeyVault", "Decrypt failed: ${error::class.simpleName}")
            com.nextcraft.forge.runtime.ErrorLog.swallowed("ApiKeyVault", error)
        }.getOrNull()
    }

    /**
     * API keys and OAuth tokens never contain whitespace. Terminals often wrap long tokens such as
     * `claude setup-token` output, so pasted secrets can include line breaks or a "Bearer " prefix.
     */
    private fun cleanSecret(secret: String): String =
        secret.trim().removePrefix("Bearer ").removePrefix("bearer ").filterNot(Char::isWhitespace)

    private fun removeEncrypted(storageId: String) {
        preferences.edit().remove("$storageId.iv").remove("$storageId.value").apply()
    }

    private fun ensurePool(providerId: String): List<ApiKeyInfo> {
        readPool(providerId).takeIf(List<ApiKeyInfo>::isNotEmpty)?.let { return it }
        if (!preferences.contains("$providerId.value")) return emptyList()
        val legacySecret = getEncrypted(providerId) ?: return emptyList()
        val legacy = ApiKeyInfo("legacy", "Primary", true)
        putEncrypted(secretKey(providerId, legacy.id), legacySecret)
        removeEncrypted(providerId)
        savePool(providerId, listOf(legacy))
        setActiveId(providerId, legacy.id)
        return listOf(legacy)
    }

    private fun readPool(providerId: String): List<ApiKeyInfo> = runCatching {
        val rawPool = preferences.getString(poolKey(providerId), "[]").orEmpty().ifBlank { "[]" }
        val array = JSONArray(rawPool)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                if (id.isBlank()) null else ApiKeyInfo(id, item.optString("name").ifBlank { "API key" })
            }
        }
    }.getOrDefault(emptyList())

    private fun savePool(providerId: String, entries: List<ApiKeyInfo>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        preferences.edit().putString(poolKey(providerId), array.toString()).commit()
    }

    private fun activeId(providerId: String): String? = preferences.getString(activeKey(providerId), null)
    private fun setActiveId(providerId: String, id: String) = preferences.edit().putString(activeKey(providerId), id).commit()
    private fun poolKey(providerId: String) = "$providerId.pool"
    private fun activeKey(providerId: String) = "$providerId.active"
    private fun secretKey(providerId: String, id: String) = "$providerId.pool.$id"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            // An existing key that cannot be loaded right now is a transient keystore error.
            // Do not delete it: that would orphan every ciphertext written with it.
            return keyStore.getKey(alias, null) as? SecretKey
                ?: throw IllegalStateException("AndroidKeyStore key '$alias' is unavailable")
        }
        return createKey(alias)
    }

    private fun createKey(keyAlias: String): SecretKey {
        // No user-authentication binding: the app never shows a BiometricPrompt/Keyguard, so an
        // auth-bound key would throw UserNotAuthenticatedException minutes after each unlock.
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val LEGACY_KEY_ALIAS = "nextcraft-provider-key"
        const val KEY_ALIAS_PREF = "keystore.alias"
    }
}

data class ApiKeyInfo(val id: String, val name: String, val isActive: Boolean = false)

data class ApiKeyCredential(val id: String, val name: String, val secret: String, val isActive: Boolean)
