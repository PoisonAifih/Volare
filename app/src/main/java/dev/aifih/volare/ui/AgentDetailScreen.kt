package dev.aifih.volare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.aifih.volare.ServiceLocator
import dev.aifih.volare.data.Agent
import dev.aifih.volare.data.AgentMode
import dev.aifih.volare.data.AgentStatus
import dev.aifih.volare.data.CursorApiException
import dev.aifih.volare.data.GitBranch
import dev.aifih.volare.data.Run
import dev.aifih.volare.data.RunEvent
import dev.aifih.volare.data.RunStatus
import dev.aifih.volare.service.RunWatchService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ToolLine(val callId: String, val name: String, val status: String)

enum class AgentDetailRetry {
    Reload,
    ResendFollowUp,
    ReconnectStream,
    CreatePullRequest,
}

data class AgentDetailUiState(
    val agent: Agent? = null,
    val run: Run? = null,
    val status: String? = null,
    val transcript: String = "",
    val tools: List<ToolLine> = emptyList(),
    val followUp: String = "",
    val selectedMode: AgentMode = AgentMode.AGENT,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val creatingPr: Boolean = false,
    val cancelling: Boolean = false,
    val archiving: Boolean = false,
    val showCreatePrDialog: Boolean = false,
    val showArchiveDialog: Boolean = false,
    /**
     * True after the user sends a follow-up once a PR already exists.
     * Cleared when a new PR run is started, so Create PR stays hidden
     * until there is fresh work to publish.
     */
    val hasChangesSincePr: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val retry: AgentDetailRetry? = null,
) {
    val isActive: Boolean get() = status != null && !RunStatus.isTerminal(status)

    val agentName: String get() = agent?.name ?: agent?.id ?: "Agent"

    val isArchived: Boolean get() = AgentStatus.isArchived(agent?.status)

    val canArchive: Boolean get() = agent != null && !isArchived && !archiving

    val isRepoLess: Boolean get() = agent?.repos.isNullOrEmpty()

    val gitBranches: List<GitBranch> get() = run?.git?.branches.orEmpty()

    val branchesWithPr: List<GitBranch> get() = gitBranches.filter { !it.prUrl.isNullOrBlank() }

    val hasExistingPr: Boolean get() = branchesWithPr.isNotEmpty()

    val branch: String? get() = gitBranches.lastOrNull { !it.branch.isNullOrBlank() }?.branch
        ?: gitBranches.firstOrNull()?.branch

    /** Show Create PR when there is a branch and either no PR yet, or new work since the last PR. */
    val canCreatePr: Boolean
        get() = !isRepoLess && !isActive && branch != null && !creatingPr &&
            (!hasExistingPr || hasChangesSincePr)

    val reviewSummary: String
        get() {
            val result = run?.result?.trim().orEmpty()
            if (result.isNotEmpty()) return result
            val text = transcript.trim()
            if (text.isEmpty()) return "No summary yet."
            return if (text.length <= 500) text else "…" + text.takeLast(500)
        }
}

class AgentDetailViewModel(private val agentId: String) : ViewModel() {

    private val repository = ServiceLocator.repository

