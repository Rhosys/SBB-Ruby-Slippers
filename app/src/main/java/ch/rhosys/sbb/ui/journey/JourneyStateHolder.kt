package ch.rhosys.sbb.ui.journey

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ch.rhosys.sbb.data.local.preferences.PersistedJourney
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.notification.JourneyNotificationService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JourneyStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val autoClearManager: JourneyAutoClearManager,
    private val routeRepository: RouteRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeJourney = MutableStateFlow<ActiveJourney?>(null)
    val activeJourney: StateFlow<ActiveJourney?> = _activeJourney

    private val _missedBoardingPrompt = MutableStateFlow(false)
    val missedBoardingPrompt: StateFlow<Boolean> = _missedBoardingPrompt

    fun lockIn(connection: Connection, from: SearchEndpoint, to: SearchEndpoint, tripHistoryId: Long? = null) {
        _activeJourney.value = ActiveJourney(connection, from, to, tripHistoryId)
        val departureEpoch = connection.departure.effectiveTime?.epochSecond
        val arrivalEpoch = connection.arrival.effectiveTime?.epochSecond
        scope.launch {
            arrivalEpoch?.let {
                prefs.persistActiveJourney(
                    PersistedJourney(fromName = from.displayName(), toName = to.displayName(), arrivalEpoch = it)
                )
            }
        }
        autoClearManager.schedule(
            fromLat = from.latOrNull(), fromLng = from.lngOrNull(),
            toLat = to.latOrNull(), toLng = to.lngOrNull(),
            departureEpoch = departureEpoch,
            arrivalEpoch = arrivalEpoch,
        )
        ContextCompat.startForegroundService(context, Intent(context, JourneyNotificationService::class.java))
    }

    fun promptMissedBoarding() {
        if (_activeJourney.value != null) _missedBoardingPrompt.value = true
    }

    fun dismissMissedBoardingPrompt() {
        _missedBoardingPrompt.value = false
    }

    fun clear() {
        _activeJourney.value = null
        _missedBoardingPrompt.value = false
        autoClearManager.cancel()
        scope.launch { prefs.clearActiveJourney() }
        context.stopService(Intent(context, JourneyNotificationService::class.java))
    }

    // Explicit user-initiated cancellation, as opposed to arrival/geofence auto-clear:
    // also marks the trip_history row so it still shows (as cancelled) in Past.
    fun cancel() {
        val tripHistoryId = _activeJourney.value?.tripHistoryId
        _activeJourney.value = null
        _missedBoardingPrompt.value = false
        autoClearManager.cancel()
        scope.launch {
            prefs.clearActiveJourney()
            tripHistoryId?.let { routeRepository.markTripCancelled(it) }
        }
        context.stopService(Intent(context, JourneyNotificationService::class.java))
    }

    data class ActiveJourney(
        val connection: Connection,
        val from: SearchEndpoint,
        val to: SearchEndpoint,
        val tripHistoryId: Long? = null,
    )
}
