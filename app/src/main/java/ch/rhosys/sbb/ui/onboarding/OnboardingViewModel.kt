package ch.rhosys.sbb.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaceSuggestion(val name: String, val lat: Double, val lng: Double)

data class OnboardingUiState(
    val homeNameInput: String = "",
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    val homeSuggestions: List<PlaceSuggestion> = emptyList(),
    val isLocatingHome: Boolean = false,
    val isSaving: Boolean = false,
    val isComplete: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val transportRepository: TransportRepository,
    private val locationProvider: LocationProvider,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState

    private var suggestJob: Job? = null

    fun onHomeNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(
            homeNameInput = name,
            homeLat = null,
            homeLng = null,
            homeSuggestions = emptyList(),
        )
        suggestJob?.cancel()
        if (name.length >= 2) {
            suggestJob = viewModelScope.launch {
                delay(300)
                runCatching { transportRepository.getLocations(name) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            homeSuggestions = resp.stations.take(5).mapNotNull { s ->
                                val stationName = s.name ?: return@mapNotNull null
                                val lat = s.coordinate?.y ?: return@mapNotNull null
                                val lng = s.coordinate?.x ?: return@mapNotNull null
                                PlaceSuggestion(stationName, lat, lng)
                            }
                        )
                    }
            }
        }
    }

    fun selectHomeSuggestion(suggestion: PlaceSuggestion) {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(
            homeNameInput = suggestion.name,
            homeLat = suggestion.lat,
            homeLng = suggestion.lng,
            homeSuggestions = emptyList(),
        )
    }

    fun fillHomeWithNearestStop() {
        suggestJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLocatingHome = true, homeSuggestions = emptyList())
            val location = locationProvider.getLocationOrNull()
            val nearest = location?.let {
                runCatching {
                    transportRepository.getLocationsByCoordinate(it.first, it.second)
                }.getOrNull()?.stations?.firstOrNull()
            }
            val lat = nearest?.coordinate?.y
            val lng = nearest?.coordinate?.x
            _uiState.value = if (nearest?.name != null && lat != null && lng != null) {
                _uiState.value.copy(
                    isLocatingHome = false,
                    homeNameInput = nearest.name,
                    homeLat = lat,
                    homeLng = lng,
                )
            } else {
                _uiState.value.copy(isLocatingHome = false)
            }
        }
    }

    fun saveHomeAndFinish() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            val name = state.homeNameInput.trim()
            if (name.isNotBlank()) {
                val lat = state.homeLat ?: 47.3769
                val lng = state.homeLng ?: 8.5417
                placeRepository.upsertPlace(name = name, lat = lat, lng = lng)
            }
            prefs.setHasCompletedOnboarding(true)
            _uiState.value = _uiState.value.copy(isSaving = false, isComplete = true)
        }
    }

    fun skipAndFinish() {
        viewModelScope.launch {
            prefs.setHasCompletedOnboarding(true)
            _uiState.value = _uiState.value.copy(isComplete = true)
        }
    }
}
