package dev.aifih.volare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aifih.volare.ServiceLocator
import dev.aifih.volare.data.AgentMode
import dev.aifih.volare.data.CreateAgentRequest
import dev.aifih.volare.data.ModelInfo
import dev.aifih.volare.data.ModelSelection
import dev.aifih.volare.data.Prompt
import dev.aifih.volare.data.RefreshTooSoonException
import dev.aifih.volare.data.RepoRef
import dev.aifih.volare.data.RepositoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewAgentUiState(
    val prompt: String = "",
    val repos: List<RepositoryItem> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val selectedRepoUrl: String? = null,
    val selectedModelId: String? = null,
    val startingRef: String = "main",
    val autoCreatePr: Boolean = true,
    val selectedMode: AgentMode = AgentMode.AGENT,
    val refreshingRepos: Boolean = false,
    val submitting: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

class NewAgentViewModel : ViewModel() {

    private val repository = ServiceLocator.repository

    private val _state = MutableStateFlow(
        NewAgentUiState(
            repos = repository.cachedRepositories(),
            models = repository.cachedModels(),
            selectedRepoUrl = repository.lastRepoUrl,
            selectedModelId = repository.lastModelId,
            autoCreatePr = repository.lastAutoCreatePr,
            selectedMode = repository.lastMode,
        ),
    )
    val state: StateFlow<NewAgentUiState> = _state.asStateFlow()

    fun onPromptChange(value: String) = _state.update { it.copy(prompt = value, error = null) }

    fun onStartingRefChange(value: String) = _state.update { it.copy(startingRef = value) }

    fun onRepoSelected(url: String) {
        repository.lastRepoUrl = url
        _state.update { it.copy(selectedRepoUrl = url) }
    }

    fun onModelSelected(id: String?) {
        repository.lastModelId = id
        _state.update { it.copy(selectedModelId = id) }
    }

    fun onAutoCreatePrChange(value: Boolean) {
        repository.lastAutoCreatePr = value
        _state.update { it.copy(autoCreatePr = value) }
    }

    fun onModeSelected(mode: AgentMode) {
        repository.lastMode = mode
        _state.update { it.copy(selectedMode = mode) }
    }

    fun loadInitial() {
        viewModelScope.launch {
            runCatching { repository.refreshModels() }
                .onSuccess { models -> _state.update { it.copy(models = models) } }

            runCatching { repository.refreshRepositories(force = false) }
                .onSuccess { repos -> _state.update { it.copy(repos = repos) } }
                .onFailure { cause -> if (cause !is RefreshTooSoonException) reportRepoFailure(cause) }
        }
    }

    fun refreshRepos() {
        if (_state.value.refreshingRepos) return
        _state.update { it.copy(refreshingRepos = true, notice = null, error = null) }

        viewModelScope.launch {
            runCatching { repository.refreshRepositories(force = true) }.fold(
                onSuccess = { repos ->
                    _state.update {
                        it.copy(
                            repos = repos,
                            refreshingRepos = false,
                            notice = "Repository list updated (${repos.size})",
                        )
                    }
                },
                onFailure = { cause ->
                    _state.update { it.copy(refreshingRepos = false) }
                    if (cause is RefreshTooSoonException) {
                        _state.update {
                            it.copy(
                                notice = "The repository endpoint allows 1 request per minute. " +
                                    "Try again in ${cause.retryInSeconds} seconds.",
                            )
                        }
                    } else {
                        reportRepoFailure(cause)
                    }
                },
            )
        }
    }

    fun submit(onCreated: (String) -> Unit) {
        val current = _state.value
        if (current.prompt.isBlank()) {
            _state.update { it.copy(error = "Prompt is empty") }
            return
        }
        if (current.selectedRepoUrl.isNullOrBlank()) {
            _state.update { it.copy(error = "Pick a repository first") }
            return
        }

        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val request = CreateAgentRequest(
                prompt = Prompt(current.prompt.trim()),
                model = current.selectedModelId?.let { ModelSelection(it) },
                repos = listOf(
                    RepoRef(
                        url = current.selectedRepoUrl,
                        startingRef = current.startingRef.trim().ifBlank { null },
                    ),
                ),
                autoCreatePR = current.autoCreatePr,
                mode = current.selectedMode.apiValue,
            )

            runCatching { repository.createAgent(request) }.fold(
                onSuccess = { response ->
                    _state.update { it.copy(submitting = false) }
                    onCreated(response.agent.id)
                },
                onFailure = { cause ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = cause.message ?: "Could not create the agent",
                        )
                    }
                },
            )
        }
    }

    private fun reportRepoFailure(cause: Throwable) {
        _state.update { it.copy(error = cause.message ?: "Could not load the repository list") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val viewModel: NewAgentViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadInitial() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChange,
                label = { Text("Prompt") },
                placeholder = { Text("For example: fix login validation and add tests") },
                minLines = 4,
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repository", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::refreshRepos, enabled = !state.refreshingRepos) {
                    if (state.refreshingRepos) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh repository list")
                    }
                }
            }

            PickerField(
                label = state.repos
                    .firstOrNull { it.url == state.selectedRepoUrl }
                    ?.shortName
                    ?: state.selectedRepoUrl
                    ?: "Pick a repository",
                options = state.repos.map { it.shortName to it.url },
                onSelect = { url -> url?.let(viewModel::onRepoSelected) },
            )

            if (state.repos.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The repository list is not cached yet. Tap refresh once and wait — " +
                        "this endpoint can take tens of seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.startingRef,
                onValueChange = viewModel::onStartingRefChange,
                label = { Text("Starting branch") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Text("Model", style = MaterialTheme.typography.labelLarge)

            PickerField(
                label = state.models
                    .firstOrNull { it.id == state.selectedModelId }
                    ?.label
                    ?: "Account default",
                options = listOf("Account default" to null) +
                    state.models.map { it.label to it.id },
                onSelect = viewModel::onModelSelected,
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Create PR automatically", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Recommended: a PR is far easier to review from a phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoCreatePr,
                    onCheckedChange = viewModel::onAutoCreatePrChange,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.selectedMode == mode,
                        onClick = { viewModel.onModeSelected(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            state.notice?.let { notice ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.submit(onCreated) },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.submitting) "Sending…" else "Run agent")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    options: List<Pair<String, String?>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, modifier = Modifier.fillMaxWidth())
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No options yet") },
                    onClick = { expanded = false },
                )
            }

            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
