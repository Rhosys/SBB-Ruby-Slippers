package ch.rhosys.sbb.domain.model

import java.time.Duration

data class Connection(
    val departure: Stop,
    val arrival: Stop,
    val legs: List<Leg>,
    val transfers: Int,
    // Walk from user's actual origin to the first boarding stop
    val walkToFirstStop: Duration,
    // Walk from the last alighting stop to the user's actual destination
    val walkFromLastStop: Duration,
    // null until the fares feature is built
    val fare: Fare? = null,
) {
    val lineNames: List<String>
        get() = legs.filterIsInstance<Leg.Transit>().map { it.lineName }

    // Displayed as the middle value on the connection card (node 0 → node N).
    val transitDuration: Duration?
        get() {
            val dep = departure.scheduledTime ?: return null
            val arr = arrival.scheduledTime ?: return null
            return Duration.between(dep, arr)
        }

    // Used only for Pareto optimisation — never displayed.
    // Display uses walkToFirstStop, transitDuration, and walkFromLastStop separately.
    val optimisationDuration: Duration?
        get() = transitDuration?.let { walkToFirstStop + it + walkFromLastStop }
}
