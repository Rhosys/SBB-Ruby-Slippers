package ch.rhosys.sbb.data.local.routing.algorithm

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsRoute
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val INF = Int.MAX_VALUE / 2
private const val MAX_ROUNDS = 7
private const val BUDGET_MS = 20_000L
private const val ROUND_BUDGET_MS = 10_000L

class RoutingEngine(private val network: GtfsNetwork) {

    fun route(query: RoutingQuery): Flow<RoutingResult> = flow {
        val startMs = System.currentTimeMillis()

        // best[stopId] = earliest arrival in seconds at that stop across all rounds
        val best = IntArray(network.stops.size) { INF }
        // bestWithTrips[stopId] = earliest arrival reachable by at least one transit leg
        val bestWithTransit = IntArray(network.stops.size) { INF }

        // Journey pointer: how did we reach each stop?
        // Stored as a linked list of legs via the JourneyPointer structure
        val pointer = arrayOfNulls<JourneyPointer>(network.stops.size)

        // Seed origins
        for (stopId in query.originStopIds) {
            best[stopId] = query.departureAfterSeconds
        }

        val destSet = query.destinationStopIds.toSet()
        val accumulator = mutableListOf<FoundConnection>()
        var roundsCompleted = 0

        for (round in 1..MAX_ROUNDS) {
            val roundStart = System.currentTimeMillis()

            // Stops improved this round (marked stops)
            val marked = BooleanArray(network.stops.size) { false }
            val improved = BooleanArray(network.stops.size) { false }

            // Phase 1: for each marked stop, scan routes through it
            for (stopId in network.stops.indices) {
                if (best[stopId] == INF) continue
                val routes = network.stopToRoutes[stopId] ?: continue
                for ((routeIdx, pos) in routes) {
                    val route = network.routes[routeIdx]
                    val trip = earliestTrip(route, pos, best[stopId]) ?: continue
                    // Ride this trip forward
                    for (p in pos + 1 until route.stopIds.size) {
                        val nextStop = route.stopIds[p]
                        val arrivalSec = tripArrival(trip, p)
                        if (arrivalSec < best[nextStop]) {
                            best[nextStop] = arrivalSec
                            bestWithTransit[nextStop] = arrivalSec
                            improved[nextStop] = true
                            pointer[nextStop] = JourneyPointer(
                                leg = FoundLeg.Transit(
                                    routeName = route.name,
                                    boardStopId = stopId,
                                    alightStopId = nextStop,
                                    boardSeconds = tripDeparture(trip, pos),
                                    alightSeconds = arrivalSec,
                                ),
                                prev = pointer[stopId],
                                prevStopId = stopId,
                            )
                        }
                    }
                }
            }

            // Phase 2: walking transfers
            for (stopId in network.stops.indices) {
                if (!improved[stopId]) continue
                val transfers = network.stopToTransfers[stopId] ?: continue
                for ((neighbourId, walkSecs) in transfers) {
                    val arrivalViaWalk = best[stopId] + walkSecs
                    if (arrivalViaWalk < best[neighbourId]) {
                        best[neighbourId] = arrivalViaWalk
                        marked[neighbourId] = true
                        pointer[neighbourId] = JourneyPointer(
                            leg = FoundLeg.Walk(stopId, neighbourId, walkSecs),
                            prev = pointer[stopId],
                            prevStopId = stopId,
                        )
                    }
                }
            }

            roundsCompleted++

            // Collect any destination stops newly reached
            var foundNew = false
            for (destId in destSet) {
                if (bestWithTransit[destId] != INF) {
                    val conn = buildConnection(destId, pointer, query)
                    if (conn != null && accumulator.none { it.arrivalSeconds == conn.arrivalSeconds }) {
                        accumulator.add(conn)
                        foundNew = true
                    }
                }
            }

            if (foundNew) {
                val sorted = accumulator.sortedBy { it.doorToDoorSeconds }
                emit(RoutingResult(connections = sorted, isComplete = false))
            }

            // Budget checks (only after destination is reachable — phase 1 completeness)
            val reachable = destSet.any { bestWithTransit[it] != INF }
            if (reachable) {
                val elapsed = System.currentTimeMillis() - startMs
                val roundMs = System.currentTimeMillis() - roundStart
                if (elapsed >= BUDGET_MS || roundMs >= ROUND_BUDGET_MS || round >= MAX_ROUNDS) break
            }
        }

        if (accumulator.isNotEmpty()) {
            emit(RoutingResult(connections = accumulator.sortedBy { it.doorToDoorSeconds }, isComplete = true))
        }
    }

    private fun earliestTrip(route: GtfsRoute, pos: Int, notBefore: Int): GtfsTrip? =
        route.trips
            .filter { tripDeparture(it, pos) >= notBefore }
            .minByOrNull { tripDeparture(it, pos) }

    // Trip time layout per stop: [dep0, arr1, dep1, arr2, dep2, ..., arrN]
    // dep at pos p = times[p * 2]
    // arr at pos p = times[p * 2 - 1]  (for p > 0)
    private fun tripDeparture(trip: GtfsTrip, pos: Int): Int = trip.times[pos * 2]
    private fun tripArrival(trip: GtfsTrip, pos: Int): Int = trip.times[pos * 2 - 1]

    private fun buildConnection(
        destId: Int,
        pointer: Array<JourneyPointer?>,
        query: RoutingQuery,
    ): FoundConnection? {
        val legs = mutableListOf<FoundLeg>()
        var p = pointer[destId] ?: return null
        while (true) {
            legs.add(0, p.leg)
            val prev = p.prev ?: break
            p = prev
        }
        val transitLegs = legs.filterIsInstance<FoundLeg.Transit>()
        if (transitLegs.isEmpty()) return null
        return FoundConnection(
            legs = legs,
            departureSeconds = transitLegs.first().boardSeconds,
            arrivalSeconds = transitLegs.last().alightSeconds,
            walkToFirstStop = query.walkToFirstStop.seconds,
            walkFromLastStop = query.walkFromLastStop.seconds,
        )
    }
}

private data class JourneyPointer(
    val leg: FoundLeg,
    val prev: JourneyPointer?,
    val prevStopId: Int,
)
