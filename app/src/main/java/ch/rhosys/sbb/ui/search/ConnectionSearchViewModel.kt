package ch.rhosys.sbb.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionSearchUiState(
    val fromText: String = "",
    val toText: String = "",
    val connections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConnectionSearchViewModel @Inject constructor(
    private val repository: TransportRepository,
    private val routeRepository: RouteRepository,
    private val journeyStateHolder: JourneyStateHolder,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ConnectionSearchUiState(
            fromText = savedStateHandle.get<String>("from") ?: "",
            toText = savedStateHandle.get<String>("to") ?: "",
        )
    )
    val uiState: StateFlow<ConnectionSearchUiState> = _uiState

    fun onFromChanged(value: String) { _uiState.value = _uiState.value.copy(fromText = value) }
    fun onToChanged(value: String)   { _uiState.value = _uiState.value.copy(toText = value) }

    fun search() {
        val from = _uiState.value.fromText.trim()
        val to   = _uiState.value.toText.trim()
        if (to.isBlank()) return

        val fromEndpoint = if (from.isBlank())
            SearchEndpoint.NamedPlace(to)
        else
            SearchEndpoint.NamedPlace(from)
        val toEndpoint = SearchEndpoint.NamedPlace(to)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.getConnections(fromEndpoint, toEndpoint) }
                .onSuccess { connections ->
                    _uiState.value = _uiState.value.copy(connections = connections, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
        }
    }

    fun lockIn(connection: Connection) {
        val fromName = _uiState.value.fromText.trim()
        val toName   = _uiState.value.toText.trim()
        val from = SearchEndpoint.NamedPlace(fromName)
        val to   = SearchEndpoint.NamedPlace(toName)
        journeyStateHolder.lockIn(connection, from, to)
        viewModelScope.launch {
            routeRepository.recordSearch(
                fromName = fromName.ifBlank { toName },
                toName = toName,
                toLat = 0.0,
                toLng = 0.0,
                wasLockedIn = true,
            )
        }
    }
}
