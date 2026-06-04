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

    val transitDuration: Duration
        get() = Duration.between(departure.scheduledTime, arrival.scheduledTime)

    val doorToDoorDuration: Duration
        get() = walkToFirstStop + transitDuration + walkFromLastStop
}
