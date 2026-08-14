package dev.aifih.onthefly.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aifih.onthefly.ServiceLocator
import dev.aifih.onthefly.update.AppUpdater
import dev.aifih.onthefly.update.InstallEvent
import dev.aifih.onthefly.update.InstallResultReceiver
import dev.aifih.onthefly.update.UpdateCheck
import dev.aifih.onthefly.update.UpdateManifest
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
    val hasToken: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = ServiceLocator.updateTokenStore

    // Built here rather than in ServiceLocator so no Context is held in a static field.
    private val updater = AppUpdater(
        context = application,
        client = ServiceLocator.httpClient,
        json = ServiceLocator.json,
        tokenProvider = { tokenStore.get() },
    )

    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersionName = updater.currentVersionName,
            currentVersionCode = updater.currentVersionCode,
            needsUnknownSourcesPermission = !updater.canRequestInstalls(),
            hasToken = tokenStore.isConfigured(),
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
                            message = "Update terpasang. Aplikasi akan memakai versi baru.",
                        )
                    }

                    is InstallEvent.PendingUserAction -> _state.update {
                        it.copy(
                            phase = UpdatePhase.PENDING_USER_ACTION,
                            message = "Sistem meminta konfirmasi. Setujui dialog instalasinya.",
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

    fun saveToken(token: String) {
        tokenStore.save(token)
        _state.update {
            it.copy(
                hasToken = tokenStore.isConfigured(),
                phase = UpdatePhase.IDLE,
                message = null,
            )
        }
    }

    fun clearToken() {
        tokenStore.clear()
        _state.update {
            it.copy(hasToken = false, phase = UpdatePhase.IDLE, manifest = null, message = null)
        }
    }

    fun check() {
        _state.update { it.copy(phase = UpdatePhase.CHECKING, message = null) }

        viewModelScope.launch {
            runCatching { updater.check() }.fold(
                onSuccess = { result ->
                    when (result) {
                        is UpdateCheck.UpToDate -> _state.update {
                            it.copy(
                                phase = UpdatePhase.UP_TO_DATE,
                                message = "Sudah versi terbaru.",
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
                            message = cause.message ?: "Gagal memeriksa update",
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
                        message = cause.message ?: "Gagal memasang update",
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
    var tokenInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refreshPermissionState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Update") },
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
            Text(
                text = "Versi terpasang ${state.currentVersionName} (${state.currentVersionCode})",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            if (state.needsUnknownSourcesPermission) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Aplikasi belum diizinkan memasang APK",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Tanpa izin ini, update tidak bisa dipasang sendiri.",
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
                            Text("Buka pengaturan")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            if (state.hasToken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Token GitHub tersimpan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearToken) { Text("Ganti") }
                }

                Spacer(Modifier.height(8.dp))
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Token GitHub diperlukan",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Repo release bersifat private. Buat fine-grained token " +
                                "dengan izin Contents: Read untuk repo itu saja.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("github_pat_…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                viewModel.saveToken(tokenInput)
                                tokenInput = ""
                            },
                            enabled = tokenInput.isNotBlank(),
                        ) {
                            Text("Simpan token")
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
                                text = "Versi baru ${manifest.versionName} (${manifest.versionCode})",
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
                        UpdatePhase.DOWNLOADING -> "Mengunduh… ${(state.progress * 100).toInt()}%"
                        else -> "Memasang…"
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
                    Text("Unduh dan pasang")
                }

                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = viewModel::check,
                enabled = !busy && state.hasToken,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.phase == UpdatePhase.CHECKING) "Memeriksa…" else "Cek update")
            }
        }
    }
}
