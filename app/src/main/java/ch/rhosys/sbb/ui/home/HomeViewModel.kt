package ch.rhosys.sbb.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.RecurringRoute
import ch.rhosys.sbb.domain.model.SavedRoute
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import ch.rhosys.sbb.ui.widget.JourneyWidgetSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject

data class ScorerResult(
    val destination: String,
    val connections: List<Connection>,
    val from: SearchEndpoint,
    val to: SearchEndpoint,
)

data class ActiveJourneyBanner(
    val connection: Connection,
    val from: SearchEndpoint,
    val to: SearchEndpoint,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val places: List<Place> = emptyList(),
    val scorerResult: ScorerResult? = null,
    val activeJourney: ActiveJourneyBanner? = null,
    val fromText: String = "",
    val toText: String = "",
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val locationProvider: LocationProvider,
    private val placeRepository: PlaceRepository,
    private val routeRepository: RouteRepository,
    private val transportRepository: TransportRepository,
    private val journeyStateHolder: JourneyStateHolder,
    private val widgetSyncer: JourneyWidgetSyncer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        viewModelScope.launch { infer() }
        viewModelScope.launch {
            journeyStateHolder.activeJourney.collect { activeJourney ->
                _uiState.value = _uiState.value.copy(
                    activeJourney = if (activeJourney != null) {
                        ActiveJourneyBanner(activeJourney.connection, activeJourney.from, activeJourney.to)
                    } else null
                )
            }
        }
        viewModelScope.launch {
            placeRepository.getPlaces().collect { places ->
                _uiState.value = _uiState.value.copy(places = places)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { infer() }
    }

    fun onFromTextChanged(value: String) {
        _uiState.value = _uiState.value.copy(fromText = value)
    }

    fun onToTextChanged(value: String) {
        _uiState.value = _uiState.value.copy(toText = value)
    }

    fun dismissScorer() {
        _uiState.value = _uiState.value.copy(scorerResult = null)
        // Widget keeps showing the last scorer result as sticky glanceable info.
    }

    fun abandonActiveJourney() {
        journeyStateHolder.clear()
    }

    fun routeFromCurrentLocationTo(place: Place) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val location = locationProvider.getLocationOrNull()
            val from = if (location != null)
                SearchEndpoint.CurrentLocation(location.first, location.second)
            else
                SearchEndpoint.NamedPlace(place.name)

            val connections = runCatching {
                transportRepository.getConnections(from, place.toSearchEndpoint())
            }.getOrNull() ?: emptyList()

            val result = if (connections.isNotEmpty()) {
                ScorerResult(
                    destination = place.name,
                    connections = connections,
                    from = from,
                    to = place.toSearchEndpoint(),
                )
            } else null
            _uiState.value = _uiState.value.copy(isLoading = false, scorerResult = result)
            if (result != null) notifyWidget(result) else widgetSyncer.clearScorerResult()
        }
    }

    private suspend fun infer() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val location = locationProvider.getLocationOrNull()
        val candidate = scoreBestCandidate()

        if (candidate != null) {
            val from = if (location != null)
                SearchEndpoint.CurrentLocation(location.first, location.second)
            else
                SearchEndpoint.NamedPlace("")

            val connections = runCatching {
                transportRepository.getConnections(from, candidate.toDestinationEndpoint())
            }.getOrNull() ?: emptyList()

            if (connections.isNotEmpty()) {
                val result = ScorerResult(
                    destination = candidate.destinationName,
                    connections = connections,
                    from = from,
                    to = candidate.toDestinationEndpoint(),
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    scorerResult = result,
                )
                notifyWidget(result)
                return
            }
        }

        _uiState.value = _uiState.value.copy(isLoading = false)
        widgetSyncer.clearScorerResult()
    }

    private fun notifyWidget(result: ScorerResult) {
        val best = result.connections.first()
        widgetSyncer.onScorerResult(
            to = result.destination,
            departTime = best.departure.displayTime(),
            arriveTime = best.arrival.displayTime(),
            lines = best.lineNames.joinToString(" · "),
        )
    }

    private suspend fun scoreBestCandidate(): SavedRoute? {
        val now = Instant.now()
        val windowEnd = now.plusSeconds(2 * 60 * 60)

        val savedRoutes = routeRepository.getSavedRoutes().first()
        val imminentRoute = savedRoutes
            .filter { route ->
                val scheduledAt = route.scheduledAt ?: return@filter false
                scheduledAt.isAfter(now) && scheduledAt.isBefore(windowEnd)
            }
            .minByOrNull { it.scheduledAt!! }
        if (imminentRoute != null) return imminentRoute

        val recurringRoutes = routeRepository.getRecurringRoutes().first()
        val recurringCandidate = recurringRoutes
            .filter { !it.isPaused && it.matchesToday() }
            .filter { route ->
                val deptTime = LocalTime.of(route.departureHour, route.departureMinute)
                val nowTime = LocalTime.now()
                deptTime.isAfter(nowTime.minusMinutes(5)) && deptTime.isBefore(nowTime.plusHours(2))
            }
            .minByOrNull { route ->
                LocalTime.of(route.departureHour, route.departureMinute).toSecondOfDay()
            }
        if (recurringCandidate != null) {
            return SavedRoute(
                id = -1,
                label = recurringCandidate.label,
                destinationName = recurringCandidate.destinationName,
                destinationLat = recurringCandidate.destinationLat,
                destinationLng = recurringCandidate.destinationLng,
                scheduledAt = null,
            )
        }

        return null
    }
}

private fun RecurringRoute.matchesToday(): Boolean {
    val dayCode = when (ZonedDateTime.now().dayOfWeek) {
        DayOfWeek.MONDAY    -> "MO"
        DayOfWeek.TUESDAY   -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY  -> "TH"
        DayOfWeek.FRIDAY    -> "FR"
        DayOfWeek.SATURDAY  -> "SA"
        DayOfWeek.SUNDAY    -> "SU"
        else                -> return false
    }

    val rules = rrule.split(";").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx < 0) null else part.substring(0, idx) to part.substring(idx + 1)
    }.toMap()

    return when (rules["FREQ"]) {
        "DAILY"   -> true
        "WEEKLY"  -> {
            val byday = rules["BYDAY"] ?: return true
            dayCode in byday.split(",")
        }
        else -> false
    }
}
