package dev.aifih.volare.data

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class RefreshTooSoonException(val retryInSeconds: Long) :
    Exception("Wait $retryInSeconds more seconds before refreshing the repository list")


class AgentRepository(
    private val api: CursorApi,
    private val prefs: SharedPreferences,
    private val json: Json,
    private val transcriptStore: AgentTranscriptStore,
) {

    fun cachedRepositories(): List<RepositoryItem> =
        decode(KEY_REPOS, ListSerializer(RepositoryItem.serializer()))

    fun repositoriesFetchedAt(): Long = prefs.getLong(KEY_REPOS_AT, 0L)

    suspend fun refreshRepositories(force: Boolean): List<RepositoryItem> {
        val elapsed = System.currentTimeMillis() - repositoriesFetchedAt()
        val cached = cachedRepositories()

        if (!force && cached.isNotEmpty() && elapsed < REPOS_TTL_MS) return cached

        if (elapsed < REPOS_MIN_INTERVAL_MS) {
            throw RefreshTooSoonException(((REPOS_MIN_INTERVAL_MS - elapsed) / 1000) + 1)
        }

        val items = api.listRepositories().items
        store(KEY_REPOS, KEY_REPOS_AT, ListSerializer(RepositoryItem.serializer()), items)
        return items
    }

    fun cachedModels(): List<ModelInfo> =
        decode(KEY_MODELS, ListSerializer(ModelInfo.serializer()))

    suspend fun refreshModels(force: Boolean = false): List<ModelInfo> {
        val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_MODELS_AT, 0L)
        val cached = cachedModels()

        if (!force && cached.isNotEmpty() && elapsed < MODELS_TTL_MS) return cached

        val items = api.listModels().items
        store(KEY_MODELS, KEY_MODELS_AT, ListSerializer(ModelInfo.serializer()), items)
        return items
    }

    var lastRepoUrl: String?
        get() = prefs.getString(KEY_LAST_REPO, null)
        set(value) = prefs.edit().putString(KEY_LAST_REPO, value).apply()

    var lastModelId: String?
        get() = prefs.getString(KEY_LAST_MODEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_MODEL, value).apply()

    var lastMode: AgentMode
        get() = AgentMode.fromString(prefs.getString(KEY_LAST_MODE, null))
        set(value) = prefs.edit().putString(KEY_LAST_MODE, value.apiValue).apply()

    var lastAgentKind: AgentKind
        get() = AgentKind.fromString(prefs.getString(KEY_LAST_KIND, null))
        set(value) = prefs.edit().putString(KEY_LAST_KIND, value.prefsValue).apply()

    /** When false, the agents list hides deactivated (ARCHIVED) agents. */
    var showDeactivatedAgents: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEACTIVATED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DEACTIVATED, value).apply()

    fun loadTranscript(agentId: String): String? = transcriptStore.load(agentId)

    fun saveTranscript(agentId: String, text: String) = transcriptStore.save(agentId, text)

    fun deleteTranscript(agentId: String) = transcriptStore.delete(agentId)

    suspend fun me(): MeResponse = api.me()

    suspend fun listAgents(cursor: String? = null): AgentListResponse = api.listAgents(cursor = cursor)

    suspend fun getAgent(agentId: String): Agent = api.getAgent(agentId)

    suspend fun listRuns(agentId: String): List<Run> = api.listRuns(agentId).items

    suspend fun getRun(agentId: String, runId: String): Run = api.getRun(agentId, runId)

    suspend fun createAgent(request: CreateAgentRequest): CreateAgentResponse =
        api.createAgent(request)

    suspend fun createRun(
        agentId: String,
        prompt: String,
        mode: String? = null,
    ): Run = api.createRun(
        agentId,
        CreateRunRequest(Prompt(prompt), mode),
    ).run

    suspend fun cancelRun(agentId: String, runId: String) {
        api.cancelRun(agentId, runId)
    }

    suspend fun archiveAgent(agentId: String) {
        api.archiveAgent(agentId)
        transcriptStore.delete(agentId)
    }

    /**
     * Everything cached here is derived from one account, so it has to go when the key does.
     * Otherwise the next person to sign in on this device sees the previous account's
     * repositories until they refresh.
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        transcriptStore.clear()
    }

    private fun <T> decode(
        key: String,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
    ): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun <T> store(
        key: String,
        timestampKey: String,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
        items: List<T>,
    ) {
        prefs.edit()
            .putString(key, json.encodeToString(serializer, items))
            .putLong(timestampKey, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val KEY_REPOS = "cache_repos"
        const val KEY_REPOS_AT = "cache_repos_at"
        const val KEY_MODELS = "cache_models"
        const val KEY_MODELS_AT = "cache_models_at"
        const val KEY_LAST_REPO = "last_repo_url"
        const val KEY_LAST_MODEL = "last_model_id"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_LAST_KIND = "last_agent_kind"
        const val KEY_SHOW_DEACTIVATED = "show_deactivated_agents"

        const val REPOS_MIN_INTERVAL_MS = 65_000L
        const val REPOS_TTL_MS = 6 * 60 * 60 * 1000L
        const val MODELS_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
