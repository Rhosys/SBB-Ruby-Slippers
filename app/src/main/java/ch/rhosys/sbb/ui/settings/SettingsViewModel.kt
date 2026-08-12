package ch.rhosys.sbb.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.worker.CalendarSyncWorker
import ch.rhosys.sbb.worker.GtfsRtRefreshWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val walkingPaceKmh: Float = 6f,
    val runningPaceKmh: Float = 10f,
    val switchThresholdMinutes: Int = 1,
    val calendarSyncEnabled: Boolean = false,
    val calendarSyncIntervalHours: Int = 4,
    val rtToken: String = "",
    val rtLastSuccessEpoch: Long? = null,
    val rtLastErrorEpoch: Long? = null,
    val rtLastErrorMessage: String? = null,
)

private data class RtStatus(
    val token: String,
    val lastSuccessEpoch: Long?,
    val lastErrorEpoch: Long?,
    val lastErrorMessage: String?,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(prefs.walkingPaceKmh, prefs.runningPaceKmh, prefs.switchThresholdMinutes) { w, r, t -> Triple(w, r, t) },
        combine(prefs.calendarSyncEnabled, prefs.calendarSyncIntervalHours) { e, i -> e to i },
        combine(
            prefs.rtToken, prefs.rtLastSuccessEpoch, prefs.rtLastErrorEpoch, prefs.rtLastErrorMessage,
        ) { token, successEpoch, errorEpoch, errorMsg -> RtStatus(token, successEpoch, errorEpoch, errorMsg) },
    ) { (walking, running, threshold), (calSync, calInterval), rt ->
        SettingsUiState(
            walkingPaceKmh = walking,
            runningPaceKmh = running,
            switchThresholdMinutes = threshold,
            calendarSyncEnabled = calSync,
            calendarSyncIntervalHours = calInterval,
            rtToken = rt.token,
            rtLastSuccessEpoch = rt.lastSuccessEpoch,
            rtLastErrorEpoch = rt.lastErrorEpoch,
            rtLastErrorMessage = rt.lastErrorMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setWalkingPace(kmh: Float) = viewModelScope.launch { prefs.setWalkingPace(kmh) }
    fun setRunningPace(kmh: Float) = viewModelScope.launch { prefs.setRunningPace(kmh) }
    fun setSwitchThreshold(minutes: Int) = viewModelScope.launch { prefs.setSwitchThreshold(minutes) }

    fun setCalendarSync(enabled: Boolean) = viewModelScope.launch {
        prefs.setCalendarSyncEnabled(enabled)
        if (enabled) {
            val interval = prefs.calendarSyncIntervalHours.first()
            CalendarSyncWorker.schedule(context, interval)
        } else {
            CalendarSyncWorker.cancel(context)
        }
    }

    fun setCalendarSyncInterval(hours: Int) = viewModelScope.launch {
        prefs.setCalendarSyncIntervalHours(hours)
        if (prefs.calendarSyncEnabled.first()) {
            CalendarSyncWorker.schedule(context, hours)
        }
    }

    private var rtTriggerJob: Job? = null

    fun setRtToken(token: String) {
        viewModelScope.launch { prefs.setRtToken(token) }

        // Debounced: validating on every keystroke would hammer the RT feed while typing.
        rtTriggerJob?.cancel()
        if (token.isNotBlank()) {
            rtTriggerJob = viewModelScope.launch {
                delay(800)
                GtfsRtRefreshWorker.triggerOnce(context)
            }
        }
    }
}
