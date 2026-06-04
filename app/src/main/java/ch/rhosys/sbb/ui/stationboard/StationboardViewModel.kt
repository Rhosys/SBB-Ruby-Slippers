package ch.rhosys.sbb.ui.stationboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.data.remote.dto.JourneyEntryDto
import ch.rhosys.sbb.domain.TransportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StationboardUiState(
    val station: String = "",
    val stationSuggestions: List<String> = emptyList(),
    val entries: List<JourneyEntryDto> = emptyList(),
    val favouriteStations: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class StationboardViewModel @Inject constructor(
    private val repository: TransportRepository,
    private val prefs: UserPreferencesRepository,
    private val detailHolder: DepartureDetailHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StationboardUiState())
    val uiState: StateFlow<StationboardUiState> = _uiState

    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.favouriteStations.collect { favs ->
                _uiState.value = _uiState.value.copy(favouriteStations = favs.sorted())
            }
        }
    }

    fun onStationChanged(value: String) {
        _uiState.value = _uiState.value.copy(station = value, stationSuggestions = emptyList())
        suggestJob?.cancel()
        if (value.length >= 2) {
            suggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            stationSuggestions = resp.stations.take(5).mapNotNull { it.name }
                        )
                    }
            }
        }
    }

    fun selectSuggestion(name: String) {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(station = name, stationSuggestions = emptyList())
        load()
    }

    fun selectFavourite(name: String) {
        _uiState.value = _uiState.value.copy(station = name, stationSuggestions = emptyList())
        load()
    }

    fun toggleFavourite() {
        val station = _uiState.value.station.trim()
        if (station.isBlank()) return
        viewModelScope.launch {
            val favs = prefs.favouriteStations.first()
            if (station in favs) prefs.removeFavouriteStation(station)
            else prefs.addFavouriteStation(station)
        }
    }

    fun load() {
        val station = _uiState.value.station.trim()
        if (station.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, stationSuggestions = emptyList())
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

    fun selectEntry(entry: JourneyEntryDto) {
        detailHolder.select(entry)
    }
}
