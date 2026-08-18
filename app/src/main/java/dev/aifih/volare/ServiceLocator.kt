package dev.aifih.volare

import android.content.Context
import android.content.SharedPreferences
import dev.aifih.volare.data.AgentRepository
import dev.aifih.volare.data.ApiKeyStore
import dev.aifih.volare.data.CursorApi
import dev.aifih.volare.data.RunStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


object ServiceLocator {

    lateinit var apiKeyStore: ApiKeyStore
        private set

    lateinit var repository: AgentRepository
        private set

    lateinit var runStream: RunStream
        private set

    /** Shared with [dev.aifih.volare.update.AppUpdater], which is built per ViewModel. */
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

        prefs = context.getSharedPreferences("volare_cache", Context.MODE_PRIVATE)

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
