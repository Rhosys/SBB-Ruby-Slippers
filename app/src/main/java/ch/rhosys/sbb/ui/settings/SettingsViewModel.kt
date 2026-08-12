package ch.rhosys.sbb.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.worker.CalendarSyncResult
import ch.rhosys.sbb.worker.CalendarSyncWorker
import ch.rhosys.sbb.worker.CalendarSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
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
    val calendarSyncing: Boolean = false,
    val calendarSyncError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val calendarSyncer: CalendarSyncer,
) : ViewModel() {

    private val calendarSyncing = MutableStateFlow(false)
    private val calendarSyncError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(prefs.walkingPaceKmh, prefs.runningPaceKmh, prefs.switchThresholdMinutes) { w, r, t -> Triple(w, r, t) },
        combine(prefs.calendarSyncEnabled, prefs.calendarSyncIntervalHours) { e, i -> e to i },
        prefs.rtToken,
        combine(calendarSyncing, calendarSyncError) { syncing, error -> syncing to error },
    ) { (walking, running, threshold), (calSync, calInterval), token, (syncing, error) ->
        SettingsUiState(walking, running, threshold, calSync, calInterval, token, syncing, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setWalkingPace(kmh: Float) = viewModelScope.launch { prefs.setWalkingPace(kmh) }
    fun setRunningPace(kmh: Float) = viewModelScope.launch { prefs.setRunningPace(kmh) }
    fun setSwitchThreshold(minutes: Int) = viewModelScope.launch { prefs.setSwitchThreshold(minutes) }

    /**
     * Called once READ_CALENDAR is granted. Runs an immediate sync check before persisting
     * the enabled flag — the toggle only turns (and stays) on if that check succeeds.
     */
    fun enableCalendarSync() = viewModelScope.launch {
        calendarSyncError.value = null
        calendarSyncing.value = true
        when (val result = calendarSyncer.sync()) {
            is CalendarSyncResult.Success -> {
                prefs.setCalendarSyncEnabled(true)
                CalendarSyncWorker.schedule(context, prefs.calendarSyncIntervalHours.first())
            }
            is CalendarSyncResult.Failure -> calendarSyncError.value = result.message
        }
        calendarSyncing.value = false
    }

    /** The permission prompt was declined — surface it as a sync error; the toggle stays off. */
    fun onCalendarPermissionDenied() {
        calendarSyncError.value = "Calendar permission is required to sync"
    }

    fun disableCalendarSync() = viewModelScope.launch {
        prefs.setCalendarSyncEnabled(false)
        CalendarSyncWorker.cancel(context)
        calendarSyncError.value = null
    }

    fun syncCalendarNow() = viewModelScope.launch {
        calendarSyncError.value = null
        calendarSyncing.value = true
        when (val result = calendarSyncer.sync()) {
            is CalendarSyncResult.Success -> {}
            is CalendarSyncResult.Failure -> calendarSyncError.value = result.message
        }
        calendarSyncing.value = false
    }

    fun setCalendarSyncInterval(hours: Int) = viewModelScope.launch {
        prefs.setCalendarSyncIntervalHours(hours)
        if (prefs.calendarSyncEnabled.first()) {
            CalendarSyncWorker.schedule(context, hours)
        }
    }

    fun setRtToken(token: String) = viewModelScope.launch { prefs.setRtToken(token) }
}
