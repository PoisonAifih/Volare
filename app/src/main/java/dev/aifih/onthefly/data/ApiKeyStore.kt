package dev.aifih.onthefly.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Cursor API key encrypted with an AES-GCM key that lives in the Android
 * Keystore, so the raw key never touches disk in plaintext and cannot be read by adb
 * backup or another app.
 *
 * This is hand-rolled rather than using Jetpack Security's `EncryptedSharedPreferences`,
 * which is deprecated and no longer receives fixes.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = decrypt(prefs.getString(KEY_API_KEY, null))

    fun get(): String? = cached

    fun isConfigured(): Boolean = !cached.isNullOrBlank()

    fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        cached = trimmed
        prefs.edit().putString(KEY_API_KEY, encrypt(trimmed)).apply()
    }

    fun clear() {
        cached = null
        prefs.edit().remove(KEY_API_KEY).apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray())

        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null

        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))

            String(cipher.doFinal(ciphertext))
        }.getOrNull()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val existing = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )

        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "onthefly_secrets"
        const val KEY_API_KEY = "cursor_api_key"
        const val KEY_ALIAS = "onthefly_api_key"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
