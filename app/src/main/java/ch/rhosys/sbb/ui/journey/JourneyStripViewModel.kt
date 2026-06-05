package ch.rhosys.sbb.ui.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.data.local.routing.rt.GtfsRtStore
import ch.rhosys.sbb.data.local.routing.rt.RtAlert
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.SearchEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class JourneyStripUiState(
    val activeConnection: Connection?,
    val switchPrompt: SwitchPrompt? = null,
    val isMonitoring: Boolean = true,
    val isRestoring: Boolean = false,
    val rtAlerts: List<RtAlert> = emptyList(),
)

data class SwitchPrompt(
    val reason: String,
    val betterConnection: Connection,
    val minutesSaved: Int,
)

@HiltViewModel
class JourneyStripViewModel @Inject constructor(
    private val journeyStateHolder: JourneyStateHolder,
    private val transportRepository: TransportRepository,
    private val prefs: UserPreferencesRepository,
    private val rtStore: GtfsRtStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        JourneyStripUiState(activeConnection = journeyStateHolder.activeJourney.value?.connection)
    )
    val uiState: StateFlow<JourneyStripUiState> = _uiState

    init {
        if (journeyStateHolder.activeJourney.value == null) {
            viewModelScope.launch { restoreJourney() }
        }
        startMonitoring()
        observeRtAlerts()
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

    private fun startMonitoring() {
        viewModelScope.launch {
            while (_uiState.value.isMonitoring) {
                delay(30_000)
                poll()
            }
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

    private suspend fun poll() {
        val journey = journeyStateHolder.activeJourney.value ?: return
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

    fun confirmSwitch() {
        val prompt = _uiState.value.switchPrompt ?: return
        val journey = journeyStateHolder.activeJourney.value ?: return
        journeyStateHolder.lockIn(prompt.betterConnection, journey.from, journey.to)
        _uiState.value = _uiState.value.copy(
            activeConnection = prompt.betterConnection,
            switchPrompt = null,
        )
    }

    fun dismissSwitch() {
        _uiState.value = _uiState.value.copy(switchPrompt = null)
    }

    fun abandonJourney() {
        _uiState.value = _uiState.value.copy(isMonitoring = false)
        journeyStateHolder.clear()
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
