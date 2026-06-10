package ch.rhosys.sbb.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val homeNameInput: String = "",
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    val isSaving: Boolean = false,
    val isComplete: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState

    fun onHomeNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(homeNameInput = name)
    }

    fun onLocationResolved(name: String, lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(homeNameInput = name, homeLat = lat, homeLng = lng)
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
