package ch.rhosys.sbb.data.local.routing.algorithm

import ch.rhosys.sbb.domain.model.JourneyCriteria

data class RoutingResult(
    // Non-dominated connections found so far (see JourneyCriteria), ordered by departure.
    // A later emission may add connections the earlier one didn't have.
    val connections: List<FoundConnection>,
    val isComplete: Boolean,
)

data class FoundConnection(
    val legs: List<FoundLeg>,
    // First transit boarding / last transit alighting, as scheduled (seconds of day).
    val departureSeconds: Int,
    val arrivalSeconds: Int,
    val walkToFirstStop: Long,   // seconds
    val walkFromLastStop: Long,  // seconds
) {
    val doorToDoorSeconds: Long
        get() = walkToFirstStop + (arrivalSeconds - departureSeconds) + walkFromLastStop

    private val transitLegs: List<FoundLeg.Transit> get() = legs.filterIsInstance<FoundLeg.Transit>()

    // Spare time at the tightest transfer: scheduled gap minus any walk between the two
    // legs minus the incoming leg's expected delay. Null when there is no transfer.
    val tightestTransferSlackSeconds: Int?
        get() {
            var tightest: Int? = null
            var lastTransit: FoundLeg.Transit? = null
            var walked = 0
            for (leg in legs) when (leg) {
                is FoundLeg.Walk -> walked += leg.durationSeconds
                is FoundLeg.Transit -> {
                    lastTransit?.let { prev ->
                        val slack = leg.boardSeconds - prev.alightSeconds - walked - prev.expectedDelaySeconds
                        tightest = minOf(tightest ?: slack, slack)
                    }
                    lastTransit = leg
                    walked = 0
                }
            }
            return tightest
        }

    // Same rule the UI applies to domain Connections — seconds of day stand in for epoch
    // seconds since every connection here is on the same service day.
    val criteria: JourneyCriteria
        get() {
            val transit = transitLegs
            val leadingWalk = legs.takeWhile { it is FoundLeg.Walk }.sumOf { (it as FoundLeg.Walk).durationSeconds }
            val trailingWalk = legs.takeLastWhile { it is FoundLeg.Walk }.sumOf { (it as FoundLeg.Walk).durationSeconds }
            return JourneyCriteria(
                departureEpochSeconds = departureSeconds.toLong() - leadingWalk - walkToFirstStop,
                arrivalEpochSeconds = arrivalSeconds.toLong() + transit.last().expectedDelaySeconds +
                    trailingWalk + walkFromLastStop,
                transfers = maxOf(0, transit.size - 1),
                transferSlackSeconds = JourneyCriteria.cappedSlack(tightestTransferSlackSeconds?.toLong()),
            )
        }
}

sealed class FoundLeg {
    data class Transit(
        val routeName: String,
        val routeIdx: Int,
        val boardStopId: Int,
        val boardPos: Int,
        val alightStopId: Int,
        val boardSeconds: Int,
        val alightSeconds: Int,
        // From the engine's ExpectedDelayProvider — how late we plan for this leg to arrive.
        val expectedDelaySeconds: Int = 0,
    ) : FoundLeg()

    data class Walk(
        val fromStopId: Int,
        val toStopId: Int,
        val durationSeconds: Int,
    ) : FoundLeg()
}
