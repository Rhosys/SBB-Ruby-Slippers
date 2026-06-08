package ch.rhosys.sbb.ui.stationboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Departure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StationboardUiState(
    val query: String = "",
    val departures: List<Departure> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class StationboardViewModel @Inject constructor(
    private val repository: TransportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StationboardUiState())
    val uiState: StateFlow<StationboardUiState> = _uiState

    private var pollJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun load() {
        val station = _uiState.value.query.trim()
        if (station.isBlank()) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetchDepartures(station)
                delay(30_000L)
            }
        }
    }

    private suspend fun fetchDepartures(station: String) {
        _uiState.value = _uiState.value.copy(isLoading = _uiState.value.departures.isEmpty(), error = null)
        runCatching { repository.getStationboard(station) }
            .onSuccess { deps ->
                _uiState.value = _uiState.value.copy(departures = deps, isLoading = false)
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
    }

    override fun onCleared() {
        pollJob?.cancel()
    }
}
