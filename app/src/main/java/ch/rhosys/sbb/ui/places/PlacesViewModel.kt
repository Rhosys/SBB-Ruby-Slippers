package ch.rhosys.sbb.ui.places

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.photo.PlacePhotoStore
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
    private val photoStore: PlacePhotoStore,
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

    // A picked photo is copied into app storage immediately (see PlacePhotoStore) so it
    // survives Android Auto Backup, but that means the copy sitting in editPhotoUri is
    // only "real" once confirmEdit() actually saves it — replacing or cancelling has to
    // clean up whichever copy never got attached to the place, without ever touching the
    // place's still-current saved photo.
    fun onEditPhotoSelected(uri: Uri?) {
        val original = _uiState.value.editingPlace?.photoUri
        val uncommittedPrevious = _uiState.value.editPhotoUri.takeIf { it != original }
        if (uri == null) {
            _uiState.value = _uiState.value.copy(editPhotoUri = null)
            viewModelScope.launch { photoStore.delete(uncommittedPrevious) }
            return
        }
        viewModelScope.launch {
            val localUri = photoStore.copyToLocalStorage(uri)
            _uiState.value = _uiState.value.copy(editPhotoUri = localUri)
            photoStore.delete(uncommittedPrevious)
        }
    }

    fun confirmEdit() {
        val place = _uiState.value.editingPlace ?: return
        val finalPhotoUri = _uiState.value.editPhotoUri
        viewModelScope.launch {
            placeRepository.updatePlace(
                place.copy(
                    label = _uiState.value.editLabel.takeIf { it.isNotBlank() },
                    photoUri = finalPhotoUri,
                )
            )
            if (finalPhotoUri != place.photoUri) photoStore.delete(place.photoUri)
        }
        clearEditDialogState()
    }

    fun dismissEditDialog() {
        val original = _uiState.value.editingPlace?.photoUri
        val uncommitted = _uiState.value.editPhotoUri.takeIf { it != original }
        if (uncommitted != null) {
            viewModelScope.launch { photoStore.delete(uncommitted) }
        }
        clearEditDialogState()
    }

    private fun clearEditDialogState() {
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
        val staged = _uiState.value.addPhotoUri
        if (staged != null) {
            viewModelScope.launch { photoStore.delete(staged) }
        }
        clearAddDialogState()
    }

    private fun clearAddDialogState() {
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

    fun onPhotoSelected(uri: Uri?) {
        val previous = _uiState.value.addPhotoUri
        if (uri == null) {
            _uiState.value = _uiState.value.copy(addPhotoUri = null)
            viewModelScope.launch { photoStore.delete(previous) }
            return
        }
        viewModelScope.launch {
            val localUri = photoStore.copyToLocalStorage(uri)
            _uiState.value = _uiState.value.copy(addPhotoUri = localUri)
            photoStore.delete(previous)
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
        clearAddDialogState()
    }

    fun deletePlace(id: Long) {
        val photoUri = _uiState.value.places.firstOrNull { it.id == id }?.photoUri
        viewModelScope.launch {
            placeRepository.deletePlace(id)
            photoStore.delete(photoUri)
        }
    }
}
