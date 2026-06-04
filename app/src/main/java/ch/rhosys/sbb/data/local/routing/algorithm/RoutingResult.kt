package ch.rhosys.sbb.data.local.routing.algorithm

data class RoutingResult(
    // Pareto-optimal connections found so far, ordered by arrival time.
    // The list grows as more rounds complete — earlier emissions may have fewer entries.
    val connections: List<FoundConnection>,
    val isComplete: Boolean,
)

data class FoundConnection(
    val legs: List<FoundLeg>,
    val departureSeconds: Int,
    val arrivalSeconds: Int,
    val walkToFirstStop: Long,   // seconds
    val walkFromLastStop: Long,  // seconds
) {
    val doorToDoorSeconds: Long
        get() = walkToFirstStop + (arrivalSeconds - departureSeconds) + walkFromLastStop
}

sealed class FoundLeg {
    data class Transit(
        val routeName: String,
        val boardStopId: Int,
        val alightStopId: Int,
        val boardSeconds: Int,
        val alightSeconds: Int,
    ) : FoundLeg()

    data class Walk(
        val fromStopId: Int,
        val toStopId: Int,
        val durationSeconds: Int,
    ) : FoundLeg()
}
