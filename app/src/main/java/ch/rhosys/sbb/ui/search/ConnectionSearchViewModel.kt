package ch.rhosys.sbb.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.remote.dto.ConnectionDto
import ch.rhosys.sbb.domain.TransportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionSearchUiState(
    val from: String = "",
    val to: String = "",
    val connections: List<ConnectionDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConnectionSearchViewModel @Inject constructor(
    private val repository: TransportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionSearchUiState())
    val uiState: StateFlow<ConnectionSearchUiState> = _uiState

    fun onFromChanged(value: String) {
        _uiState.value = _uiState.value.copy(from = value)
    }

    fun onToChanged(value: String) {
        _uiState.value = _uiState.value.copy(to = value)
    }

    fun search() {
        val from = _uiState.value.from.trim()
        val to = _uiState.value.to.trim()
        if (from.isBlank() || to.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = repository.getConnections(from = from, to = to)
                _uiState.value = _uiState.value.copy(
                    connections = response.connections,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error",
                )
            }
        }
    }
}
