package dev.aifih.onthefly

import android.content.Context
import android.content.SharedPreferences
import dev.aifih.onthefly.data.AgentRepository
import dev.aifih.onthefly.data.ApiKeyStore
import dev.aifih.onthefly.data.CursorApi
import dev.aifih.onthefly.data.RunStream
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

        prefs = context.getSharedPreferences("onthefly_cache", Context.MODE_PRIVATE)

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
