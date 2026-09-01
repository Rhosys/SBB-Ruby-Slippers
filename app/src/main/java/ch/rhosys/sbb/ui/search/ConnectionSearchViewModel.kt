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
import ch.rhosys.sbb.util.lowercaseAscii
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

private val SWISS_ZONE = ZoneId.of("Europe/Zurich")

data class ConnectionSearchUiState(
    val fromText: String = "",
    val toText: String = "",
    val fromSuggestions: List<String> = emptyList(),
    val toSuggestions: List<String> = emptyList(),
    val isFromSuggesting: Boolean = false,
    val isToSuggesting: Boolean = false,
    val smartSuggestions: List<String> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    val isLoadingLater: Boolean = false,
    val error: String? = null,
    val searchDate: LocalDate = LocalDate.now(SWISS_ZONE),
    val searchTime: LocalTime = LocalTime.now(SWISS_ZONE),
    // false = "depart after" searchTime, true = "arrive by" searchTime.
    val isArriveBy: Boolean = false,
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
    private var fromLocalSuggestJob: Job? = null
    private var toLocalSuggestJob: Job? = null
    private var autoSearchJob: Job? = null

    init {
        viewModelScope.launch { loadSmartSuggestions() }
        if (initialFrom.isNotBlank() && initialTo.isNotBlank()) search()
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

    // Suggestions come from two sources run in parallel: the on-device GTFS stop cache
    // (instant, offline — see LocalTransportRepository.searchStopNames) and the live
    // opendata.ch API (debounced 300ms, as before). Whichever answers first is shown
    // immediately; the other merges in on top of it, deduplicated, when it lands.
    fun onFromChanged(value: String) {
        val smart = _uiState.value.smartSuggestions
        _uiState.value = _uiState.value.copy(
            fromText = value,
            fromSuggestions = if (value.isEmpty()) smart else emptyList(),
        )
        fromLocalSuggestJob?.cancel()
        fromSuggestJob?.cancel()
        if (value.length >= 2) {
            _uiState.value = _uiState.value.copy(isFromSuggesting = true)
            fromLocalSuggestJob = viewModelScope.launch {
                val local = localRouter.searchStopNames(value).map { it.name }
                _uiState.value = _uiState.value.copy(
                    fromSuggestions = mergeSuggestionNames(local, _uiState.value.fromSuggestions)
                )
            }
            fromSuggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        val remote = resp.stations.take(5).mapNotNull { it.name }
                        _uiState.value = _uiState.value.copy(
                            fromSuggestions = mergeSuggestionNames(_uiState.value.fromSuggestions, remote)
                        )
                    }
                _uiState.value = _uiState.value.copy(isFromSuggesting = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(isFromSuggesting = false)
        }
        scheduleAutoSearch()
    }

    fun onToChanged(value: String) {
        val smart = _uiState.value.smartSuggestions
        _uiState.value = _uiState.value.copy(
            toText = value,
            toSuggestions = if (value.isEmpty()) smart else emptyList(),
        )
        toLocalSuggestJob?.cancel()
        toSuggestJob?.cancel()
        if (value.length >= 2) {
            _uiState.value = _uiState.value.copy(isToSuggesting = true)
            toLocalSuggestJob = viewModelScope.launch {
                val local = localRouter.searchStopNames(value).map { it.name }
                _uiState.value = _uiState.value.copy(
                    toSuggestions = mergeSuggestionNames(local, _uiState.value.toSuggestions)
                )
            }
            toSuggestJob = viewModelScope.launch {
                delay(300)
                runCatching { repository.getLocations(value) }
                    .onSuccess { resp ->
                        val remote = resp.stations.take(5).mapNotNull { it.name }
                        _uiState.value = _uiState.value.copy(
                            toSuggestions = mergeSuggestionNames(_uiState.value.toSuggestions, remote)
                        )
                    }
                _uiState.value = _uiState.value.copy(isToSuggesting = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(isToSuggesting = false)
        }
        scheduleAutoSearch()
    }

    // ASCII-only lowercase for the dedup key — "Zürich" and "zurich" both matched the
    // query, but they're different names and must not collapse into one suggestion.
    private fun mergeSuggestionNames(before: List<String>, after: List<String>, max: Int = 5): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (name in before + after) {
            if (seen.add(name.lowercaseAscii())) result.add(name)
            if (result.size >= max) break
        }
        return result
    }

    fun selectFromSuggestion(name: String) {
        fromLocalSuggestJob?.cancel()
        fromSuggestJob?.cancel()
        _uiState.value = _uiState.value.copy(fromText = name, fromSuggestions = emptyList(), isFromSuggesting = false)
        scheduleAutoSearch(immediate = true)
    }

    fun selectToSuggestion(name: String) {
        toLocalSuggestJob?.cancel()
        toSuggestJob?.cancel()
        _uiState.value = _uiState.value.copy(toText = name, toSuggestions = emptyList(), isToSuggesting = false)
        scheduleAutoSearch(immediate = true)
    }

    // Already tracked continuously (see LocationProvider), so this is instant off the
    // cached value — no spinner, no wait. No fix yet → leave the field untouched
    // rather than clearing it. A background refresh is kicked off for next time.
    fun fillFromWithNearestStop() {
        fromLocalSuggestJob?.cancel()
        fromSuggestJob?.cancel()
        locationProvider.refreshNow()
        if (locationProvider.currentLocation.value == null) return
        _uiState.value = _uiState.value.copy(
            fromText = SearchEndpoint.CURRENT_LOCATION_LABEL,
            fromSuggestions = emptyList(),
            isFromSuggesting = false,
        )
        scheduleAutoSearch(immediate = true)
    }

    fun fillToWithNearestStop() {
        toLocalSuggestJob?.cancel()
        toSuggestJob?.cancel()
        locationProvider.refreshNow()
        if (locationProvider.currentLocation.value == null) return
        _uiState.value = _uiState.value.copy(
            toText = SearchEndpoint.CURRENT_LOCATION_LABEL,
            toSuggestions = emptyList(),
            isToSuggesting = false,
        )
        scheduleAutoSearch(immediate = true)
    }

    // Debounced while typing; immediate right after a discrete selection (suggestion tap, GPS fill).
    private fun scheduleAutoSearch(immediate: Boolean = false) {
        autoSearchJob?.cancel()
        if (_uiState.value.fromText.isBlank() || _uiState.value.toText.isBlank()) return
        autoSearchJob = viewModelScope.launch {
            if (!immediate) delay(500)
            search()
        }
    }

    // Resolves the "Current location" placeholder text (typed, tapped, or dragged in)
    // into an actual coordinate-bearing endpoint using the continuously-tracked
    // location; falls back to a plain named lookup for everything else.
    private fun endpointFor(text: String): SearchEndpoint {
        if (text == SearchEndpoint.CURRENT_LOCATION_LABEL) {
            locationProvider.currentLocation.value?.let { (lat, lng) ->
                return SearchEndpoint.CurrentLocation(lat, lng)
            }
        }
        return SearchEndpoint.NamedPlace(text)
    }

    fun onDateSelected(date: LocalDate) {
        _uiState.value = _uiState.value.copy(searchDate = date)
        search()
    }

    fun onTimeSelected(time: LocalTime) {
        _uiState.value = _uiState.value.copy(searchTime = time)
        search()
    }

    fun onToggleArriveBy() {
        _uiState.value = _uiState.value.copy(isArriveBy = !_uiState.value.isArriveBy)
        search()
    }

    // Reverses the from/to endpoints in place and re-runs the search.
    fun swapFromTo() {
        val s = _uiState.value
        _uiState.value = s.copy(
            fromText = s.toText,
            toText = s.fromText,
            fromSuggestions = emptyList(),
            toSuggestions = emptyList(),
        )
        scheduleAutoSearch(immediate = true)
    }

    fun search() {
        val from = _uiState.value.fromText.trim()
        val to   = _uiState.value.toText.trim()
        if (to.isBlank()) return

        val fromEndpoint = endpointFor(from.ifBlank { to })
        val toEndpoint = endpointFor(to)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                connections = emptyList(),
                fromSuggestions = emptyList(),
                toSuggestions = emptyList(),
            )

            val routingTime = routingTimeFor(_uiState.value.searchTime)
            if (localRouter.hasData()) {
                searchLocally(fromEndpoint, toEndpoint, routingTime)
            } else {
                searchViaApi(fromEndpoint, toEndpoint)
            }
        }
    }

    // Pulling up (or reaching the top of the list) fetches connections departing/arriving
    // earlier than what's currently shown; pulling down fetches later ones. Both merge the
    // freshly-fetched connections into the existing list, deduplicated and re-sorted, rather
    // than replacing it — that's what makes the list feel like one continuous infinite scroll.
    fun loadEarlier() {
        val state = _uiState.value
        if (state.isLoadingEarlier || state.isLoading) return
        val first = state.connections.firstOrNull() ?: return
        val firstDeparture = first.departure.scheduledTime?.atZone(SWISS_ZONE)?.toLocalTime() ?: return
        if (firstDeparture == LocalTime.MIDNIGHT) return

        val from = endpointFor(state.fromText.trim().ifBlank { state.toText.trim() })
        val to = endpointFor(state.toText.trim())

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingEarlier = true)
            val earlier = fetchConnections(from, to, state.searchDate, firstDeparture.minusMinutes(1), isArriveBy = true)
            _uiState.value = _uiState.value.copy(
                connections = mergeConnections(earlier, _uiState.value.connections),
                isLoadingEarlier = false,
            )
        }
    }

    fun loadLater() {
        val state = _uiState.value
        if (state.isLoadingLater || state.isLoading) return
        val last = state.connections.lastOrNull() ?: return
        val lastDeparture = last.departure.scheduledTime?.atZone(SWISS_ZONE)?.toLocalTime() ?: return

        val from = endpointFor(state.fromText.trim().ifBlank { state.toText.trim() })
        val to = endpointFor(state.toText.trim())

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingLater = true)
            val later = fetchConnections(from, to, state.searchDate, lastDeparture.plusMinutes(1), isArriveBy = false)
            _uiState.value = _uiState.value.copy(
                connections = mergeConnections(_uiState.value.connections, later),
                isLoadingLater = false,
            )
        }
    }

    private fun routingTimeFor(time: LocalTime): RoutingTime =
        if (_uiState.value.isArriveBy) RoutingTime.ArriveBy(time) else RoutingTime.DepartAfter(time)

    private fun mergeConnections(before: List<Connection>, after: List<Connection>): List<Connection> =
        (before + after)
            .distinctBy { it.departure.scheduledTime to it.arrival.scheduledTime to it.lineNames }
            .sortedBy { it.departure.scheduledTime }

    private suspend fun fetchConnections(
        from: SearchEndpoint,
        to: SearchEndpoint,
        date: LocalDate,
        time: LocalTime,
        isArriveBy: Boolean,
    ): List<Connection> {
        if (localRouter.hasData()) {
            val routingTime = if (isArriveBy) RoutingTime.ArriveBy(time) else RoutingTime.DepartAfter(time)
            var result: List<Connection> = emptyList()
            localRouter.routeConnections(from = from, to = to, date = date, routingTime = routingTime)
                .collect { state -> if (state is LocalRoutingState.Results) result = state.connections }
            return result
        }
        return runCatching {
            repository.getConnections(from, to, date = date, time = time, isArrivalTime = isArriveBy)
        }.getOrDefault(emptyList())
    }

    private suspend fun searchLocally(from: SearchEndpoint, to: SearchEndpoint, routingTime: RoutingTime) {
        localRouter.routeConnections(
            from = from,
            to = to,
            date = _uiState.value.searchDate,
            routingTime = routingTime,
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
        val state = _uiState.value
        runCatching {
            repository.getConnections(
                from, to,
                date = state.searchDate,
                time = state.searchTime,
                isArrivalTime = state.isArriveBy,
            )
        }
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
            from = endpointFor(fromText.ifBlank { toText }),
            to   = endpointFor(toText),
        )
    }
}
