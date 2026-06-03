package ch.rhosys.sbb.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.SavedRoute
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Hero(
        val destination: String,
        val connections: List<Connection>,
        val from: SearchEndpoint,
        val to: SearchEndpoint,
        val places: List<Place>,
    ) : HomeUiState()
    data class TileGrid(val places: List<Place>) : HomeUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val placeRepository: PlaceRepository,
    private val routeRepository: RouteRepository,
    private val transportRepository: TransportRepository,
    private val journeyStateHolder: JourneyStateHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        viewModelScope.launch { infer() }
    }

    fun refresh() {
        viewModelScope.launch { infer() }
    }

    fun lockIn(connection: Connection, from: SearchEndpoint, to: SearchEndpoint) {
        journeyStateHolder.lockIn(connection, from, to)
    }

    private suspend fun infer() {
        _uiState.value = HomeUiState.Loading
        val places = placeRepository.getPlaces().first()
        val location = getLocationOrNull()

        val nearestPlace = if (location != null) {
            places.minByOrNull { it.distanceMetersTo(location.first, location.second) }
        } else null

        val candidate = scoreBestCandidate(location, nearestPlace)

        if (candidate != null) {
            val from = if (location != null)
                SearchEndpoint.CurrentLocation(location.first, location.second)
            else
                SearchEndpoint.NamedPlace(nearestPlace?.name ?: "")

            val connections = runCatching {
                transportRepository.getConnections(from, candidate.toDestinationEndpoint())
            }.getOrNull() ?: emptyList()

            if (connections.isNotEmpty()) {
                _uiState.value = HomeUiState.Hero(
                    destination = candidate.destinationName,
                    connections = connections,
                    from = from,
                    to = candidate.toDestinationEndpoint(),
                    places = places,
                )
                return
            }
        }

        _uiState.value = HomeUiState.TileGrid(places)
    }

    private suspend fun scoreBestCandidate(
        location: Pair<Double, Double>?,
        nearestPlace: Place?,
    ): SavedRoute? {
        val now = Instant.now()
        val windowEnd = now.plusSeconds(2 * 60 * 60)

        val savedRoutes = routeRepository.getSavedRoutes().first()

        // Score imminent saved routes
        val imminentRoute = savedRoutes
            .filter { route ->
                val scheduledAt = route.scheduledAt ?: return@filter false
                scheduledAt.isAfter(now) && scheduledAt.isBefore(windowEnd)
            }
            .minByOrNull { it.scheduledAt!! }

        if (imminentRoute != null) return imminentRoute

        // If we're near a non-home place, suggest going Home
        val home = placeRepository.getHomePlace().first()
        if (nearestPlace != null && home != null && nearestPlace.id != home.id) {
            return SavedRoute(
                id = -1,
                label = "Home",
                destinationName = home.name,
                destinationLat = home.lat,
                destinationLng = home.lng,
                scheduledAt = null,
            )
        }

        return null
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
