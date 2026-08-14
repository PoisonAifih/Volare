package dev.aifih.onthefly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.aifih.onthefly.ServiceLocator
import dev.aifih.onthefly.data.CreateAgentRequest
import dev.aifih.onthefly.data.ModelInfo
import dev.aifih.onthefly.data.ModelSelection
import dev.aifih.onthefly.data.Prompt
import dev.aifih.onthefly.data.RefreshTooSoonException
import dev.aifih.onthefly.data.RepoRef
import dev.aifih.onthefly.data.RepositoryItem
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
    val planMode: Boolean = false,
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

    fun onPlanModeChange(value: Boolean) = _state.update { it.copy(planMode = value) }

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
                            notice = "Daftar repo diperbarui (${repos.size})",
                        )
                    }
                },
                onFailure = { cause ->
                    _state.update { it.copy(refreshingRepos = false) }
                    if (cause is RefreshTooSoonException) {
                        _state.update {
                            it.copy(
                                notice = "Endpoint repo dibatasi 1 permintaan per menit. " +
                                    "Coba lagi dalam ${cause.retryInSeconds} detik.",
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
            _state.update { it.copy(error = "Prompt masih kosong") }
            return
        }
        if (current.selectedRepoUrl.isNullOrBlank()) {
            _state.update { it.copy(error = "Pilih repo dulu") }
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
                mode = if (current.planMode) "plan" else null,
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
                            error = cause.message ?: "Gagal membuat agent",
                        )
                    }
                },
            )
        }
    }

    private fun reportRepoFailure(cause: Throwable) {
        _state.update { it.copy(error = cause.message ?: "Gagal memuat daftar repo") }
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
                title = { Text("Agent baru") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChange,
                label = { Text("Prompt") },
                placeholder = { Text("Contoh: perbaiki validasi login lalu tambahkan test") },
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
                        Icon(Icons.Filled.Refresh, contentDescription = "Segarkan daftar repo")
                    }
                }
            }

            PickerField(
                label = state.repos
                    .firstOrNull { it.url == state.selectedRepoUrl }
                    ?.shortName
                    ?: state.selectedRepoUrl
                    ?: "Pilih repo",
                options = state.repos.map { it.shortName to it.url },
                onSelect = { url -> url?.let(viewModel::onRepoSelected) },
            )

            if (state.repos.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Daftar repo belum ter-cache. Tekan ikon segarkan sekali, lalu " +
                        "tunggu — endpoint ini bisa perlu puluhan detik.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.startingRef,
                onValueChange = viewModel::onStartingRefChange,
                label = { Text("Branch awal") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Text("Model", style = MaterialTheme.typography.labelLarge)

            PickerField(
                label = state.models
                    .firstOrNull { it.id == state.selectedModelId }
                    ?.label
                    ?: "Default akun",
                options = listOf("Default akun" to null) +
                    state.models.map { it.label to it.id },
                onSelect = viewModel::onModelSelected,
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Buat PR otomatis", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Direkomendasikan: PR jauh lebih mudah direview dari HP",
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
                FilterChip(
                    selected = !state.planMode,
                    onClick = { viewModel.onPlanModeChange(false) },
                    label = { Text("Agent") },
                )
                FilterChip(
                    selected = state.planMode,
                    onClick = { viewModel.onPlanModeChange(true) },
                    label = { Text("Plan dulu") },
                )
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
                Text(if (state.submitting) "Mengirim…" else "Jalankan agent")
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
                    text = { Text("Belum ada pilihan") },
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
