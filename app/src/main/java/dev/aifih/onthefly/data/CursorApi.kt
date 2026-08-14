package dev.aifih.onthefly.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class MissingApiKeyException : IOException("API key belum diatur")

class CursorApiException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String,
) : IOException(message) {

    val isAgentBusy: Boolean get() = statusCode == 409 && errorCode == "agent_busy"

    val isUnauthorized: Boolean get() = statusCode == 401 || statusCode == 403

    val isRateLimited: Boolean get() = statusCode == 429
}


class CursorApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiKeyProvider: () -> String?,
) {

    suspend fun me(): MeResponse = get("v1/me", MeResponse.serializer())

    suspend fun listAgents(limit: Int = 30, cursor: String? = null): AgentListResponse =
        get(
            path = "v1/agents",
            serializer = AgentListResponse.serializer(),
            query = buildMap {
                put("limit", limit.toString())
                if (cursor != null) put("cursor", cursor)
            },
        )

    suspend fun getAgent(agentId: String): Agent =
        get("v1/agents/$agentId", Agent.serializer())

    suspend fun createAgent(request: CreateAgentRequest): CreateAgentResponse =
        post(
            path = "v1/agents",
            body = encode(CreateAgentRequest.serializer(), request),
            serializer = CreateAgentResponse.serializer(),
        )

    suspend fun listRuns(agentId: String, limit: Int = 30): RunListResponse =
        get(
            path = "v1/agents/$agentId/runs",
            serializer = RunListResponse.serializer(),
            query = mapOf("limit" to limit.toString()),
        )

    suspend fun getRun(agentId: String, runId: String): Run =
        get("v1/agents/$agentId/runs/$runId", Run.serializer())

    suspend fun createRun(agentId: String, request: CreateRunRequest): CreateRunResponse =
        post(
            path = "v1/agents/$agentId/runs",
            body = encode(CreateRunRequest.serializer(), request),
            serializer = CreateRunResponse.serializer(),
        )

    suspend fun cancelRun(agentId: String, runId: String): IdResponse =
        post(
            path = "v1/agents/$agentId/runs/$runId/cancel",
            body = EMPTY_BODY,
            serializer = IdResponse.serializer(),
        )

    suspend fun listModels(): ModelListResponse = get("v1/models", ModelListResponse.serializer())


    suspend fun listRepositories(): RepositoryListResponse =
        get("v1/repositories", RepositoryListResponse.serializer())

    fun authHeader(): String = "Bearer " + (apiKeyProvider() ?: throw MissingApiKeyException())

    fun streamUrl(agentId: String, runId: String): HttpUrl =
        url("v1/agents/$agentId/runs/$runId/stream", emptyMap())

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(JSON_MEDIA_TYPE)

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): T = execute(Request.Builder().url(url(path, query)).get(), serializer)

    private suspend fun <T> post(
        path: String,
        body: RequestBody,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): T = execute(Request.Builder().url(url(path, query)).post(body), serializer)

    private suspend fun <T> execute(
        builder: Request.Builder,
        serializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val request = builder
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw parseError(response.code, text)

            try {
                json.decodeFromString(serializer, text)
            } catch (cause: Exception) {
                throw CursorApiException(
                    statusCode = response.code,
                    errorCode = null,
                    message = "Respons tidak bisa dibaca: ${cause.message}",
                )
            }
        }
    }

    private fun url(path: String, query: Map<String, String>): HttpUrl {
        val builder = BASE_URL.toHttpUrl().newBuilder()
        path.trim('/').split('/').forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /**
     * Error bodies are not fully specified, so both `{ "error": { ... } }` and a flat
     * `{ "code": ..., "message": ... }` are accepted before falling back to the raw text.
     */
    private fun parseError(statusCode: Int, body: String): CursorApiException {
        val fallback = body.take(300).ifBlank { "HTTP $statusCode" }

        val payload: JsonObject? = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            (root["error"] as? JsonObject) ?: root
        }.getOrNull()

        val code = payload?.get("code")?.runCatching { jsonPrimitive.content }?.getOrNull()
        val message = payload?.get("message")?.runCatching { jsonPrimitive.content }?.getOrNull()

        return CursorApiException(
            statusCode = statusCode,
            errorCode = code,
            message = message ?: fallback,
        )
    }

    companion object {
        const val BASE_URL = "https://api.cursor.com/"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
    }
}
