package ch.rhosys.sbb.wear

import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Answers the watch's "Go to" tile: the next connections from the user's current location
// to one of their home-screen places, and locking one of them in as the active journey.
@Singleton
class WearConnectionsProvider @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val transportRepository: TransportRepository,
    private val locationProvider: LocationProvider,
    private val journeyStateHolder: JourneyStateHolder,
) {
    private data class Shown(val from: SearchEndpoint, val to: SearchEndpoint, val connections: List<Connection>)

    // Last connections sent to the watch, per place, so "Save journey" locks in exactly
    // the connection the user picked rather than whatever a fresh search returns.
    private val lastShown = ConcurrentHashMap<Long, Shown>()

    suspend fun connections(request: WearConnectionsRequest): WearConnectionsResponse {
        val place = findPlace(request.placeId) ?: return WearConnectionsResponse(error = "Place not found")
        val from = origin(request.lat, request.lng)
            ?: return WearConnectionsResponse(placeName = place.displayName, error = "Location unavailable")
        val to = place.toSearchEndpoint()
        val connections = runCatching { upcoming(from, to) }.getOrElse {
            return WearConnectionsResponse(placeName = place.displayName, error = "Couldn't load connections")
        }
        lastShown[place.id] = Shown(from, to, connections)
        return WearConnectionsResponse(
            placeName = place.displayName,
            connections = connections.map { it.toWear() },
        )
    }

    suspend fun saveJourney(request: WearSaveJourneyRequest): WearSaveJourneyResponse {
        val shown = lastShown[request.placeId]?.takeIf { s -> s.connections.any { it.stableKey == request.key } }
            ?: refetch(request)
            ?: return WearSaveJourneyResponse(ok = false, error = "Connection no longer available")
        val connection = shown.connections.firstOrNull { it.stableKey == request.key }
            ?: return WearSaveJourneyResponse(ok = false, error = "Connection no longer available")
        journeyStateHolder.startJourney(connection, shown.from, shown.to)
        return WearSaveJourneyResponse(ok = true)
    }

    // The phone process may have been restarted since the watch asked — search again.
    private suspend fun refetch(request: WearSaveJourneyRequest): Shown? {
        val place = findPlace(request.placeId) ?: return null
        val from = origin(request.lat, request.lng) ?: return null
        val to = place.toSearchEndpoint()
        val connections = runCatching { upcoming(from, to) }.getOrNull() ?: return null
        return Shown(from, to, connections).also { lastShown[place.id] = it }
    }

    private suspend fun findPlace(id: Long): Place? =
        placeRepository.getPlaces().first().firstOrNull { it.id == id }

    private suspend fun origin(lat: Double?, lng: Double?): SearchEndpoint.CurrentLocation? {
        if (lat != null && lng != null) return SearchEndpoint.CurrentLocation(lat, lng)
        return locationProvider.getFreshLocationOrNull()?.let { (la, ln) -> SearchEndpoint.CurrentLocation(la, ln) }
    }

    private suspend fun upcoming(from: SearchEndpoint, to: SearchEndpoint): List<Connection> {
        val now = Instant.now()
        return transportRepository.getConnections(from, to)
            .filter { c -> c.departure.effectiveTime?.isBefore(now) != true }
            .take(WEAR_CONNECTION_COUNT)
    }
}

private fun Connection.toWear(): WearConnection {
    val firstTransit = legs.firstOrNull { it is Leg.Transit } as? Leg.Transit
    val boarding = firstTransit?.departure ?: departure
    return WearConnection(
        key = stableKey,
        departureEpochSeconds = boarding.effectiveTime?.epochSecond,
        departureTime = boarding.displayTime(),
        arrivalTime = arrival.displayTime(),
        boardingStop = boarding.stationName,
        platform = boarding.platform,
        lines = lineNames,
        transfers = transfers,
        delayMinutes = boarding.delayMinutes,
    )
}
