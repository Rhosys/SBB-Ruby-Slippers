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

    val transitDuration: Duration?
        get() {
            val dep = departure.scheduledTime ?: return null
            val arr = arrival.scheduledTime ?: return null
            return Duration.between(dep, arr)
        }

    // What the user sees on the card and what we optimise on — identical.
    val doorToDoorDuration: Duration?
        get() = transitDuration?.let { walkToFirstStop + it + walkFromLastStop }
}
