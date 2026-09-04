package ch.rhosys.sbb.data.local.routing

import ch.rhosys.sbb.data.local.routing.algorithm.FoundConnection
import ch.rhosys.sbb.data.local.routing.algorithm.FoundLeg
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingEngine
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingQuery
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkStore
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsParser
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsStop
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.domain.model.Stop
import ch.rhosys.sbb.util.lowercaseAscii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val SWISS_ZONE = ZoneId.of("Europe/Zurich")
private const val WALK_RADIUS_METERS = 500.0
private const val MAX_ORIGIN_STOPS = 5
private const val MAX_DEST_STOPS = 5

data class StopSuggestion(val name: String, val lat: Double, val lng: Double)

@Singleton
class LocalTransportRepository @Inject constructor(
    private val store: GtfsNetworkStore,
) {
    private var cached: CachedData? = null
    private val lock = Mutex()

    private data class CachedData(
        val parsed: GtfsParser.ParsedGtfs,
        val engine: RoutingEngine,
    )

    private suspend fun getOrLoad(): CachedData? = lock.withLock {
        if (cached == null) {
            val parsed = store.read() ?: return null
            cached = CachedData(parsed, RoutingEngine(parsed.network, parsed.calendar))
        }
        cached
    }

    // Call after a successful import to force a reload on next query.
    fun invalidate() { cached = null }

    fun hasData(): Boolean = store.hasData()

    // Offline nearest-stop lookup over the on-device GTFS cache — used to label the
    // "current location" badge with a real station name without a network round trip.
    suspend fun nearestStopName(lat: Double, lng: Double): String? {
        val data = getOrLoad() ?: return null
        return withContext(Dispatchers.Default) {
            data.parsed.network.stops.minByOrNull { haversineMeters(it.lat, it.lng, lat, lng) }?.name
        }
    }

    // Instant, offline name search over the on-device GTFS stop cache — a substring match
    // against the ASCII-lowercased name, so "zurich" still matches "Zürich" but distinct
    // accented names never collapse into the same key. Meant to be combined with (and
    // deduplicated against) the live API suggestions, not to replace them: the cache only
    // covers stops in the last-imported GTFS feed, and never departures/times.
    suspend fun searchStopNames(query: String, max: Int = 5): List<StopSuggestion> {
        val data = getOrLoad() ?: return emptyList()
        val q = query.trim().lowercaseAscii()
        if (q.isEmpty()) return emptyList()
        // The GTFS stop list can run into the thousands, so the scan is pushed off the
        // caller's dispatcher (typically Main, since this is called on every keystroke)
        // to keep the UI thread free while typing.
        return withContext(Dispatchers.Default) {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<StopSuggestion>()
            for (stop in data.parsed.network.stops) {
                if (!stop.name.lowercaseAscii().contains(q)) continue
                if (!seen.add(stop.name.lowercaseAscii())) continue
                result.add(StopSuggestion(stop.name, stop.lat, stop.lng))
                if (result.size >= max) break
            }
            result
        }
    }

    fun routeConnections(
        from: SearchEndpoint,
        to: SearchEndpoint,
        date: LocalDate,
        routingTime: RoutingTime,
        walkToFirstStop: Duration = Duration.ZERO,
        walkFromLastStop: Duration = Duration.ZERO,
    ): Flow<LocalRoutingState> = flow {
        emit(LocalRoutingState.Loading)

        val data = getOrLoad() ?: run {
            emit(LocalRoutingState.NoData)
            return@flow
        }

        val originIds = resolveStopIds(from, data.parsed.network)
        val destIds = resolveStopIds(to, data.parsed.network)

        if (originIds.isEmpty() || destIds.isEmpty()) {
            emit(LocalRoutingState.NoResults("No stops found near ${if (originIds.isEmpty()) from.displayName() else to.displayName()}"))
            return@flow
        }

        val query = RoutingQuery(
            originStopIds = originIds,
            destinationStopIds = destIds,
            date = date,
            routingTime = routingTime,
            walkToFirstStop = walkToFirstStop,
            walkFromLastStop = walkFromLastStop,
        )

        var hadAnyResult = false
        data.engine.route(query).collect { result ->
            val connections = result.connections.mapNotNull { found ->
                found.toDomain(data.parsed.network, date, walkToFirstStop, walkFromLastStop)
            }
            if (connections.isNotEmpty()) {
                hadAnyResult = true
                emit(LocalRoutingState.Results(connections, result.isComplete))
            }
        }

        if (!hadAnyResult) {
            emit(LocalRoutingState.NoResults())
        }
    }

    // ---- Stop resolver -------------------------------------------------------

    private fun resolveStopIds(endpoint: SearchEndpoint, network: GtfsNetwork): List<Int> =
        when (endpoint) {
            is SearchEndpoint.CurrentLocation ->
                nearbyStops(network.stops, endpoint.lat, endpoint.lng, WALK_RADIUS_METERS, MAX_ORIGIN_STOPS)
                    .map { it.id }

            is SearchEndpoint.NamedPlace -> {
                val lat = endpoint.lat
                val lng = endpoint.lng
                if (lat != null && lng != null) {
                    nearbyStops(network.stops, lat, lng, WALK_RADIUS_METERS, MAX_DEST_STOPS).map { it.id }
                } else {
                    val query = endpoint.name.lowercase()
                    network.stops.filter { it.name.lowercase().contains(query) }
                        .take(MAX_DEST_STOPS)
                        .map { it.id }
                }
            }
        }

    private fun nearbyStops(stops: List<GtfsStop>, lat: Double, lng: Double, radiusM: Double, max: Int): List<GtfsStop> =
        stops.map { it to haversineMeters(it.lat, it.lng, lat, lng) }
            .filter { (_, d) -> d <= radiusM }
            .sortedBy { (_, d) -> d }
            .take(max)
            .map { (stop, _) -> stop }

    // ---- Domain conversion ---------------------------------------------------

    private fun FoundConnection.toDomain(
        network: GtfsNetwork,
        date: LocalDate,
        walkToFirst: Duration,
        walkFromLast: Duration,
    ): Connection? {
        val dayStartInstant = date.atStartOfDay(SWISS_ZONE).toInstant()
        val domainLegs = legs.map { leg ->
            when (leg) {
                is FoundLeg.Transit -> {
                    val boardStop = network.stops.getOrNull(leg.boardStopId) ?: return null
                    val alightStop = network.stops.getOrNull(leg.alightStopId) ?: return null
                    val route = network.routes.getOrNull(leg.routeIdx)
                    val alightPos = route?.stopIds?.indexOf(leg.alightStopId) ?: -1
                    val intermediateStops = if (route != null && alightPos > leg.boardPos + 1) {
                        route.stopIds.subList(leg.boardPos + 1, alightPos).mapNotNull { id ->
                            network.stops.getOrNull(id)?.let { Stop(stationName = it.name) }
                        }
                    } else emptyList()
                    Leg.Transit(
                        departure = Stop(
                            stationName = boardStop.name,
                            scheduledTime = dayStartInstant.plusSeconds(leg.boardSeconds.toLong()),
                        ),
                        arrival = Stop(
                            stationName = alightStop.name,
                            scheduledTime = dayStartInstant.plusSeconds(leg.alightSeconds.toLong()),
                        ),
                        lineName = leg.routeName,
                        lineCategory = "",
                        direction = alightStop.name,
                        intermediateStops = intermediateStops,
                    )
                }
                is FoundLeg.Walk -> Leg.Walk(
                    fromName = network.stops.getOrNull(leg.fromStopId)?.name ?: "",
                    toName = network.stops.getOrNull(leg.toStopId)?.name ?: "",
                    durationMinutes = (leg.durationSeconds + 30) / 60,
                )
            }
        }

        val transitLegs = domainLegs.filterIsInstance<Leg.Transit>()
        if (transitLegs.isEmpty()) return null

        return Connection(
            departure = transitLegs.first().departure,
            arrival = transitLegs.last().arrival,
            legs = domainLegs,
            transfers = maxOf(0, transitLegs.size - 1),
            walkToFirstStop = walkToFirst,
            walkFromLastStop = walkFromLast,
        )
    }
}

// Haversine distance in metres between two WGS-84 coordinates.
internal fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
