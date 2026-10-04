package ch.rhosys.sbb.data.local.routing

import ch.rhosys.sbb.data.local.routing.algorithm.DEFAULT_SEARCH_WINDOW
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
import ch.rhosys.sbb.domain.model.MapStop
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.domain.model.Stop
import ch.rhosys.sbb.domain.model.TransportMode
import ch.rhosys.sbb.domain.model.paretoOptimal
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
// Paging (see pageConnections): at most this many connections per page…
const val PAGE_MAX_RESULTS = 10
// …found no further than this from the edge of the list.
val PAGE_SPAN: Duration = Duration.ofHours(24)

enum class PageDirection { EARLIER, LATER }

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
    ) {
        // Built on first map open only — routing never needs it.
        val mapStops: List<MapStop> by lazy { buildMapStops(parsed.network) }
    }

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

    // Every stop that some route actually serves, for the map picker. Empty when no
    // GTFS feed has been imported yet (the map then falls back to the live API).
    suspend fun mapStops(): List<MapStop> {
        val data = getOrLoad() ?: return emptyList()
        return withContext(Dispatchers.Default) { data.mapStops }
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
        // km/h. Defaults to UserPreferencesRepository's own default so callers that
        // don't have a preference on hand yet still get a sensible pace.
        walkingPaceKmh: Float = 6f,
        // See RoutingQuery.window.
        window: Duration = DEFAULT_SEARCH_WINDOW,
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
            walkingPaceMetersPerSecond = walkingPaceKmh * 1000.0 / 3600.0,
            window = window,
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

    // One page outward from an edge of the list already shown: the [maxResults]
    // non-dominated connections leaving nearest to [edge] — strictly after it for LATER,
    // strictly before it for EARLIER — looking no further than [span] away. Unlike the
    // first search this is bounded by count, not by a time window, so a busy route
    // pages a few minutes at a time and a quiet one jumps straight to its next train.
    //
    // The search reaches out 1 h, 2 h, 4 h … until it has [maxResults] or covers [span];
    // each step re-runs the whole range, so the result is the same as searching [span]
    // in one go, but a busy route stops after the first hour or two. A range crossing
    // midnight is split into one search per service day.
    suspend fun pageConnections(
        from: SearchEndpoint,
        to: SearchEndpoint,
        edge: Instant,
        direction: PageDirection,
        walkingPaceKmh: Float = 6f,
        maxResults: Int = PAGE_MAX_RESULTS,
        span: Duration = PAGE_SPAN,
    ): List<Connection> {
        var reach = Duration.ofHours(1)
        while (true) {
            if (reach > span) reach = span
            val (start, end) = when (direction) {
                PageDirection.LATER -> edge.plusSeconds(1) to edge.plus(reach)
                PageDirection.EARLIER -> edge.minus(reach) to edge.minusSeconds(1)
            }
            val found = departingBetween(from, to, start, end, walkingPaceKmh)
                .filter { c ->
                    val dep = c.departure.scheduledTime ?: return@filter false
                    !dep.isBefore(start) && !dep.isAfter(end)
                }
                .distinctBy { it.stableKey }
                .paretoOptimal { it.criteria }
            if (found.size >= maxResults || reach >= span) {
                val nearestFirst = when (direction) {
                    PageDirection.LATER -> found.sortedBy { it.departure.scheduledTime }
                    PageDirection.EARLIER -> found.sortedByDescending { it.departure.scheduledTime }
                }
                return nearestFirst.take(maxResults).sortedBy { it.departure.scheduledTime }
            }
            reach = reach.multipliedBy(2)
        }
    }

    private suspend fun departingBetween(
        from: SearchEndpoint,
        to: SearchEndpoint,
        start: Instant,
        end: Instant,
        walkingPaceKmh: Float,
    ): List<Connection> {
        val first = start.atZone(SWISS_ZONE)
        val last = end.atZone(SWISS_ZONE)
        val result = mutableListOf<Connection>()
        var date = first.toLocalDate()
        while (!date.isAfter(last.toLocalDate())) {
            val lo = if (date == first.toLocalDate()) first.toLocalTime() else LocalTime.MIDNIGHT
            val hi = if (date == last.toLocalDate()) last.toLocalTime() else LocalTime.MAX
            var dayResults: List<Connection> = emptyList()
            routeConnections(
                from, to, date, RoutingTime.DepartBetween(lo, hi), walkingPaceKmh = walkingPaceKmh,
            ).collect { state -> if (state is LocalRoutingState.Results) dayResults = state.connections }
            result += dayResults
            date = date.plusDays(1)
        }
        return result
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
                        // Rounded up: planning around a delay is only safe if it's never understated.
                        expectedDelayMinutes = (leg.expectedDelaySeconds + 59) / 60,
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

// The feed lists a station's parent stop plus each of its platforms under the same
// name, so stops are merged by name into one pin (at the first member's position),
// shown as the most prominent mode any member is served by. Stops no route serves
// (parent stations, entrances) still lend their position to the group but never
// create a pin on their own.
internal fun buildMapStops(network: GtfsNetwork): List<MapStop> {
    val modeByStop = HashMap<Int, TransportMode>()
    for (route in network.routes) {
        for (stopId in route.stopIds) {
            val current = modeByStop[stopId]
            if (current == null || route.mode < current) modeByStop[stopId] = route.mode
        }
    }
    val firstByName = LinkedHashMap<String, GtfsStop>()
    val modeByName = HashMap<String, TransportMode>()
    for (stop in network.stops) {
        if (stop.lat == 0.0 && stop.lng == 0.0) continue
        firstByName.putIfAbsent(stop.name, stop)
        val mode = modeByStop[stop.id] ?: continue
        val current = modeByName[stop.name]
        if (current == null || mode < current) modeByName[stop.name] = mode
    }
    return firstByName.values.mapNotNull { stop ->
        val mode = modeByName[stop.name] ?: return@mapNotNull null
        MapStop(stop.name, stop.lat, stop.lng, mode)
    }
}
