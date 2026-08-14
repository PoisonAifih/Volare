package dev.aifih.onthefly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aifih.onthefly.ServiceLocator
import dev.aifih.onthefly.data.CursorApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DASHBOARD_API_KEYS_URL = "https://cursor.com/dashboard/api"

data class ApiKeyUiState(
    val apiKey: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
)

class ApiKeyViewModel : ViewModel() {

    private val _state = MutableStateFlow(ApiKeyUiState())
    val state: StateFlow<ApiKeyUiState> = _state.asStateFlow()

    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value, error = null) }
    }

    fun connect(onConnected: () -> Unit) {
        val key = _state.value.apiKey.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(error = "API key masih kosong") }
            return
        }

        _state.update { it.copy(connecting = true, error = null) }

        viewModelScope.launch {
            val store = ServiceLocator.apiKeyStore
            store.save(key)

            val result = runCatching { ServiceLocator.repository.me() }

            result.fold(
                onSuccess = {
                    _state.update { current -> current.copy(connecting = false) }
                    onConnected()
                },
                onFailure = { cause ->
                    store.clear()
                    _state.update { current ->
                        current.copy(connecting = false, error = describe(cause))
                    }
                },
            )
        }
    }

    private fun describe(cause: Throwable): String = when {
        cause is CursorApiException && cause.isUnauthorized ->
            "API key ditolak. Pastikan kamu menyalin key dari Cursor Dashboard, bukan token lain."

        cause is CursorApiException -> cause.message
        else -> cause.message ?: "Gagal menghubungi api.cursor.com"
    }
}

@Composable
fun ApiKeyScreen(onConnected: () -> Unit) {
    val viewModel: ApiKeyViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "OnTheFly", style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Jalankan Cursor Cloud Agents dari HP. Tempel API key dari Cursor " +
                    "Dashboard untuk mulai.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("Cursor API key") },
                singleLine = true,
                isError = state.error != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.connect(onConnected) },
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Hubungkan")
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { context.openUrl(DASHBOARD_API_KEYS_URL) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Buka Dashboard → API Keys")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Key disimpan terenkripsi di perangkat ini saja. Key ini memberi akses " +
                    "penuh ke cloud agent akunmu, jadi kalau HP hilang, cabut key-nya dari " +
                    "dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
