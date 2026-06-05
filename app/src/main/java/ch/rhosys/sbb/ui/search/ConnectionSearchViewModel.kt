package ch.rhosys.sbb.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.TripReviewHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionSearchUiState(
    val fromText: String = "",
    val toText: String = "",
    val fromSuggestions: List<String> = emptyList(),
    val toSuggestions: List<String> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConnectionSearchViewModel @Inject constructor(
    private val repository: TransportRepository,
    private val tripReviewHolder: TripReviewHolder,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ConnectionSearchUiState(
            fromText = savedStateHandle.get<String>("from") ?: "",
            toText = savedStateHandle.get<String>("to") ?: "",
        )
    )
    val uiState: StateFlow<ConnectionSearchUiState> = _uiState

    private var fromSuggestJob: Job? = null
    private var toSuggestJob: Job? = null

    fun onFromChanged(value: String) {
        _uiState.value = _uiState.value.copy(fromText = value, fromSuggestions = emptyList())
        fromSuggestJob?.cancel()
        if (value.length >= 2) {
            fromSuggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            fromSuggestions = resp.stations.take(5).mapNotNull { it.name }
                        )
                    }
            }
        }
    }

    fun onToChanged(value: String) {
        _uiState.value = _uiState.value.copy(toText = value, toSuggestions = emptyList())
        toSuggestJob?.cancel()
        if (value.length >= 2) {
            toSuggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            toSuggestions = resp.stations.take(5).mapNotNull { it.name }
                        )
                    }
            }
        }
    }

    fun selectFromSuggestion(name: String) {
        fromSuggestJob?.cancel()
        _uiState.value = _uiState.value.copy(fromText = name, fromSuggestions = emptyList())
    }

    fun selectToSuggestion(name: String) {
        toSuggestJob?.cancel()
        _uiState.value = _uiState.value.copy(toText = name, toSuggestions = emptyList())
    }

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
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                fromSuggestions = emptyList(),
                toSuggestions = emptyList(),
            )
            runCatching { repository.getConnections(fromEndpoint, toEndpoint) }
                .onSuccess { connections ->
                    _uiState.value = _uiState.value.copy(connections = connections, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
        }
    }

    fun openTripReview(connection: Connection) {
        val fromText = _uiState.value.fromText.trim()
        val toText   = _uiState.value.toText.trim()
        tripReviewHolder.set(
            connection = connection,
            from = SearchEndpoint.NamedPlace(fromText.ifBlank { toText }),
            to   = SearchEndpoint.NamedPlace(toText),
        )
    }
}
