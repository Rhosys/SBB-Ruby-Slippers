package ch.rhosys.sbb.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.worker.CalendarSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(prefs.walkingPaceKmh, prefs.runningPaceKmh, prefs.switchThresholdMinutes) { w, r, t -> Triple(w, r, t) },
        combine(prefs.calendarSyncEnabled, prefs.calendarSyncIntervalHours) { e, i -> e to i },
        prefs.rtToken,
    ) { (walking, running, threshold), (calSync, calInterval), token ->
        SettingsUiState(walking, running, threshold, calSync, calInterval, token)
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

    fun setRtToken(token: String) = viewModelScope.launch { prefs.setRtToken(token) }
}
