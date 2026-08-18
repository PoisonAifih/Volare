package dev.aifih.onthefly.data

import android.content.Context

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
