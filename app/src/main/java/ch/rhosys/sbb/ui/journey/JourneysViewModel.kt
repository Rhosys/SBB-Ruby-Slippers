package ch.rhosys.sbb.ui.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.data.local.routing.rt.GtfsRtStore
import ch.rhosys.sbb.data.local.routing.rt.RtAlert
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.RecurringRoute
import ch.rhosys.sbb.domain.model.SavedRoute
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.domain.model.TripHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

enum class JourneysTab { ACTIVE, PAST, PLANNED }

data class SwitchPrompt(
    val reason: String,
    val betterConnection: Connection,
    val minutesSaved: Int,
)

data class JourneysUiState(
    val selectedTab: JourneysTab = JourneysTab.ACTIVE,
    // Active tab
    val activeConnection: Connection? = null,
    val rtAlerts: List<RtAlert> = emptyList(),
    val switchPrompt: SwitchPrompt? = null,
    val isRestoring: Boolean = false,
    // Past tab
    val lockedInHistory: List<TripHistoryItem> = emptyList(),
    // Planned tab
    val savedRoutes: List<SavedRoute> = emptyList(),
    val recurringRoutes: List<RecurringRoute> = emptyList(),
)

@HiltViewModel
class JourneysViewModel @Inject constructor(
    private val journeyStateHolder: JourneyStateHolder,
    private val transportRepository: TransportRepository,
    private val prefs: UserPreferencesRepository,
    private val rtStore: GtfsRtStore,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        JourneysUiState(
            activeConnection = journeyStateHolder.activeJourney.value?.connection,
        )
    )
    val uiState: StateFlow<JourneysUiState> = _uiState

    init {
        if (journeyStateHolder.activeJourney.value == null) {
            viewModelScope.launch { restoreJourney() }
        }
        startPolling()
        observeRtAlerts()
        observePastAndPlanned()
        viewModelScope.launch { routeRepository.pruneExpiredBrowsedTrips() }
    }

    fun selectTab(tab: JourneysTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    private suspend fun restoreJourney() {
        val persisted = prefs.activeJourney.first() ?: return
        if (persisted.arrivalEpoch <= Instant.now().epochSecond) {
            prefs.clearActiveJourney()
            return
        }

        _uiState.value = _uiState.value.copy(isRestoring = true)

        val connections = runCatching {
            transportRepository.getConnections(
                SearchEndpoint.NamedPlace(persisted.fromName),
                SearchEndpoint.NamedPlace(persisted.toName),
            )
        }.getOrNull()

        if (!connections.isNullOrEmpty()) {
            journeyStateHolder.lockIn(
                connections.first(),
                SearchEndpoint.NamedPlace(persisted.fromName),
                SearchEndpoint.NamedPlace(persisted.toName),
            )
            _uiState.value = _uiState.value.copy(
                activeConnection = connections.first(),
                isRestoring = false,
            )
        } else {
            prefs.clearActiveJourney()
            _uiState.value = _uiState.value.copy(isRestoring = false)
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                poll()
            }
        }
    }

    private suspend fun poll() {
        val journey = journeyStateHolder.activeJourney.value ?: return

        // Auto-expire if arrival is in the past
        val arrivalEpoch = journey.connection.arrival.effectiveTime?.epochSecond
        if (arrivalEpoch != null && arrivalEpoch <= Instant.now().epochSecond) {
            journeyStateHolder.clear()
            _uiState.value = _uiState.value.copy(activeConnection = null, switchPrompt = null)
            return
        }

        val threshold = prefs.switchThresholdMinutes.first()
        val candidates = runCatching {
            transportRepository.getConnections(journey.from, journey.to)
        }.getOrNull() ?: return

        val active = journey.connection
        val activeArrival = active.arrival.effectiveTime ?: return
        val best = candidates.firstOrNull() ?: return
        val bestArrival = best.arrival.effectiveTime ?: return

        val savedSeconds = activeArrival.epochSecond - bestArrival.epochSecond
        val savedMinutes = (savedSeconds / 60).toInt()

        if (savedMinutes >= threshold && best != active) {
            _uiState.value = _uiState.value.copy(
                switchPrompt = SwitchPrompt(
                    reason = buildReason(active, best),
                    betterConnection = best,
                    minutesSaved = savedMinutes,
                )
            )
        }
    }

    private fun observeRtAlerts() {
        viewModelScope.launch {
            rtStore.alerts.collect { allAlerts ->
                val connection = journeyStateHolder.activeJourney.value?.connection
                _uiState.value = _uiState.value.copy(
                    rtAlerts = if (connection == null || allAlerts.isEmpty()) {
                        emptyList()
                    } else {
                        val stopIds = connection.legs
                            .filterIsInstance<Leg.Transit>()
                            .flatMap { listOfNotNull(it.departure.stationId, it.arrival.stationId) }
                        allAlerts.filter { alert ->
                            stopIds.any { id -> alert.informedStopIds.contains(id) } ||
                                    alert.informedRouteIds.isEmpty() && alert.informedStopIds.isEmpty()
                        }
                    }
                )
            }
        }
    }

    private fun observePastAndPlanned() {
        viewModelScope.launch {
            combine(
                routeRepository.getLockedInTripHistory(),
                routeRepository.getSavedRoutes(),
                routeRepository.getRecurringRoutes(),
            ) { history, saved, recurring ->
                Triple(history, saved, recurring)
            }.collect { (history, saved, recurring) ->
                _uiState.value = _uiState.value.copy(
                    lockedInHistory = history,
                    savedRoutes = saved,
                    recurringRoutes = recurring,
                )
            }
        }
    }

    fun confirmSwitch() {
        val prompt = _uiState.value.switchPrompt ?: return
        val journey = journeyStateHolder.activeJourney.value ?: return
        journeyStateHolder.lockIn(prompt.betterConnection, journey.from, journey.to, journey.tripHistoryId)
        _uiState.value = _uiState.value.copy(
            activeConnection = prompt.betterConnection,
            switchPrompt = null,
        )
    }

    fun dismissSwitch() {
        _uiState.value = _uiState.value.copy(switchPrompt = null)
    }

    fun cancelActiveJourney() {
        journeyStateHolder.cancel()
        _uiState.value = _uiState.value.copy(activeConnection = null, switchPrompt = null)
    }

    // Re-locks-in a Past trip whose scheduled arrival hasn't happened yet (e.g. one the
    // user cancelled early). Re-fetches connections rather than replaying stale data, since
    // trip_history only keeps names/times, not the full Connection.
    fun reactivateTrip(item: TripHistoryItem) = viewModelScope.launch {
        val from = SearchEndpoint.NamedPlace(item.fromName)
        val to = SearchEndpoint.NamedPlace(item.toName)
        val connections = runCatching { transportRepository.getConnections(from, to) }.getOrNull()
        val now = Instant.now().epochSecond
        val connection = connections?.firstOrNull { (it.arrival.effectiveTime?.epochSecond ?: 0) > now }
            ?: connections?.firstOrNull()
            ?: return@launch

        val tripHistoryId = routeRepository.recordSearch(
            fromName = item.fromName,
            toName = item.toName,
            toLat = 0.0,
            toLng = 0.0,
            wasLockedIn = true,
            departureEpoch = connection.departure.effectiveTime?.epochSecond,
            arrivalEpoch = connection.arrival.effectiveTime?.epochSecond,
        )
        journeyStateHolder.lockIn(connection, from, to, tripHistoryId)
        _uiState.value = _uiState.value.copy(
            activeConnection = connection,
            selectedTab = JourneysTab.ACTIVE,
        )
    }

    private fun buildReason(active: Connection, better: Connection): String {
        val activeDelay = active.departure.delayMinutes
        return if (activeDelay > 0) {
            "${active.lineNames.firstOrNull() ?: "Train"} is $activeDelay min late."
        } else {
            "A faster option is available."
        }
    }
}
