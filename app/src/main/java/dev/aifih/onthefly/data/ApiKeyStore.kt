package dev.aifih.onthefly.data

import android.content.Context

/**
 * The Cursor API key. It grants full access to the account's cloud agents, so it is only ever
 * held here and never logged, put in an Intent extra, or written to a plain file.
 */
class ApiKeyStore(context: Context) {

    private val secret = SecretStore(
        context = context,
        prefKey = "cursor_api_key",
        keyAlias = "onthefly_api_key",
    )

    fun get(): String? = secret.get()

    fun isConfigured(): Boolean = secret.isConfigured()

    fun save(apiKey: String) = secret.save(apiKey)

    fun clear() = secret.clear()
}
