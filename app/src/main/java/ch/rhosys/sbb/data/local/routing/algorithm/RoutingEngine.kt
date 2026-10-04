package ch.rhosys.sbb.data.local.routing.algorithm

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsRoute
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsTrip
import ch.rhosys.sbb.domain.model.COMFORTABLE_TRANSFER_SLACK_SECONDS
import ch.rhosys.sbb.domain.model.paretoOptimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt

private const val INF = Int.MAX_VALUE / 2
private const val NEG_INF = Int.MIN_VALUE / 2
// Max transit legs per journey, i.e. at most MAX_ROUNDS - 1 changes.
private const val MAX_ROUNDS = 7
private const val BUDGET_MS = 20_000L
// Changing vehicles at the same stop (platform to platform) never takes less than this.
internal const val MIN_SAME_STOP_TRANSFER_SECONDS = 60
// ArriveBy: extra windows to look back when the first one (anchored on the reverse
// scan's latest departure, which can be optimistic) holds no journey that makes it.
private const val ARRIVE_BY_EXTRA_WINDOWS = 2

// Range RAPTOR: returns every journey in the query's departure window that no other
// journey beats on all of JourneyCriteria (leave later, arrive earlier, fewer changes,
// more transfer slack) — not just the single fastest one.
//
// How the set is built:
//  1. Departure times: every time a trip leaves an origin stop (or a stop one walk away,
//     minus the walk) inside the window.
//  2. rRAPTOR: those times are processed latest first, and arrival labels are kept between
//     them, so a journey is only recorded when leaving at that time gets you somewhere
//     strictly earlier than leaving later does. Labels are per round (= transit legs), so
//     a journey with more changes is only kept when it actually arrives earlier.
//  3. Two passes: a tight one (a same-stop change needs MIN_SAME_STOP_TRANSFER_SECONDS,
//     a walked change just the walk) and a comfortable one where every change must leave
//     COMFORTABLE_TRANSFER_SLACK_SECONDS spare. The second finds the "one train earlier,
//     but a relaxed change" alternatives the first pass prunes.
//  4. The union is filtered with paretoOptimal — the same rule the UI applies.
//
// Every transfer is planned against the incoming leg's *expected* arrival (scheduled +
// ExpectedDelayProvider), so a known delay can make a change infeasible or tight.
class RoutingEngine(
    private val network: GtfsNetwork,
    private val calendar: GtfsCalendarResolver? = null,
    private val expectedDelays: ExpectedDelayProvider = ExpectedDelayProvider.NONE,
) {

    // One step of a journey, linked backwards. Immutable, so a better label replacing an
    // older one at some stop never rewrites a journey already built through the old one.
    private class Label(
        val leg: FoundLeg?,       // null only for the origin itself
        val prev: Label?,
        // Reached after at least one transit leg — boarding from here is a transfer.
        val afterTransit: Boolean,
    )

    private val originLabel = Label(leg = null, prev = null, afterTransit = false)

    fun route(query: RoutingQuery): Flow<RoutingResult> = flow {
        val deadlineMs = System.currentTimeMillis() + BUDGET_MS
        val active = calendar?.activeServiceIds(query.date)
        val windowSec = query.window.seconds.toInt()

        val arrivalCap: Int
        var windowStart: Int
        val windowEnd: Int
        var widenings = 0
        when (val rt = query.routingTime) {
            is RoutingTime.DepartAfter -> {
                val requested = rt.time.toSecondOfDay()
                // Anchor the window on the first real departure, so a quiet period right
                // after the requested time still returns the next connections.
                val first = departureCandidates(query, active, requested, INF).minOrNull() ?: return@flow
                windowStart = requested
                windowEnd = first + windowSec
                arrivalCap = INF
            }
            is RoutingTime.DepartBetween -> {
                windowStart = rt.from.toSecondOfDay()
                windowEnd = rt.to.toSecondOfDay()
                arrivalCap = INF
            }
            is RoutingTime.ArriveBy -> {
                val deadline = rt.time.toSecondOfDay()
                val latest = latestDepartureArrivingBy(query, deadline, active) ?: return@flow
                windowStart = latest - windowSec
                windowEnd = deadline
                arrivalCap = deadline
                widenings = ARRIVE_BY_EXTRA_WINDOWS
            }
        }

        val passes = listOf(0, COMFORTABLE_TRANSFER_SLACK_SECONDS.toInt())
        val found = mutableListOf<FoundConnection>()
        for ((i, requiredSlack) in passes.withIndex()) {
            while (true) {
                val candidates = departureCandidates(query, active, windowStart, windowEnd)
                found += rangeSearch(query, active, candidates, requiredSlack, deadlineMs)
                    .filter { it.criteria.arrivalEpochSeconds - it.walkFromLastStop <= arrivalCap }
                if (i > 0 || found.isNotEmpty() || widenings-- <= 0) break
                windowStart -= windowSec
            }
            val shown = found
                .distinctBy { it.legs }
                .paretoOptimal { it.criteria }
                .sortedWith(compareBy({ it.departureSeconds }, { it.arrivalSeconds }))
            if (shown.isNotEmpty()) emit(RoutingResult(connections = shown, isComplete = i == passes.lastIndex))
        }
    }

    // ---- Forward range search -------------------------------------------------

    private fun rangeSearch(
        query: RoutingQuery,
        active: Set<String>?,
        departureTimes: Collection<Int>,
        requiredSlack: Int,
        deadlineMs: Long,
    ): List<FoundConnection> {
        val n = network.stops.size
        // tau[k][s]: earliest expected arrival at s using at most k transit legs' worth of
        // labels — kept across departure times (the rRAPTOR trick). A new label only counts
        // if it beats every label with k or fewer legs: one with *more* legs doesn't
        // dominate it (fewer changes is a criterion), so a slower direct train survives a
        // faster journey with a change.
        val tau = Array(MAX_ROUNDS + 1) { IntArray(n) { INF } }
        val labels = Array(MAX_ROUNDS + 1) { arrayOfNulls<Label>(n) }
        val origins = query.originStopIds.distinct()
        val dests = query.destinationStopIds.distinct()
        val isDest = BooleanArray(n).also { a -> dests.forEach { a[it] = true } }
        // destByRound[k]: earliest arrival at any destination with exactly-k-leg labels.
        val destByRound = IntArray(MAX_ROUNDS + 1) { INF }
        val out = mutableListOf<FoundConnection>()

        val marked = BooleanArray(n)
        val markedList = ArrayList<Int>()
        fun mark(s: Int) { if (!marked[s]) { marked[s] = true; markedList += s } }
        fun clearMarks() { markedList.forEach { marked[it] = false }; markedList.clear() }
        fun bestUpTo(k: Int, s: Int): Int { var m = INF; for (j in 0..k) m = minOf(m, tau[j][s]); return m }
        fun destBoundUpTo(k: Int): Int { var m = INF; for (j in 0..k) m = minOf(m, destByRound[j]); return m }
        fun improve(k: Int, s: Int, time: Int, label: Label): Boolean {
            // Local pruning, then target pruning: nothing reached later than a destination
            // already is (with as few legs) can lead to a better journey.
            if (time >= bestUpTo(k, s) || time >= destBoundUpTo(k)) return false
            tau[k][s] = time
            labels[k][s] = label
            mark(s)
            if (isDest[s]) destByRound[k] = minOf(destByRound[k], time)
            return true
        }

        for (t in departureTimes.sortedDescending()) {
            if (System.currentTimeMillis() > deadlineMs) break
            clearMarks()

            // Round 0: at the origin at t, or one walk away from it.
            for (o in origins) {
                tau[0][o] = t
                labels[0][o] = originLabel
                mark(o)
            }
            for (o in origins) {
                for ((nb, meters) in network.stopToTransfers[o].orEmpty()) {
                    val walk = walkSeconds(query, meters)
                    improve(0, nb, t + walk, Label(FoundLeg.Walk(o, nb, walk), originLabel, afterTransit = false))
                }
            }

            for (k in 1..MAX_ROUNDS) {
                if (markedList.isEmpty()) break

                // Each route through a stop improved last round, scanned from the
                // earliest such stop along it.
                val queue = HashMap<Int, Int>()
                for (s in markedList) {
                    for ((r, p) in network.stopToRoutes[s].orEmpty()) {
                        val current = queue[r]
                        if (current == null || p < current) queue[r] = p
                    }
                }
                clearMarks()

                val prevTau = tau[k - 1]
                val prevLabels = labels[k - 1]
                for ((r, firstPos) in queue) {
                    val route = network.routes[r]
                    var trip: GtfsTrip? = null
                    var boardPos = -1
                    var boardLabel: Label? = null
                    for (p in firstPos until route.stopIds.size) {
                        val s = route.stopIds[p]
                        if (trip != null) {
                            val delay = expectedDelays.arrivalDelaySeconds(r, trip, p)
                            val leg = FoundLeg.Transit(
                                routeName = route.name,
                                routeIdx = r,
                                boardStopId = route.stopIds[boardPos],
                                boardPos = boardPos,
                                alightStopId = s,
                                boardSeconds = tripDeparture(trip, boardPos),
                                alightSeconds = tripArrival(trip, p),
                                expectedDelaySeconds = delay,
                            )
                            improve(k, s, leg.alightSeconds + delay, Label(leg, boardLabel, afterTransit = true))
                        }
                        // Can we be here in time for an earlier trip of this route?
                        val arrivedLabel = prevLabels[s]
                        if (p < route.stopIds.size - 1 && prevTau[s] < INF && arrivedLabel != null) {
                            val ready = prevTau[s] + waitBeforeBoarding(arrivedLabel, requiredSlack)
                            if (trip == null || ready <= tripDeparture(trip, p)) {
                                val candidate = earliestTrip(route, p, ready, active)
                                if (candidate != null && (trip == null || tripDeparture(candidate, p) < tripDeparture(trip, p))) {
                                    trip = candidate
                                    boardPos = p
                                    boardLabel = arrivedLabel
                                }
                            }
                        }
                    }
                }

                // Walking transfers out of every stop a vehicle just improved.
                for (s in markedList.toList()) {
                    val from = labels[k][s] ?: continue
                    for ((nb, meters) in network.stopToTransfers[s].orEmpty()) {
                        val walk = walkSeconds(query, meters)
                        improve(k, nb, tau[k][s] + walk, Label(FoundLeg.Walk(s, nb, walk), from, afterTransit = true))
                    }
                }

                for (d in dests) {
                    if (marked[d]) labels[k][d]?.let { buildConnection(it, query) }?.let(out::add)
                }
            }
        }
        return out
    }

    // Time needed between being at a stop and a departure from it: nothing at the start
    // of the journey, otherwise the transfer's required slack — never less than a
    // same-stop change takes (a walked change already spent its time walking).
    private fun waitBeforeBoarding(arrived: Label, requiredSlack: Int): Int = when {
        !arrived.afterTransit -> 0
        arrived.leg is FoundLeg.Transit -> maxOf(MIN_SAME_STOP_TRANSFER_SECONDS, requiredSlack)
        else -> requiredSlack
    }

    private fun buildConnection(last: Label, query: RoutingQuery): FoundConnection? {
        val legs = ArrayList<FoundLeg>()
        var label: Label? = last
        while (label != null) {
            label.leg?.let { legs.add(it) }
            label = label.prev
        }
        legs.reverse()
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

    // Door departure times (seconds of day) in [from, to] worth starting a search at: each
    // departure from an origin stop, and from a stop one walk away minus that walk.
    private fun departureCandidates(query: RoutingQuery, active: Set<String>?, from: Int, to: Int): Set<Int> {
        val result = HashSet<Int>()
        fun addDeparturesAt(stop: Int, walk: Int) {
            for ((r, p) in network.stopToRoutes[stop].orEmpty()) {
                val route = network.routes[r]
                if (p >= route.stopIds.size - 1) continue
                for (trip in route.trips) {
                    if (!isActive(trip, active)) continue
                    val t = tripDeparture(trip, p) - walk
                    if (t in from..to) result += t
                }
            }
        }
        for (o in query.originStopIds.distinct()) {
            addDeparturesAt(o, 0)
            for ((nb, meters) in network.stopToTransfers[o].orEmpty()) addDeparturesAt(nb, walkSeconds(query, meters))
        }
        return result
    }

    // ---- Reverse scan (ArriveBy anchor) ----------------------------------------

    // Latest door departure from an origin that still arrives by [arriveBy], or null when
    // none does. Only anchors the ArriveBy window — the journeys themselves come from the
    // forward range search, which also applies transfer slack exactly.
    private fun latestDepartureArrivingBy(query: RoutingQuery, arriveBy: Int, active: Set<String>?): Int? {
        val n = network.stops.size
        // latest[s]: latest time you can be at s and still make it.
        val latest = IntArray(n) { NEG_INF }
        // latest[s] relies on at least one transit leg (not only walking to a destination).
        val viaTransit = BooleanArray(n)
        // latest[s] is a boarding time at s — reaching s off another vehicle needs a change.
        val boardsHere = BooleanArray(n)

        var marked = HashSet<Int>()
        for (d in query.destinationStopIds) { latest[d] = arriveBy; marked += d }

        fun walkInto(stops: Collection<Int>): Set<Int> {
            val improved = HashSet<Int>()
            for (s in stops) {
                for ((nb, meters) in network.stopToTransfers[s].orEmpty()) {
                    val at = latest[s] - walkSeconds(query, meters)
                    if (at > latest[nb]) {
                        latest[nb] = at
                        viaTransit[nb] = viaTransit[s]
                        boardsHere[nb] = false
                        improved += nb
                    }
                }
            }
            return improved
        }
        marked.addAll(walkInto(marked.toList()))

        for (round in 1..MAX_ROUNDS) {
            if (marked.isEmpty()) break
            val queue = HashMap<Int, Int>()
            for (s in marked) {
                for ((r, p) in network.stopToRoutes[s].orEmpty()) {
                    val current = queue[r]
                    if (current == null || p > current) queue[r] = p
                }
            }
            val improved = HashSet<Int>()
            for ((r, lastPos) in queue) {
                val route = network.routes[r]
                var trip: GtfsTrip? = null
                for (p in lastPos downTo 0) {
                    val s = route.stopIds[p]
                    if (trip != null) {
                        val dep = tripDeparture(trip, p)
                        if (dep > latest[s]) {
                            latest[s] = dep
                            viaTransit[s] = true
                            boardsHere[s] = true
                            improved += s
                        }
                    }
                    if (p > 0 && latest[s] > NEG_INF) {
                        val alightBy = latest[s] - if (boardsHere[s]) MIN_SAME_STOP_TRANSFER_SECONDS else 0
                        val candidate = latestTripArrivingBy(r, route, p, alightBy, active)
                        if (candidate != null && (trip == null || tripArrival(candidate, p) > tripArrival(trip, p))) {
                            trip = candidate
                        }
                    }
                }
            }
            marked = HashSet(improved)
            marked.addAll(walkInto(improved))
        }

        return query.originStopIds.filter { viaTransit[it] }.maxOfOrNull { latest[it] }
    }

    // ---- Trip lookups -------------------------------------------------------------

    private fun isActive(trip: GtfsTrip, active: Set<String>?): Boolean =
        active == null || trip.serviceId.isEmpty() || trip.serviceId in active

    private fun earliestTrip(route: GtfsRoute, pos: Int, notBefore: Int, active: Set<String>?): GtfsTrip? {
        var found: GtfsTrip? = null
        var foundDep = INF
        for (trip in route.trips) {
            if (!isActive(trip, active)) continue
            val dep = tripDeparture(trip, pos)
            if (dep in notBefore until foundDep) { found = trip; foundDep = dep }
        }
        return found
    }

    // Latest trip whose *expected* arrival at [pos] is no later than [noLaterThan].
    private fun latestTripArrivingBy(routeIdx: Int, route: GtfsRoute, pos: Int, noLaterThan: Int, active: Set<String>?): GtfsTrip? {
        var found: GtfsTrip? = null
        var foundArr = NEG_INF
        for (trip in route.trips) {
            if (!isActive(trip, active)) continue
            val arr = tripArrival(trip, pos)
            if (arr > foundArr && arr + expectedDelays.arrivalDelaySeconds(routeIdx, trip, pos) <= noLaterThan) {
                found = trip; foundArr = arr
            }
        }
        return found
    }

    private fun walkSeconds(query: RoutingQuery, meters: Double): Int =
        (meters / query.walkingPaceMetersPerSecond).roundToInt()

    // Trip time layout per stop: [dep0, arr1, dep1, arr2, dep2, ..., arrN]
    // dep at pos p = times[p * 2]
    // arr at pos p = times[p * 2 - 1]  (for p > 0)
    private fun tripDeparture(trip: GtfsTrip, pos: Int): Int = trip.times[pos * 2]
    private fun tripArrival(trip: GtfsTrip, pos: Int): Int = trip.times[pos * 2 - 1]
}
