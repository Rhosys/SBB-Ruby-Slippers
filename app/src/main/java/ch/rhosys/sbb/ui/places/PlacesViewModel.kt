package ch.rhosys.sbb.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlacesUiState(
    val places: List<Place> = emptyList(),
    val addQuery: String = "",
    val addSuggestions: List<SuggestionItem> = emptyList(),
    val showAddDialog: Boolean = false,
    val selectedSuggestion: SuggestionItem? = null,
)

data class SuggestionItem(
    val name: String,
    val lat: Double,
    val lng: Double,
)

@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val transportRepository: TransportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> = _uiState

    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            placeRepository.getPlaces().collect { places ->
                _uiState.value = _uiState.value.copy(places = places)
            }
        }
    }

    fun openAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            addQuery = "",
            addSuggestions = emptyList(),
            selectedSuggestion = null,
        )
    }

    fun dismissAddDialog() {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(
            showAddDialog = false,
            addQuery = "",
            addSuggestions = emptyList(),
            selectedSuggestion = null,
        )
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(
            addQuery = query,
            addSuggestions = emptyList(),
            selectedSuggestion = null,
        )
        suggestJob?.cancel()
        if (query.length >= 2) {
            suggestJob = viewModelScope.launch {
                delay(300)
                runCatching { transportRepository.getLocations(query) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            addSuggestions = resp.stations.take(5).mapNotNull { s ->
                                val name = s.name ?: return@mapNotNull null
                                val lat = s.coordinate?.y ?: return@mapNotNull null
                                val lng = s.coordinate?.x ?: return@mapNotNull null
                                SuggestionItem(name, lat, lng)
                            }
                        )
                    }
            }
        }
    }

    fun selectSuggestion(item: SuggestionItem) {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(
            addQuery = item.name,
            addSuggestions = emptyList(),
            selectedSuggestion = item,
        )
    }

    fun confirmAdd() {
        val item = _uiState.value.selectedSuggestion
            ?: _uiState.value.addQuery.trim().takeIf { it.isNotBlank() }
                ?.let { SuggestionItem(it, 0.0, 0.0) }
            ?: return

        viewModelScope.launch {
            val isFirst = placeRepository.getPlaces().first().isEmpty()
            placeRepository.upsertPlace(
                name = item.name,
                lat = item.lat,
                lng = item.lng,
                isHome = isFirst,
            )
        }
        dismissAddDialog()
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.deletePlace(id) }
    }

    fun setHome(id: Long) {
        viewModelScope.launch { placeRepository.setHome(id) }
    }
}
