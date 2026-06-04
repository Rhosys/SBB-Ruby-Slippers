package ch.rhosys.sbb.data.local.routing.algorithm

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsRoute
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val INF = Int.MAX_VALUE / 2
private const val MAX_ROUNDS = 7
private const val BUDGET_MS = 20_000L
private const val ROUND_BUDGET_MS = 10_000L

class RoutingEngine(
    private val network: GtfsNetwork,
    private val calendar: GtfsCalendarResolver? = null,
) {

    fun route(query: RoutingQuery): Flow<RoutingResult> = when (query.routingTime) {
        is RoutingTime.DepartAfter -> routeForward(query, query.routingTime.time.toSecondOfDay())
        is RoutingTime.ArriveBy   -> routeReverse(query, query.routingTime.time.toSecondOfDay())
    }

    private fun routeForward(query: RoutingQuery, departAfterSec: Int): Flow<RoutingResult> = flow {
        val startMs = System.currentTimeMillis()
        val activeServiceIds = calendar?.activeServiceIds(query.date)

        // best[stopId] = earliest arrival in seconds at that stop across all rounds
        val best = IntArray(network.stops.size) { INF }
        // bestWithTransit[stopId] = earliest arrival reachable by at least one transit leg
        val bestWithTransit = IntArray(network.stops.size) { INF }

        // Journey pointer: how did we reach each stop?
        val pointer = arrayOfNulls<JourneyPointer>(network.stops.size)

        // Seed origins
        for (stopId in query.originStopIds) {
            best[stopId] = departAfterSec
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
                    val trip = earliestTrip(route, pos, best[stopId], activeServiceIds) ?: continue
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

    private fun earliestTrip(route: GtfsRoute, pos: Int, notBefore: Int, activeServiceIds: Set<String>?): GtfsTrip? =
        route.trips
            .filter { activeServiceIds == null || it.serviceId.isEmpty() || it.serviceId in activeServiceIds }
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

    // Reverse scan: find latest departure from origin arriving by arriveBySeconds.
    // Seeds destination stops and scans trips backwards — alight first, board later.
    // best[stopId] = latest time you can depart from stopId and still reach a destination.
    private fun routeReverse(query: RoutingQuery, arriveBySeconds: Int): Flow<RoutingResult> = flow {
        val startMs = System.currentTimeMillis()
        val activeServiceIds = calendar?.activeServiceIds(query.date)

        // NEG_INF sentinel: "not reachable in reverse" (latest departure not yet known)
        val NEG_INF = Int.MIN_VALUE / 2
        val best = IntArray(network.stops.size) { NEG_INF }
        val bestWithTransit = IntArray(network.stops.size) { NEG_INF }
        val pointer = arrayOfNulls<JourneyPointer>(network.stops.size)

        // Seed destinations — they can be "departed" no later than arriveBySeconds
        for (stopId in query.destinationStopIds) {
            best[stopId] = arriveBySeconds
        }

        val originSet = query.originStopIds.toSet()
        val accumulator = mutableListOf<FoundConnection>()

        for (round in 1..MAX_ROUNDS) {
            val roundStart = System.currentTimeMillis()
            val improved = BooleanArray(network.stops.size) { false }

            // Phase 1: for each stop with a known "latest reachable" time,
            // scan routes through it in reverse to find earlier boarding stops.
            for (stopId in network.stops.indices) {
                if (best[stopId] == NEG_INF) continue
                val routes = network.stopToRoutes[stopId] ?: continue
                for ((routeIdx, pos) in routes) {
                    val route = network.routes[routeIdx]
                    // Find latest trip that arrives at stopId no later than best[stopId]
                    val trip = latestTripArrivingBy(route, pos, best[stopId], activeServiceIds) ?: continue
                    // Scan backwards to earlier stops in the route
                    for (p in pos - 1 downTo 0) {
                        val prevStop = route.stopIds[p]
                        val depSec = tripDeparture(trip, p)
                        if (depSec > bestWithTransit[prevStop]) {
                            bestWithTransit[prevStop] = depSec
                            best[prevStop] = depSec
                            improved[prevStop] = true
                            pointer[prevStop] = JourneyPointer(
                                leg = FoundLeg.Transit(
                                    routeName = route.name,
                                    boardStopId = prevStop,
                                    alightStopId = stopId,
                                    boardSeconds = depSec,
                                    alightSeconds = tripArrival(trip, pos),
                                ),
                                prev = pointer[stopId],
                                prevStopId = stopId,
                            )
                        }
                    }
                }
            }

            // Phase 2: reverse walking transfers
            for (stopId in network.stops.indices) {
                if (!improved[stopId]) continue
                val transfers = network.stopToTransfers[stopId] ?: continue
                for ((neighbourId, walkSecs) in transfers) {
                    val depViaWalk = best[stopId] - walkSecs
                    if (depViaWalk > best[neighbourId]) {
                        best[neighbourId] = depViaWalk
                        pointer[neighbourId] = JourneyPointer(
                            leg = FoundLeg.Walk(neighbourId, stopId, walkSecs),
                            prev = pointer[stopId],
                            prevStopId = stopId,
                        )
                    }
                }
            }

            // Collect newly reachable origins
            var foundNew = false
            for (originId in originSet) {
                if (bestWithTransit[originId] != NEG_INF) {
                    val conn = buildReverseConnection(originId, pointer, query)
                    if (conn != null && accumulator.none { it.departureSeconds == conn.departureSeconds }) {
                        accumulator.add(conn)
                        foundNew = true
                    }
                }
            }

            if (foundNew) {
                val sorted = accumulator.sortedByDescending { it.departureSeconds }
                emit(RoutingResult(connections = sorted, isComplete = false))
            }

            val reachable = originSet.any { bestWithTransit[it] != NEG_INF }
            if (reachable) {
                val elapsed = System.currentTimeMillis() - startMs
                val roundMs = System.currentTimeMillis() - roundStart
                if (elapsed >= BUDGET_MS || roundMs >= ROUND_BUDGET_MS || round >= MAX_ROUNDS) break
            }
        }

        if (accumulator.isNotEmpty()) {
            emit(RoutingResult(
                connections = accumulator.sortedByDescending { it.departureSeconds },
                isComplete = true,
            ))
        }
    }

    private fun latestTripArrivingBy(route: GtfsRoute, pos: Int, noLaterThan: Int, activeServiceIds: Set<String>?): GtfsTrip? =
        route.trips
            .filter { activeServiceIds == null || it.serviceId.isEmpty() || it.serviceId in activeServiceIds }
            .filter { tripArrival(it, pos) <= noLaterThan }
            .maxByOrNull { tripArrival(it, pos) }

    private fun buildReverseConnection(
        originId: Int,
        pointer: Array<JourneyPointer?>,
        query: RoutingQuery,
    ): FoundConnection? {
        val legs = mutableListOf<FoundLeg>()
        var p = pointer[originId] ?: return null
        while (true) {
            legs.add(p.leg)
            val next = p.prev ?: break
            p = next
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
