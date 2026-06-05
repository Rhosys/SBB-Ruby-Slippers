package ch.rhosys.sbb.ui.places

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Place
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

private const val PROXIMITY_METERS = 500.0

data class NavigationTarget(val from: String, val to: String)

data class PlacesUiState(
    val places: List<Place> = emptyList(),
    val addQuery: String = "",
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
class PlacesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    fun onTileTap(place: Place) {
        viewModelScope.launch {
            val location = getLocationOrNull()
            val from = if (location != null) {
                val nearest = _uiState.value.places
                    .filter { it.id != place.id }
                    .minByOrNull { it.distanceMetersTo(location.first, location.second) }
                if (nearest != null && nearest.distanceMetersTo(location.first, location.second) <= PROXIMITY_METERS) {
                    nearest.name
                } else ""
            } else ""
            _uiState.value = _uiState.value.copy(
                pendingNavigateTo = NavigationTarget(from = from, to = place.name)
            )
        }
    }

    fun onNavigationHandled() {
        _uiState.value = _uiState.value.copy(pendingNavigateTo = null)
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

    private suspend fun getLocationOrNull(): Pair<Double, Double>? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null
        return runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            loc?.let { Pair(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
