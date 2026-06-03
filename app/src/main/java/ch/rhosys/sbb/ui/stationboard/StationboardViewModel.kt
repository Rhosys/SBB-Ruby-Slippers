package ch.rhosys.sbb.ui.stationboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.remote.dto.JourneyEntryDto
import ch.rhosys.sbb.domain.TransportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StationboardUiState(
    val station: String = "",
    val entries: List<JourneyEntryDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class StationboardViewModel @Inject constructor(
    private val repository: TransportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StationboardUiState())
    val uiState: StateFlow<StationboardUiState> = _uiState

    fun onStationChanged(value: String) {
        _uiState.value = _uiState.value.copy(station = value)
    }

    fun load() {
        val station = _uiState.value.station.trim()
        if (station.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = repository.getStationboard(station = station)
                _uiState.value = _uiState.value.copy(
                    entries = response.stationboard,
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
