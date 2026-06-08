package ch.rhosys.sbb.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PROXIMITY_METERS = 500.0

data class NavigationTarget(val from: String, val to: String)

data class HomeEditUiState(
    val places: List<Place> = emptyList(),
    val addQuery: String = "",
    val addLabel: String = "",
    val addPhotoUri: String? = null,
    val addSuggestions: List<SuggestionItem> = emptyList(),
    val showAddDialog: Boolean = false,
    val selectedSuggestion: SuggestionItem? = null,
    val pendingNavigateTo: NavigationTarget? = null,
)

data class SuggestionItem(
    val name: String,
    val lat: Double,
    val lng: Double,
)

@HiltViewModel
class HomeEditViewModel @Inject constructor(
    private val locationProvider: LocationProvider,
    private val placeRepository: PlaceRepository,
    private val transportRepository: TransportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeEditUiState())
    val uiState: StateFlow<HomeEditUiState> = _uiState

    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            placeRepository.getPlaces().collect { places ->
                _uiState.value = _uiState.value.copy(places = places)
            }
        }
    }

    fun onTileTap(place: Place) {
        viewModelScope.launch {
            val location = locationProvider.getLocationOrNull()
            val from = if (location != null) {
                val nearest = _uiState.value.places
                    .filter { it.id != place.id }
                    .minByOrNull { it.distanceMetersTo(location.first, location.second) }
                if (nearest != null && nearest.distanceMetersTo(location.first, location.second) <= PROXIMITY_METERS) {
                    nearest.name
                } else "Current location"
            } else "Current location"
            _uiState.value = _uiState.value.copy(
                pendingNavigateTo = NavigationTarget(from = from, to = place.name)
            )
        }
    }

    fun onNavigationHandled() {
        _uiState.value = _uiState.value.copy(pendingNavigateTo = null)
    }

    fun reorderTiles(draggedId: Long, targetId: Long) {
        val places = _uiState.value.places.toMutableList()
        val fromIdx = places.indexOfFirst { it.id == draggedId }
        val toIdx = places.indexOfFirst { it.id == targetId }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        val tmp = places[fromIdx]
        places[fromIdx] = places[toIdx]
        places[toIdx] = tmp
        viewModelScope.launch {
            places.forEachIndexed { idx, place ->
                placeRepository.updatePlace(place.copy(sortOrder = idx))
            }
        }
    }

    fun openAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            addQuery = "",
            addLabel = "",
            addPhotoUri = null,
            addSuggestions = emptyList(),
            selectedSuggestion = null,
        )
    }

    fun dismissAddDialog() {
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(
            showAddDialog = false,
            addQuery = "",
            addLabel = "",
            addPhotoUri = null,
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

    fun onLabelChanged(label: String) {
        _uiState.value = _uiState.value.copy(addLabel = label)
    }

    fun onPhotoSelected(uri: String?) {
        _uiState.value = _uiState.value.copy(addPhotoUri = uri)
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
            placeRepository.upsertPlace(
                name = item.name,
                lat = item.lat,
                lng = item.lng,
                label = _uiState.value.addLabel.takeIf { it.isNotBlank() },
                photoUri = _uiState.value.addPhotoUri,
            )
        }
        dismissAddDialog()
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.deletePlace(id) }
    }
}
