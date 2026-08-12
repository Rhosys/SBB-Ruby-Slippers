package ch.rhosys.sbb.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.data.local.routing.LocalRoutingState
import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.TripReviewHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class ConnectionSearchUiState(
    val fromText: String = "",
    val toText: String = "",
    val fromSuggestions: List<String> = emptyList(),
    val toSuggestions: List<String> = emptyList(),
    val smartSuggestions: List<String> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val isLocatingFrom: Boolean = false,
    val isLocatingTo: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConnectionSearchViewModel @Inject constructor(
    private val repository: TransportRepository,
    private val localRouter: LocalTransportRepository,
    private val routeRepository: RouteRepository,
    private val placeRepository: PlaceRepository,
    private val locationProvider: LocationProvider,
    private val tripReviewHolder: TripReviewHolder,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialFrom = savedStateHandle.get<String>("from") ?: ""
    private val initialTo   = savedStateHandle.get<String>("to") ?: ""

    private val _uiState = MutableStateFlow(
        ConnectionSearchUiState(
            fromText = initialFrom,
            toText = initialTo,
        )
    )
    val uiState: StateFlow<ConnectionSearchUiState> = _uiState

    private var fromSuggestJob: Job? = null
    private var toSuggestJob: Job? = null

    init {
        viewModelScope.launch { loadSmartSuggestions() }
    }

    private suspend fun loadSmartSuggestions() {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()

        // 1. Closest saved places ≤3
        val location = locationProvider.getLocationOrNull()
        if (location != null) {
            placeRepository.getPlaces().first()
                .sortedBy { it.distanceMetersTo(location.first, location.second) }
                .take(3)
                .forEach { if (seen.add(it.name)) result.add(it.name) }
        }

        // 2. Last 4 actually travelled (wasLockedIn = true)
        routeRepository.getRecentSearches(50)
            .filter { it.wasLockedIn }
            .distinctBy { it.toName }
            .take(4)
            .forEach { if (seen.add(it.toName)) result.add(it.toName) }

        // 3. Last 4 searched (any)
        routeRepository.getRecentSearches(50)
            .distinctBy { it.toName }
            .take(4)
            .forEach { if (seen.add(it.toName)) result.add(it.toName) }

        _uiState.value = _uiState.value.copy(
            smartSuggestions = result,
            fromSuggestions = if (initialFrom.isEmpty()) result else emptyList(),
            toSuggestions = if (initialTo.isEmpty()) result else emptyList(),
        )
    }

    fun onFromChanged(value: String) {
        val smart = _uiState.value.smartSuggestions
        _uiState.value = _uiState.value.copy(
            fromText = value,
            fromSuggestions = if (value.isEmpty()) smart else emptyList(),
        )
        fromSuggestJob?.cancel()
        if (value.length >= 2) {
            fromSuggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            fromSuggestions = resp.stations.take(5).mapNotNull { it.name }
                        )
                    }
            }
        }
    }

    fun onToChanged(value: String) {
        val smart = _uiState.value.smartSuggestions
        _uiState.value = _uiState.value.copy(
            toText = value,
            toSuggestions = if (value.isEmpty()) smart else emptyList(),
        )
        toSuggestJob?.cancel()
        if (value.length >= 2) {
            toSuggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        _uiState.value = _uiState.value.copy(
                            toSuggestions = resp.stations.take(5).mapNotNull { it.name }
                        )
                    }
            }
        }
    }

    fun selectFromSuggestion(name: String) {
        fromSuggestJob?.cancel()
        _uiState.value = _uiState.value.copy(fromText = name, fromSuggestions = emptyList())
    }

    fun selectToSuggestion(name: String) {
        toSuggestJob?.cancel()
        _uiState.value = _uiState.value.copy(toText = name, toSuggestions = emptyList())
    }

    fun fillFromWithNearestStop() {
        fromSuggestJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLocatingFrom = true, fromSuggestions = emptyList())
            val name = resolveNearestStopName()
            _uiState.value = _uiState.value.copy(
                isLocatingFrom = false,
                fromText = name ?: _uiState.value.fromText,
            )
        }
    }

    fun fillToWithNearestStop() {
        toSuggestJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLocatingTo = true, toSuggestions = emptyList())
            val name = resolveNearestStopName()
            _uiState.value = _uiState.value.copy(
                isLocatingTo = false,
                toText = name ?: _uiState.value.toText,
            )
        }
    }

    private suspend fun resolveNearestStopName(): String? {
        val location = locationProvider.getLocationOrNull() ?: return null
        return runCatching {
            repository.getLocationsByCoordinate(location.first, location.second)
        }.getOrNull()?.stations?.firstOrNull()?.name
    }

    fun search() {
        val from = _uiState.value.fromText.trim()
        val to   = _uiState.value.toText.trim()
        if (to.isBlank()) return

        val fromEndpoint = if (from.isBlank()) SearchEndpoint.NamedPlace(to) else SearchEndpoint.NamedPlace(from)
        val toEndpoint = SearchEndpoint.NamedPlace(to)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                connections = emptyList(),
                fromSuggestions = emptyList(),
                toSuggestions = emptyList(),
            )

            if (localRouter.hasData()) {
                searchLocally(fromEndpoint, toEndpoint)
            } else {
                searchViaApi(fromEndpoint, toEndpoint)
            }
        }
    }

    private suspend fun searchLocally(from: SearchEndpoint, to: SearchEndpoint) {
        val swiss = ZoneId.of("Europe/Zurich")
        localRouter.routeConnections(
            from = from,
            to = to,
            date = LocalDate.now(swiss),
            routingTime = RoutingTime.DepartAfter(LocalTime.now(swiss)),
        ).collect { state ->
            when (state) {
                is LocalRoutingState.Results -> _uiState.value = _uiState.value.copy(
                    connections = state.connections,
                    isLoading = !state.isComplete,
                )
                is LocalRoutingState.NoResults -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = state.reason.ifBlank { null },
                )
                is LocalRoutingState.NoData -> searchViaApi(from, to)
                LocalRoutingState.Loading -> Unit
            }
        }
    }

    private suspend fun searchViaApi(from: SearchEndpoint, to: SearchEndpoint) {
        runCatching { repository.getConnections(from, to) }
            .onSuccess { connections ->
                _uiState.value = _uiState.value.copy(connections = connections, isLoading = false)
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
    }

    fun openTripReview(connection: Connection) {
        val fromText = _uiState.value.fromText.trim()
        val toText   = _uiState.value.toText.trim()
        tripReviewHolder.set(
            connection = connection,
            from = SearchEndpoint.NamedPlace(fromText.ifBlank { toText }),
            to   = SearchEndpoint.NamedPlace(toText),
        )
    }
}
