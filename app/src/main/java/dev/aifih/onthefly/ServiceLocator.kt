package dev.aifih.onthefly

import android.content.Context
import android.content.SharedPreferences
import dev.aifih.onthefly.data.AgentRepository
import dev.aifih.onthefly.data.ApiKeyStore
import dev.aifih.onthefly.data.CursorApi
import dev.aifih.onthefly.data.RunStream
import dev.aifih.onthefly.data.SecretStore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


object ServiceLocator {

    lateinit var apiKeyStore: ApiKeyStore
        private set

    /**
     * Read-only GitHub token, needed because the releases repository is private. Kept apart
     * from the Cursor key so revoking one does not affect the other.
     */
    lateinit var updateTokenStore: SecretStore
        private set

    lateinit var repository: AgentRepository
        private set

    lateinit var runStream: RunStream
        private set

    /** Shared with [dev.aifih.onthefly.update.AppUpdater], which is built per ViewModel. */
    lateinit var httpClient: OkHttpClient
        private set

    private lateinit var prefs: SharedPreferences

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun init(context: Context) {
        if (::repository.isInitialized) return

        apiKeyStore = ApiKeyStore(context)

        updateTokenStore = SecretStore(
            context = context,
            prefKey = "github_update_token",
            keyAlias = "onthefly_update_token",
        )

        prefs = context.getSharedPreferences("onthefly_cache", Context.MODE_PRIVATE)

        // Shared by the API client and the updater. The long read timeout is needed because
        // GET /v1/repositories can legitimately take tens of seconds.
        httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        val api = CursorApi(
            client = httpClient,
            json = json,
            apiKeyProvider = { apiKeyStore.get() },
        )

        repository = AgentRepository(api, prefs, json)

        runStream = RunStream(
            api = api,
            client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build(),
            json = json,
        )
    }
}
