package ch.rhosys.sbb.data.local.routing.rt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GtfsRtStore @Inject constructor() {

    private val _tripUpdates = MutableStateFlow(emptyMap<String, RtTripUpdate>())
    val tripUpdates: StateFlow<Map<String, RtTripUpdate>> = _tripUpdates.asStateFlow()

    private val _alerts = MutableStateFlow(emptyList<RtAlert>())
    val alerts: StateFlow<List<RtAlert>> = _alerts.asStateFlow()

    fun update(updates: List<RtTripUpdate>, alerts: List<RtAlert>) {
        _tripUpdates.value = updates.associateBy { it.tripId }
        _alerts.value = alerts
    }

    fun delaySecondsForStop(tripId: String, stopId: String?): Int? {
        val update = _tripUpdates.value[tripId] ?: return null
        val entry = update.stopDelays.firstOrNull { it.stopId == stopId } ?: return null
        return entry.arrivalDelaySec ?: entry.departureDelaySec
    }

    fun alertsForTrip(tripId: String): List<RtAlert> =
        _alerts.value.filter { it.informedTripIds.contains(tripId) }

    fun alertsForStop(stopId: String): List<RtAlert> =
        _alerts.value.filter { it.informedStopIds.contains(stopId) }

    fun allAlerts(): List<RtAlert> = _alerts.value
}
