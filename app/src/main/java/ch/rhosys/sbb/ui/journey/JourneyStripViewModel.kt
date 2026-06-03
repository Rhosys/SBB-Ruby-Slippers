package ch.rhosys.sbb.ui.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        JourneyStripUiState(activeConnection = journeyStateHolder.activeJourney.value?.connection)
    )
    val uiState: StateFlow<JourneyStripUiState> = _uiState

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            while (_uiState.value.isMonitoring) {
                delay(30_000)
                poll()
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
