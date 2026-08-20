package dev.aifih.volare.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MeResponse(
    val apiKeyName: String? = null,
    val userEmail: String? = null,
    val userFirstName: String? = null,
    val userId: Int? = null,
)

@Serializable
data class AgentEnv(
    val type: String? = null,
    val name: String? = null,
)

@Serializable
data class RepoRef(
    val url: String,
    val startingRef: String? = null,
    val prUrl: String? = null,
)

@Serializable
data class Agent(
    val id: String,
    val name: String? = null,
    val status: String? = null,
    val env: AgentEnv? = null,
    val repos: List<RepoRef> = emptyList(),
    val autoCreatePR: Boolean? = null,
    val workOnCurrentBranch: Boolean? = null,
    val url: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val latestRunId: String? = null,
)

@Serializable
data class AgentListResponse(
    val items: List<Agent> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class PromptImage(
    val data: String? = null,
    val mimeType: String? = null,
    val url: String? = null,
)

@Serializable
data class Prompt(
    val text: String,
    val images: List<PromptImage>? = null,
)

@Serializable
data class ModelParam(
    val id: String,
    val value: String,
)

@Serializable
data class ModelSelection(
    val id: String,
    val params: List<ModelParam>? = null,
)

@Serializable
data class CreateAgentRequest(
    val prompt: Prompt,
    val model: ModelSelection? = null,
    val repos: List<RepoRef>? = null,
    val autoCreatePR: Boolean? = null,
    val mode: String? = null,
    val name: String? = null,
)

@Serializable
data class CreateAgentResponse(
    val agent: Agent,
    val run: Run,
)

@Serializable
data class GitBranch(
    val repoUrl: String? = null,
    val branch: String? = null,
    val prUrl: String? = null,
)

@Serializable
data class GitInfo(
    val branches: List<GitBranch> = emptyList(),
)

@Serializable
data class Run(
    val id: String,
    val agentId: String? = null,
    val status: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val durationMs: Long? = null,
    val result: String? = null,
    val git: GitInfo? = null,
)

@Serializable
data class RunListResponse(
    val items: List<Run> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class CreateRunRequest(
    val prompt: Prompt,
    val mode: String? = null,
)

@Serializable
data class CreateRunResponse(
    val run: Run,
)

@Serializable
data class ModelParamValue(
    val value: String,
    val displayName: String? = null,
)

@Serializable
data class ModelParameter(
    val id: String,
    val displayName: String? = null,
    val values: List<ModelParamValue> = emptyList(),
)

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val parameters: List<ModelParameter> = emptyList(),
) {
    val label: String get() = displayName ?: id
}

@Serializable
data class ModelListResponse(
    val items: List<ModelInfo> = emptyList(),
)

@Serializable
data class RepositoryItem(
    val url: String,
) {
    val shortName: String
        get() = url.removeSuffix("/")
            .removeSuffix(".git")
            .split("/")
            .takeLast(2)
            .joinToString("/")
}

@Serializable
data class RepositoryListResponse(
    val items: List<RepositoryItem> = emptyList(),
)

@Serializable
data class IdResponse(
    val id: String,
)


object RunStatus {
    const val CREATING = "CREATING"
    const val RUNNING = "RUNNING"
    const val FINISHED = "FINISHED"
    const val ERROR = "ERROR"
    const val CANCELLED = "CANCELLED"
    const val EXPIRED = "EXPIRED"

    private val terminal = setOf(FINISHED, ERROR, CANCELLED, EXPIRED)

    fun isTerminal(status: String?): Boolean = status != null && status.uppercase() in terminal
}

@Serializable
data class SseStatusPayload(
    val runId: String? = null,
    val status: String,
)

@Serializable
data class SseTextPayload(
    val text: String = "",
)

@Serializable
data class SseToolCallPayload(
    val callId: String,
    val name: String,
    val status: String,
)

@Serializable
data class SseResultPayload(
    val runId: String? = null,
    val status: String,
    val text: String? = null,
    val durationMs: Long? = null,
    val git: GitInfo? = null,
)

@Serializable
data class SseErrorPayload(
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
)

enum class AgentMode(val apiValue: String?, val label: String) {
    AGENT(null, "Agent"),
    PLAN("plan", "Plan first"),
    ;

    companion object {
        val selectableModes: List<AgentMode> = entries

        fun fromString(value: String?): AgentMode = when (value) {
            PLAN.apiValue -> PLAN
            "ask" -> AGENT
            else -> AGENT
        }
    }
}

enum class AgentKind(val prefsValue: String, val label: String) {
    CODING("coding", "Coding"),
    GENERAL("general", "General"),
    ;

    companion object {
        val selectableKinds: List<AgentKind> = entries

        fun fromString(value: String?): AgentKind = when (value) {
            GENERAL.prefsValue -> GENERAL
            else -> CODING
        }
    }
}
