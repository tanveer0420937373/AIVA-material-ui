package com.tanveer.aiva.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tanveer.aiva.domain.models.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts provider keys with an AES-GCM key that stays in Android Keystore.
 *
 * This keeps keys out of backups and out of logs. It does not make them unextractable on a
 * compromised or rooted device, and the app sends them to the provider the user picked.
 * A failed decrypt deletes only that ciphertext and clears the provider's ready state.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _configured = MutableStateFlow(readConfigured())
    val configured: StateFlow<Set<ProviderId>> = _configured.asStateFlow()
    private val unreadable = mutableSetOf<ProviderId>()

    fun wasUnreadable(provider: ProviderId): Boolean = synchronized(unreadable) { provider in unreadable }

    /** Drops ciphertext that can no longer be opened. Safe to call from application start. */
    fun reconcile() {
        ProviderId.entries.forEach { provider ->
            if (prefs.contains(provider.name)) get(provider)
        }
    }

    fun get(provider: ProviderId): String? {
        val stored = prefs.getString(provider.name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            if (bytes.size <= IV_SIZE) error("short")
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            prefs.edit().remove(provider.name).apply()
            synchronized(unreadable) { unreadable += provider }
            _configured.value = _configured.value - provider
            null
        }
    }

    fun set(provider: ProviderId, value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(provider.name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        synchronized(unreadable) { unreadable -= provider }
        _configured.value = _configured.value + provider
    }

    fun remove(provider: ProviderId) {
        prefs.edit().remove(provider.name).apply()
        synchronized(unreadable) { unreadable -= provider }
        _configured.value = _configured.value - provider
    }

    private fun readConfigured(): Set<ProviderId> =
        ProviderId.entries.filter { prefs.contains(it.name) }.toSet()

    private fun secretKey(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "aiva_secure_keys"
        const val ALIAS = "aiva_provider_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
