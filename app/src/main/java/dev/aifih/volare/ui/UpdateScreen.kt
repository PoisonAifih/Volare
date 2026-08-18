package dev.aifih.volare.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aifih.volare.ServiceLocator
import dev.aifih.volare.update.AppUpdater
import dev.aifih.volare.update.InstallEvent
import dev.aifih.volare.update.InstallResultReceiver
import dev.aifih.volare.update.UpdateCheck
import dev.aifih.volare.update.UpdateManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    INSTALLING,
    PENDING_USER_ACTION,
    FAILED,
}

data class UpdateUiState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val needsUnknownSourcesPermission: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    // Built here rather than in ServiceLocator so no Context is held in a static field.
    private val updater = AppUpdater(
        context = application,
        client = ServiceLocator.httpClient,
        json = ServiceLocator.json,
    )

    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersionName = updater.currentVersionName,
            currentVersionCode = updater.currentVersionCode,
            needsUnknownSourcesPermission = !updater.canRequestInstalls(),
        ),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            InstallResultReceiver.installEvents.collect { event ->
                when (event) {
                    is InstallEvent.Success -> _state.update {
                        it.copy(
                            phase = UpdatePhase.UP_TO_DATE,
                            message = "Update installed. The app will use the new version.",
                        )
                    }

                    is InstallEvent.PendingUserAction -> _state.update {
                        it.copy(
                            phase = UpdatePhase.PENDING_USER_ACTION,
                            message = "The system is asking for confirmation. " +
                                "Approve the install dialog.",
                        )
                    }

                    is InstallEvent.Failed -> _state.update {
                        it.copy(phase = UpdatePhase.FAILED, message = event.message)
                    }
                }
            }
        }
    }

    fun refreshPermissionState() {
        _state.update { it.copy(needsUnknownSourcesPermission = !updater.canRequestInstalls()) }
    }

    fun unknownSourcesSettingsIntent(): Intent = updater.unknownSourcesSettingsIntent()

    fun check() {
        _state.update { it.copy(phase = UpdatePhase.CHECKING, message = null) }

        viewModelScope.launch {
            runCatching { updater.check() }.fold(
                onSuccess = { result ->
                    when (result) {
                        is UpdateCheck.UpToDate -> _state.update {
                            it.copy(
                                phase = UpdatePhase.UP_TO_DATE,
                                message = "You are on the latest version.",
                            )
                        }

                        is UpdateCheck.Available -> _state.update {
                            it.copy(
                                phase = UpdatePhase.AVAILABLE,
                                manifest = result.manifest,
                                message = null,
                            )
                        }
                    }
                },
                onFailure = { cause ->
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.FAILED,
                            message = cause.message ?: "Could not check for updates",
                        )
                    }
                },
            )
        }
    }

    fun install() {
        val manifest = _state.value.manifest ?: return

        _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, message = null) }

        viewModelScope.launch {
            runCatching {
                val apk = updater.download(manifest) { fraction ->
                    _state.update { it.copy(progress = fraction.coerceAtLeast(0f)) }
                }

                _state.update { it.copy(phase = UpdatePhase.INSTALLING) }
                updater.install(apk)
            }.onFailure { cause ->
                _state.update {
                    it.copy(
                        phase = UpdatePhase.FAILED,
                        message = cause.message ?: "Could not install the update",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val viewModel: UpdateViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshPermissionState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Update") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "Installed version ${state.currentVersionName} (${state.currentVersionCode})",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            if (state.needsUnknownSourcesPermission) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "The app is not allowed to install APKs yet",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Without this permission, updates cannot install themselves.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        viewModel.unknownSourcesSettingsIntent(),
                                    )
                                }
                            },
                        ) {
                            Text("Open settings")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            state.manifest?.let { manifest ->
                if (state.phase != UpdatePhase.UP_TO_DATE) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "New version ${manifest.versionName} (${manifest.versionCode})",
                                style = MaterialTheme.typography.titleSmall,
                            )

                            manifest.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            if (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.INSTALLING) {
                if (state.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = when (state.phase) {
                        UpdatePhase.DOWNLOADING -> "Downloading… ${(state.progress * 100).toInt()}%"
                        else -> "Installing…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(16.dp))
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.phase == UpdatePhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Spacer(Modifier.height(16.dp))
            }

            val busy = state.phase == UpdatePhase.CHECKING ||
                state.phase == UpdatePhase.DOWNLOADING ||
                state.phase == UpdatePhase.INSTALLING

            if (state.phase == UpdatePhase.AVAILABLE) {
                Button(
                    onClick = viewModel::install,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download and install")
                }

                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = viewModel::check,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.phase == UpdatePhase.CHECKING) "Checking…" else "Check for updates")
            }
        }
    }
}
