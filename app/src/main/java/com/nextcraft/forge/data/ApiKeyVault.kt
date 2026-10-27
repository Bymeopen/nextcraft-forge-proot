package com.nextcraft.forge.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.nextcraft.forge.runtime.ErrorLog
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

data class ApiKeyInfo(
    val id: String,
    val name: String,
    val isActive: Boolean = false,
)

data class ApiKeyCredential(
    val id: String,
    val name: String,
    val secret: String,
    val isActive: Boolean,
)

class ApiKeyVault(context: Context) {
    private val store: SharedPreferences =
        context.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)

    private val currentKeyAlias: String
        get() = store.getString(META_KEY_ALIAS, null) ?: DEFAULT_MASTER_ALIAS

    init {
        upgradeAuthBoundKeystoreEntry()
    }

    @Synchronized
    private fun upgradeAuthBoundKeystoreEntry() {
        runCatching {
            val ks = openAndroidKeyStore()
            val activeAlias = currentKeyAlias
            if (!ks.containsAlias(activeAlias)) return
            val existingSecretKey = ks.getKey(activeAlias, null) as? SecretKey ?: return
            val keySpec = SecretKeyFactory.getInstance(existingSecretKey.algorithm, KEYSTORE_PROVIDER)
                .getKeySpec(existingSecretKey, KeyInfo::class.java) as KeyInfo
            if (!keySpec.isUserAuthenticationRequired) return

            val entryPrefixes = store.all.keys
                .asSequence()
                .filter { it.endsWith(SUFFIX_CIPHERTEXT) }
                .map { it.removeSuffix(SUFFIX_CIPHERTEXT) }
                .toList()

            val decryptedPayloads = LinkedHashMap<String, String>(entryPrefixes.size)
            for (slotKey in entryPrefixes) {
                val nonceB64 = store.getString(slotKey + SUFFIX_NONCE, null) ?: return
                val bodyB64 = store.getString(slotKey + SUFFIX_CIPHERTEXT, null) ?: return
                val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
                val ciphertext = Base64.decode(bodyB64, Base64.NO_WRAP)
                val decryptor = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, existingSecretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
                }
                decryptedPayloads[slotKey] = decryptor.doFinal(ciphertext).decodeToString()
            }

            val rotatedAlias = "$DEFAULT_MASTER_ALIAS-${System.currentTimeMillis()}"
            val rotatedKey = generateHardwareAesKey(rotatedAlias)
            val tx = store.edit()
            try {
                for ((slotKey, rawToken) in decryptedPayloads) {
                    val encryptor = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                        init(Cipher.ENCRYPT_MODE, rotatedKey)
                    }
                    val sealedBytes = encryptor.doFinal(normalizeToken(rawToken).encodeToByteArray())
                    tx.putString(slotKey + SUFFIX_NONCE, Base64.encodeToString(encryptor.iv, Base64.NO_WRAP))
                    tx.putString(slotKey + SUFFIX_CIPHERTEXT, Base64.encodeToString(sealedBytes, Base64.NO_WRAP))
                }
                tx.putString(META_KEY_ALIAS, rotatedAlias)
                check(tx.commit()) { "Could not store the re-encrypted secrets" }
            } catch (err: Exception) {
                runCatching { ks.deleteEntry(rotatedAlias) }
                    .onFailure { ErrorLog.swallowed(LOG_TAG, it) }
                throw err
            }
            runCatching { ks.deleteEntry(activeAlias) }
                .onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            Log.i(LOG_TAG, "Migrated ${decryptedPayloads.size} secrets to an unbound keystore key")
        }.onFailure { err ->
            Log.w(LOG_TAG, "Keystore migration deferred: ${err::class.simpleName}")
        }
    }

    @Synchronized
    fun put(providerId: String, secret: String) {
        if (secret.isBlank()) return
        val pool = loadOrMigratePool(providerId)
        val selectedId = readSelectedKeyId(providerId)
        val target = pool.firstOrNull { it.id == selectedId } ?: pool.firstOrNull()
        if (target == null) {
            add(providerId, "Primary", secret)
        } else {
            sealAndCommit(slotId(providerId, target.id), secret)
        }
    }

    @Synchronized
    fun add(providerId: String, name: String, secret: String): ApiKeyInfo {
        require(secret.isNotBlank()) { "API key cannot be empty" }
        val pool = loadOrMigratePool(providerId).toMutableList()
        val cleanLabel = name.trim().ifEmpty { "API key ${pool.size + 1}" }.take(60)
        val newEntryId = UUID.randomUUID().toString()
        val becomeActive = pool.isEmpty()

        sealAndCommit(slotId(providerId, newEntryId), secret)
        pool.add(ApiKeyInfo(id = newEntryId, name = cleanLabel, isActive = false))
        persistPoolIndex(providerId, pool)
        if (becomeActive) {
            writeSelectedKeyId(providerId, newEntryId)
        }
        return ApiKeyInfo(id = newEntryId, name = cleanLabel, isActive = becomeActive)
    }

    @Synchronized
    fun list(providerId: String): List<ApiKeyInfo> {
        val pool = loadOrMigratePool(providerId)
        val activeKeyId = readSelectedKeyId(providerId) ?: pool.firstOrNull()?.id
        return pool.map { item -> item.copy(isActive = item.id == activeKeyId) }
    }

    @Synchronized
    fun credentials(providerId: String): List<ApiKeyCredential> =
        list(providerId)
            .mapNotNull { item ->
                unsealSlot(slotId(providerId, item.id))?.let { token ->
                    ApiKeyCredential(
                        id = item.id,
                        name = item.name,
                        secret = token,
                        isActive = item.isActive,
                    )
                }
            }
            .sortedByDescending { it.isActive }

    @Synchronized
    fun activate(providerId: String, keyId: String): Boolean {
        val pool = loadOrMigratePool(providerId)
        if (pool.none { it.id == keyId }) return false
        writeSelectedKeyId(providerId, keyId)
        return true
    }

    @Synchronized
    fun rotateNext(providerId: String): String? {
        val items = list(providerId)
        if (items.size <= 1) return get(providerId)
        val startIndex = items.indexOfFirst { it.isActive }.coerceAtLeast(0)
        for (offset in 1 until items.size) {
            val nextCandidate = items[(startIndex + offset) % items.size]
            val token = unsealSlot(slotId(providerId, nextCandidate.id)) ?: continue
            writeSelectedKeyId(providerId, nextCandidate.id)
            return token
        }
        return get(providerId)
    }

    @Synchronized
    fun remove(providerId: String, keyId: String) {
        val updatedPool = loadOrMigratePool(providerId).filter { it.id != keyId }
        eraseSlot(slotId(providerId, keyId))
        persistPoolIndex(providerId, updatedPool)
        if (readSelectedKeyId(providerId) == keyId) {
            store.edit().remove(selectedSlotPref(providerId)).apply()
            updatedPool.firstOrNull()?.let { writeSelectedKeyId(providerId, it.id) }
        }
    }

    fun contains(providerId: String): Boolean = get(providerId) != null

    @Synchronized
    fun remove(providerId: String) {
        for (entry in loadOrMigratePool(providerId)) {
            eraseSlot(slotId(providerId, entry.id))
        }
        store.edit()
            .remove(providerId + SUFFIX_NONCE)
            .remove(providerId + SUFFIX_CIPHERTEXT)
            .remove(poolIndexPref(providerId))
            .remove(selectedSlotPref(providerId))
            .apply()
    }

    @Synchronized
    fun get(providerId: String): String? {
        val current = list(providerId).firstOrNull { it.isActive } ?: return null
        return unsealSlot(slotId(providerId, current.id))
    }

    private fun sealAndCommit(slotKey: String, rawSecret: String) {
        val cleanBytes = normalizeToken(rawSecret).encodeToByteArray()
        val cipherResult = runCatching {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, resolveOrCreateHardwareKey())
            cipher.iv to cipher.doFinal(cleanBytes)
        }.getOrElse { err ->
            Log.w(LOG_TAG, "Encrypt failed: ${err::class.simpleName}")
            throw IllegalStateException("Could not encrypt the API key. Unlock the device and try again.", err)
        }

        val committed = store.edit()
            .putString(slotKey + SUFFIX_NONCE, Base64.encodeToString(cipherResult.first, Base64.NO_WRAP))
            .putString(slotKey + SUFFIX_CIPHERTEXT, Base64.encodeToString(cipherResult.second, Base64.NO_WRAP))
            .commit()
        check(committed) { "Could not save the API key. Check free storage and try again." }
    }

    private fun unsealSlot(slotKey: String): String? {
        val encodedNonce = store.getString(slotKey + SUFFIX_NONCE, null) ?: return null
        val encodedBody = store.getString(slotKey + SUFFIX_CIPHERTEXT, null) ?: return null
        return runCatching {
            val nonce = Base64.decode(encodedNonce, Base64.NO_WRAP)
            val body = Base64.decode(encodedBody, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, resolveOrCreateHardwareKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            normalizeToken(cipher.doFinal(body).decodeToString())
        }.onFailure { err ->
            Log.w(LOG_TAG, "Decrypt failed: ${err::class.simpleName}")
            if (err is AEADBadTagException || err is IllegalArgumentException) {
                eraseSlot(slotKey)
            }
        }.getOrNull()
    }

    private fun normalizeToken(raw: String): String {
        val stripped = raw.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
        val sb = StringBuilder(stripped.length)
        for (ch in stripped) {
            if (!ch.isWhitespace()) sb.append(ch)
        }
        return sb.toString()
    }

    private fun eraseSlot(slotKey: String) {
        store.edit()
            .remove(slotKey + SUFFIX_NONCE)
            .remove(slotKey + SUFFIX_CIPHERTEXT)
            .apply()
    }

    private fun loadOrMigratePool(providerId: String): List<ApiKeyInfo> {
        val currentEntries = decodePoolJson(providerId)
        if (currentEntries.isNotEmpty()) return currentEntries
        if (!store.contains(providerId + SUFFIX_CIPHERTEXT)) return emptyList()

        val migratedSecret = unsealSlot(providerId) ?: return emptyList()
        val migratedEntry = ApiKeyInfo(id = "legacy", name = "Primary", isActive = true)
        sealAndCommit(slotId(providerId, migratedEntry.id), migratedSecret)
        eraseSlot(providerId)
        val singlePool = listOf(migratedEntry)
        persistPoolIndex(providerId, singlePool)
        writeSelectedKeyId(providerId, migratedEntry.id)
        return singlePool
    }

    private fun decodePoolJson(providerId: String): List<ApiKeyInfo> = runCatching {
        val rawJson = store.getString(poolIndexPref(providerId), null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val jsonList = JSONArray(rawJson)
        buildList(jsonList.length()) {
            for (idx in 0 until jsonList.length()) {
                val node = jsonList.optJSONObject(idx) ?: continue
                val keyId = node.optString("id").trim()
                if (keyId.isNotEmpty()) {
                    val label = node.optString("name").trim().ifEmpty { "API key" }
                    add(ApiKeyInfo(id = keyId, name = label))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun persistPoolIndex(providerId: String, entries: List<ApiKeyInfo>) {
        val serialized = JSONArray().apply {
            for (entry in entries) {
                put(JSONObject().put("id", entry.id).put("name", entry.name))
            }
        }.toString()
        store.edit().putString(poolIndexPref(providerId), serialized).commit()
    }

    private fun readSelectedKeyId(providerId: String): String? =
        store.getString(selectedSlotPref(providerId), null)

    private fun writeSelectedKeyId(providerId: String, id: String) {
        store.edit().putString(selectedSlotPref(providerId), id).commit()
    }

    private fun poolIndexPref(providerId: String): String = "$providerId.pool"
    private fun selectedSlotPref(providerId: String): String = "$providerId.active"
    private fun slotId(providerId: String, id: String): String = "$providerId.pool.$id"

    private fun openAndroidKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun resolveOrCreateHardwareKey(): SecretKey {
        val ks = openAndroidKeyStore()
        val targetAlias = currentKeyAlias
        if (ks.containsAlias(targetAlias)) {
            return ks.getKey(targetAlias, null) as? SecretKey
                ?: throw IllegalStateException("AndroidKeyStore key '$targetAlias' is unavailable")
        }
        return generateHardwareAesKey(targetAlias)
    }

    private fun generateHardwareAesKey(targetAlias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            targetAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val LOG_TAG = "ApiKeyVault"
        const val VAULT_PREFS_NAME = "nextcraft_secrets"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val DEFAULT_MASTER_ALIAS = "nextcraft-provider-key"
        const val META_KEY_ALIAS = "keystore.alias"
        const val SUFFIX_NONCE = ".iv"
        const val SUFFIX_CIPHERTEXT = ".value"
    }
}
