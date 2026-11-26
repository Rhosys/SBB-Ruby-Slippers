package ch.rhosys.sbb.wear

import ch.rhosys.sbb.data.local.location.LocationProvider
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.data.local.routing.LocalRoutingState
import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import ch.rhosys.sbb.ui.search.mergeConnections
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val SWISS_ZONE = ZoneId.of("Europe/Zurich")

// Answers the watch's "Go to" tile: the next connections from the user's current location
// to one of their home-screen places, and locking one of them in as the active journey.
@Singleton
class WearConnectionsProvider @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val transportRepository: TransportRepository,
    private val locationProvider: LocationProvider,
    private val journeyStateHolder: JourneyStateHolder,
    private val localRouter: LocalTransportRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
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

    // Same order as the phone's own search screen: the on-device route planner when the
    // timetable is downloaded (offline, instant, no API rate limit), the API without it.
    private suspend fun upcoming(from: SearchEndpoint, to: SearchEndpoint): List<Connection> {
        val now = ZonedDateTime.now(SWISS_ZONE)
        val found = routeLocally(from, to, now) ?: transportRepository.getConnections(from, to)
        return mergeConnections(emptyList(), found)
            .filter { c -> c.departure.effectiveTime?.isBefore(now.toInstant()) != true }
            .take(WEAR_CONNECTION_COUNT)
    }

    // Null when there's no timetable to route on, so the caller falls back to the API.
    private suspend fun routeLocally(from: SearchEndpoint, to: SearchEndpoint, now: ZonedDateTime): List<Connection>? {
        if (!localRouter.hasData()) return null
        var connections = emptyList<Connection>()
        var noData = false
        localRouter.routeConnections(
            from = from,
            to = to,
            date = now.toLocalDate(),
            routingTime = RoutingTime.DepartAfter(now.toLocalTime()),
            walkingPaceKmh = userPreferencesRepository.walkingPaceKmh.first(),
        ).collect { state ->
            when (state) {
                is LocalRoutingState.Results -> connections = state.connections
                is LocalRoutingState.NoData -> noData = true
                is LocalRoutingState.NoResults, LocalRoutingState.Loading -> Unit
            }
        }
        return if (noData) null else connections
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
