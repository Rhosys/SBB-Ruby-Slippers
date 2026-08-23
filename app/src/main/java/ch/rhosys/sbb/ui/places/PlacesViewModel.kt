package ch.rhosys.sbb.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.ui.common.findFreeGridSlot
import ch.rhosys.sbb.ui.common.rectOverlapsAnyPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeEditUiState(
    val places: List<Place> = emptyList(),
    val addQuery: String = "",
    val addLabel: String = "",
    val addPhotoUri: String? = null,
    val addSuggestions: List<SuggestionItem> = emptyList(),
    val showAddDialog: Boolean = false,
    val selectedSuggestion: SuggestionItem? = null,
    val editingPlace: Place? = null,
    val editLabel: String = "",
    val editPhotoUri: String? = null,
)

data class SuggestionItem(
    val name: String,
    val lat: Double,
    val lng: Double,
)

@HiltViewModel
class HomeEditViewModel @Inject constructor(
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
        _uiState.value = _uiState.value.copy(
            editingPlace = place,
            editLabel = place.label ?: "",
            editPhotoUri = place.photoUri,
        )
    }

    fun onEditLabelChanged(label: String) {
        _uiState.value = _uiState.value.copy(editLabel = label)
    }

    fun onEditPhotoSelected(uri: String?) {
        _uiState.value = _uiState.value.copy(editPhotoUri = uri)
    }

    fun confirmEdit() {
        val place = _uiState.value.editingPlace ?: return
        viewModelScope.launch {
            placeRepository.updatePlace(
                place.copy(
                    label = _uiState.value.editLabel.takeIf { it.isNotBlank() },
                    photoUri = _uiState.value.editPhotoUri,
                )
            )
        }
        dismissEditDialog()
    }

    fun dismissEditDialog() {
        _uiState.value = _uiState.value.copy(
            editingPlace = null,
            editLabel = "",
            editPhotoUri = null,
        )
    }

    // Commits a move or resize from the edit screen's drag gesture. The screen already
    // validated the candidate rect live (via rectOverlapsAnyPlace) while dragging, so
    // this is a final defensive check rather than the primary guard.
    fun updateTileRect(id: Long, gridX: Int, gridY: Int, gridWidth: Int, gridHeight: Int) {
        val place = _uiState.value.places.firstOrNull { it.id == id } ?: return
        if (rectOverlapsAnyPlace(gridX, gridY, gridWidth, gridHeight, _uiState.value.places, excludingId = id)) return
        viewModelScope.launch {
            placeRepository.updatePlace(place.copy(gridX = gridX, gridY = gridY, gridWidth = gridWidth, gridHeight = gridHeight))
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

        val (gridX, gridY) = findFreeGridSlot(_uiState.value.places)
        viewModelScope.launch {
            placeRepository.upsertPlace(
                name = item.name,
                lat = item.lat,
                lng = item.lng,
                label = _uiState.value.addLabel.takeIf { it.isNotBlank() },
                photoUri = _uiState.value.addPhotoUri,
                gridX = gridX,
                gridY = gridY,
            )
        }
        dismissAddDialog()
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.deletePlace(id) }
    }
}