    private val _state = MutableStateFlow(
        AgentDetailUiState(selectedMode = repository.lastMode)
    )
    val state: StateFlow<AgentDetailUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private val transcript = StringBuilder()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null, retry = null) }

        viewModelScope.launch {
            val agent = runCatching { repository.getAgent(agentId) }.getOrElse { cause ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = cause.message ?: "Could not load the agent",
                        retry = if (cause.isRetryableNetworkFailure()) {
                            AgentDetailRetry.Reload
                        } else {
                            null
                        },
                    )
                }
                return@launch
            }

            _state.update { it.copy(agent = agent, loading = false) }

            if (AgentStatus.isArchived(agent.status)) {
                // Deactivated agents must not keep a local chat cache.
                repository.deleteTranscript(agentId)
                transcript.clear()
                _state.update { it.copy(transcript = "") }
            } else {
                restoreCachedTranscript()
            }

            val runId = agent.latestRunId
            if (runId == null) {
                _state.update { it.copy(status = null) }
                return@launch
            }

            val run = runCatching { repository.getRun(agentId, runId) }.getOrNull()
            if (run != null) {
                _state.update { it.copy(run = run, status = run.status) }

                if (RunStatus.isTerminal(run.status)) {
                    if (transcript.isBlank()) {
                        run.result?.let { appendTranscript(it) }
                    } else {
                        persistTranscriptIfActive()
                    }
                } else {
                    // Live SSE may replay from the start; drop cache to avoid duplicates.
                    clearTranscriptBuffer()
                    startStreaming(runId)
                }
            }
        }
    }

    /** Reload agent + run and reconnect the stream, like leaving and re-opening this screen. */
    fun refresh() {
        if (_state.value.loading) return

        streamJob?.cancel()
        streamJob = null
        transcript.clear()
        _state.update {
            it.copy(
                transcript = "",
                tools = emptyList(),
                notice = null,
                showCreatePrDialog = false,
                showArchiveDialog = false,
                hasChangesSincePr = false,
            )
        }
        load()
    }

    private fun startStreaming(runId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var settled = false

            ServiceLocator.runStream.stream(agentId, runId).collect { event ->
                when (event) {
                    is RunEvent.Status -> _state.update { it.copy(status = event.status) }

                    is RunEvent.Assistant -> appendTranscript(event.text)

                    is RunEvent.Thinking -> Unit

                    is RunEvent.Tool -> _state.update { current ->
                        val existing = current.tools.indexOfFirst { it.callId == event.callId }
                        val line = ToolLine(event.callId, event.name, event.status)

                        val tools = if (existing >= 0) {
                            current.tools.toMutableList().also { it[existing] = line }
                        } else {
                            current.tools + line
                        }

                        current.copy(tools = tools.takeLast(MAX_TOOL_LINES))
                    }

                    is RunEvent.Completed -> {
                        settled = true
                        event.text?.let { appendTranscript("\n\n$it") }
                        _state.update { it.copy(status = event.status) }
                        reloadRun(runId)
                    }

                    is RunEvent.Failed -> {
                        settled = true
                        settle(runId, if (event.expired) null else event.message)
                    }
                }
            }

            if (!settled) settle(runId, null)
        }
    }

    private suspend fun reloadRun(runId: String) {
        val run = runCatching { repository.getRun(agentId, runId) }.getOrNull() ?: return
        _state.update { it.copy(run = run, status = run.status) }
    }

    private suspend fun settle(runId: String, streamError: String?) {
        val run = runCatching { repository.getRun(agentId, runId) }.getOrNull()
        val terminal = run != null && RunStatus.isTerminal(run.status)

        if (terminal && transcript.isBlank()) {
            run.result?.let { appendTranscript(it) }
        } else if (terminal) {
            persistTranscriptIfActive()
        }

        _state.update {
            it.copy(
                run = run ?: it.run,
                status = run?.status ?: it.status,
                error = if (terminal) {
                    null
                } else {
                    streamError ?: it.error
                },
                retry = if (terminal || streamError == null) {
                    null
                } else if (streamError.isRetryableNetworkFailure()) {
                    AgentDetailRetry.ReconnectStream
                } else {
                    null
                },
            )
        }
    }

    private fun restoreCachedTranscript() {
        val cached = repository.loadTranscript(agentId) ?: return
        transcript.clear()
        transcript.append(cached)
        _state.update { it.copy(transcript = cached) }
    }

    private fun clearTranscriptBuffer() {
        transcript.clear()
        _state.update { it.copy(transcript = "") }
    }

    private fun appendTranscript(text: String) {
        transcript.append(text)
        _state.update { it.copy(transcript = transcript.toString()) }
        persistTranscriptIfActive()
    }

    private fun persistTranscriptIfActive() {
        if (AgentStatus.isArchived(_state.value.agent?.status)) return
        repository.saveTranscript(agentId, transcript.toString())
    }

    override fun onCleared() {
        persistTranscriptIfActive()
        super.onCleared()
    }

    fun onFollowUpChange(value: String) =
        _state.update { it.copy(followUp = value, notice = null, error = null, retry = null) }

    fun onModeSelected(mode: AgentMode) {
        repository.lastMode = mode
        _state.update { it.copy(selectedMode = mode) }
    }

    fun openCreatePrDialog() {
        val current = _state.value
        if (!current.canCreatePr) return
        _state.update { it.copy(showCreatePrDialog = true, notice = null, error = null) }
    }

    fun dismissCreatePrDialog() {
        _state.update { it.copy(showCreatePrDialog = false) }
    }

    fun confirmCreatePullRequest() {
        _state.update { it.copy(showCreatePrDialog = false) }
        createPullRequest()
    }

    fun sendFollowUp() {
        val current = _state.value
        val prompt = current.followUp.trim()
        if (prompt.isEmpty() || current.sending) return

        _state.update { it.copy(sending = true, error = null, notice = null, retry = null) }

        val mode = if (current.isRepoLess) null else current.selectedMode.apiValue

        viewModelScope.launch {
            runCatching { repository.createRun(agentId, prompt, mode) }.fold(
                onSuccess = { run ->
                    appendTranscript("\n\n> $prompt\n\n")
                    _state.update {
                        it.copy(
                            sending = false,
                            followUp = "",
                            run = run,
                            status = run.status,
                            tools = emptyList(),
                            // A follow-up may produce new commits; allow Create PR again
                            // even if an earlier PR already exists.
                            hasChangesSincePr = true,
                        )
                    }
                    startStreaming(run.id)
                },
                onFailure = { cause ->
                    val busy = cause is CursorApiException && cause.isAgentBusy
                    _state.update {
                        it.copy(
                            sending = false,
                            notice = if (busy) {
                                "The agent is still working on the previous run. " +
                                    "Wait for it to finish or cancel it first."
                            } else {
                                null
                            },
                            error = if (busy) {
                                null
                            } else {
                                cause.message ?: "Could not send the follow-up"
                            },
                            retry = if (!busy && cause.isRetryableNetworkFailure()) {
                                AgentDetailRetry.ResendFollowUp
                            } else {
                                null
                            },
                        )
                    }
                },
            )
        }
    }

    fun createPullRequest() {
        val current = _state.value
        if (!current.canCreatePr || current.creatingPr) return

        _state.update {
            it.copy(
                creatingPr = true,
                showCreatePrDialog = false,
                error = null,
                notice = null,
                retry = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                repository.createRun(
                    agentId = agentId,
                    prompt = CREATE_PR_PROMPT,
                )
            }.fold(
                onSuccess = { run ->
                    appendTranscript("\n\n> Create pull request\n\n")
                    _state.update {
                        it.copy(
                            creatingPr = false,
                            hasChangesSincePr = false,
                            run = run,
                            status = run.status,
                            tools = emptyList(),
                        )
                    }
                    startStreaming(run.id)
                },
                onFailure = { cause ->
                    val busy = cause is CursorApiException && cause.isAgentBusy
                    _state.update {
                        it.copy(
                            creatingPr = false,
                            notice = if (busy) {
                                "The agent is still working. Wait for it to finish or cancel it first."
                            } else {
                                null
                            },
                            error = if (busy) {
                                null
                            } else {
                                cause.message ?: "Could not create the pull request"
                            },
                            retry = if (!busy && cause.isRetryableNetworkFailure()) {
                                AgentDetailRetry.CreatePullRequest
                            } else {
                                null
                            },
                        )
                    }
                },
            )
        }
    }

    fun retry() {
        when (_state.value.retry) {
            AgentDetailRetry.Reload -> load()

            AgentDetailRetry.ResendFollowUp -> sendFollowUp()

            AgentDetailRetry.CreatePullRequest -> openCreatePrDialog()

            AgentDetailRetry.ReconnectStream -> {
                val runId = _state.value.run?.id ?: return
                _state.update { it.copy(error = null, retry = null) }
                startStreaming(runId)
            }

            null -> Unit
        }
    }

    fun cancel() {
        val runId = _state.value.run?.id ?: return
        _state.update { it.copy(cancelling = true) }

        viewModelScope.launch {
            runCatching { repository.cancelRun(agentId, runId) }
                .onFailure { cause ->
                    _state.update { it.copy(error = cause.message) }
                }

            _state.update { it.copy(cancelling = false) }
            reloadRun(runId)
        }
    }

    fun openArchiveDialog() {
        if (!_state.value.canArchive) return
        _state.update { it.copy(showArchiveDialog = true, error = null, notice = null) }
    }

    fun dismissArchiveDialog() {
        _state.update { it.copy(showArchiveDialog = false) }
    }

    fun confirmArchive() {
        if (!_state.value.canArchive) return

        _state.update {
            it.copy(
                showArchiveDialog = false,
                archiving = true,
                error = null,
                notice = null,
            )
        }

        streamJob?.cancel()
        streamJob = null

        viewModelScope.launch {
            runCatching { repository.archiveAgent(agentId) }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            archiving = false,
                            agent = it.agent?.copy(status = AgentStatus.ARCHIVED),
                            notice = "Agent deactivated. It stays in your list but will not accept new runs.",
                        )
                    }
                },
                onFailure = { cause ->
                    _state.update {
                        it.copy(
                            archiving = false,
                            error = cause.message ?: "Could not archive the agent",
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val MAX_TOOL_LINES = 6
        const val CREATE_PR_PROMPT =
            "Open a pull request for the current branch with all committed changes. " +
                "Do not modify any code."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(agentId: String, onBack: () -> Unit) {
    val viewModel: AgentDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { AgentDetailViewModel(agentId) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val latest by rememberUpdatedState(state)

    LaunchedEffect(Unit) { RunWatchService.stop(context) }

    DisposableEffect(Unit) {
        onDispose {
            val current = latest
            val runId = current.run?.id
            if (current.isActive && runId != null && !current.isArchived) {
                RunWatchService.start(context, agentId, runId, current.agentName)
            }
        }
    }

    if (state.showCreatePrDialog) {
        CreatePrReviewDialog(
            branch = state.branch,
            summary = state.reviewSummary,
            tools = state.tools,
            transcript = state.transcript,
            existingPrCount = state.branchesWithPr.size,
            onConfirm = viewModel::confirmCreatePullRequest,
            onDismiss = viewModel::dismissCreatePrDialog,
        )
    }

    if (state.showArchiveDialog) {
        ArchiveAgentDialog(
            agentName = state.agentName,
            onConfirm = viewModel::confirmArchive,
            onDismiss = viewModel::dismissArchiveDialog,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.agentName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    if (state.canArchive) {
                        TextButton(
                            onClick = viewModel::openArchiveDialog,
                            enabled = !state.archiving,
                        ) {
                            Text(if (state.archiving) "Deactivating…" else "Deactivate")
                        }
                    }
                    if (state.transcript.isNotBlank()) {
                        TextButton(
                            onClick = { context.copyText("Transcript", state.transcript) },
                        ) {
                            Text("Copy")
                        }
                    }
                    state.agent?.url?.let { url ->
                        TextButton(onClick = { context.openUrl(url) }) {
                            Text("Web")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.isArchived) {
                FollowUpBar(
                    value = state.followUp,
                    selectedMode = state.selectedMode,
                    showModeChips = !state.isRepoLess,
                    sending = state.sending,
                    onValueChange = viewModel::onFollowUpChange,
                    onModeSelected = viewModel::onModeSelected,
                    onSend = viewModel::sendFollowUp,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    if (state.isArchived) AgentStatus.ARCHIVED else state.status,
                )
                Spacer(Modifier.weight(1f))

                formatDuration(state.run?.durationMs)?.let { duration ->
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.isActive) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = viewModel::cancel,
                        enabled = !state.cancelling,
                    ) {
                        Text(if (state.cancelling) "Cancelling…" else "Cancel")
                    }
                }
            }

            state.notice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                RetryableError(
                    message = error,
                    onRetry = state.retry?.let { { viewModel.retry() } },
                )
            }

            if (state.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ToolActivity(state.tools)
            }

            if (!state.isRepoLess &&
                (state.branch != null || state.branchesWithPr.isNotEmpty() || state.canCreatePr)
            ) {
                Spacer(Modifier.height(8.dp))
                ResultLinksSection(
                    branch = state.branch,
                    branchesWithPr = state.branchesWithPr,
                    canCreatePr = state.canCreatePr,
                    creatingPr = state.creatingPr,
                    onCreatePr = viewModel::openCreatePrDialog,
                    onOpenPr = { url -> context.openUrl(url) },
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Transcript(
                text = state.transcript,
                active = state.isActive,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ArchiveAgentDialog(
    agentName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deactivate this agent?") },
        text = {
            Text(
                "“$agentName” will stop accepting new runs. " +
                    "It stays in your list as archived.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Deactivate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CreatePrReviewDialog(
    branch: String?,
    summary: String,
    tools: List<ToolLine>,
    transcript: String,
    existingPrCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create pull request?") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                branch?.let {
                    Text(
                        text = "Branch: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                if (existingPrCount > 0) {
                    Text(
                        text = "This agent already has $existingPrCount open pull request(s). " +
                            "Creating another will start a new PR run.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Summary", style = MaterialTheme.typography.labelLarge)
                Text(summary, style = MaterialTheme.typography.bodySmall)

                if (tools.isNotEmpty()) {
                    Text("Recent tools", style = MaterialTheme.typography.labelLarge)
                    tools.forEach { tool ->
                        Text(
                            text = "${if (tool.status == "completed") "✓" else "…"} ${tool.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                if (transcript.isNotBlank()) {
                    Text("Transcript", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Create pull request")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun Transcript(text: String, active: Boolean, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    LaunchedEffect(text.length) {
        if (active) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        Text(
            text = text.ifBlank {
                if (active) "Waiting for the agent to start talking…" else "No transcript yet."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToolActivity(tools: List<ToolLine>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tools.forEach { tool ->
                Text(
                    text = "${if (tool.status == "completed") "✓" else "…"} ${tool.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResultLinksSection(
    branch: String?,
    branchesWithPr: List<GitBranch>,
    canCreatePr: Boolean,
    creatingPr: Boolean,
    onCreatePr: () -> Unit,
    onOpenPr: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        branch?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        branchesWithPr.forEach { entry ->
            val url = entry.prUrl ?: return@forEach
            OutlinedButton(onClick = { onOpenPr(url) }) {
                Text(
                    text = entry.branch?.let { "Open PR ($it)" } ?: "Open pull request",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (canCreatePr) {
            Button(onClick = onCreatePr, enabled = !creatingPr) {
                Text(if (creatingPr) "Creating pull request…" else "Create pull request")
            }
        }
    }
}

@Composable
private fun FollowUpBar(
    value: String,
    selectedMode: AgentMode,
    showModeChips: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (showModeChips) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentMode.selectableModes.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Send a follow-up…") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onSend, enabled = !sending && value.isNotBlank()) {
                if (sending) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
