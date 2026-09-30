package ch.rhosys.sbb.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.haversineMeters
import ch.rhosys.sbb.data.remote.dto.LocationDto
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.MapStop
import ch.rhosys.sbb.domain.model.TransportMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// How far the map centre must move before the live-API fallback asks again — the
// API allows ~1 000 requests/day, so panning around must not spend one per frame.
private const val API_REQUERY_METERS = 750.0

@HiltViewModel
class StopMapViewModel @Inject constructor(
    private val locationProvider: LocationProvider,
    private val localRouter: LocalTransportRepository,
    private val transportRepository: TransportRepository,
) : ViewModel() {

    val userLocation: StateFlow<Pair<Double, Double>?> = locationProvider.currentLocation

    private val _stops = MutableStateFlow<List<MapStop>>(emptyList())
    val stops: StateFlow<List<MapStop>> = _stops.asStateFlow()

    private var hasLocalStops = false
    private var lastApiQuery: Pair<Double, Double>? = null
    private var apiJob: Job? = null

    init {
        locationProvider.refreshNow()
        viewModelScope.launch {
            val local = localRouter.mapStops()
            if (local.isNotEmpty()) {
                hasLocalStops = true
                apiJob?.cancel()
                _stops.value = local
            }
        }
    }

    // The on-device GTFS feed already covers every Swiss stop. Until one has been
    // imported, the stations nearest the map centre come from the live API instead,
    // once scrolling settles and only after the centre has moved far enough.
    fun onViewportSettled(lat: Double, lng: Double) {
        if (hasLocalStops) return
        val last = lastApiQuery
        if (last != null && haversineMeters(last.first, last.second, lat, lng) < API_REQUERY_METERS) return
        apiJob?.cancel()
        apiJob = viewModelScope.launch {
            delay(400)
            lastApiQuery = lat to lng
            val response = runCatching { transportRepository.getLocationsByCoordinate(lat, lng) }.getOrNull()
                ?: return@launch
            if (hasLocalStops) return@launch
            val fetched = response.stations.mapNotNull { it.toMapStop() }
            _stops.value = (_stops.value + fetched).distinctBy { it.name }
        }
    }
}

// The API's coordinate x/y axis order has been read both ways elsewhere in this app;
// inside Switzerland latitude (45.8–47.8) is always the larger of the two values
// (longitude 5.9–10.5), so the order is taken from the values themselves.
private fun LocationDto.toMapStop(): MapStop? {
    if (id == null) return null
    val stopName = name ?: return null
    val x = coordinate?.x ?: return null
    val y = coordinate?.y ?: return null
    return MapStop(stopName, lat = maxOf(x, y), lng = minOf(x, y), mode = TransportMode.fromApiIcon(icon))
}
