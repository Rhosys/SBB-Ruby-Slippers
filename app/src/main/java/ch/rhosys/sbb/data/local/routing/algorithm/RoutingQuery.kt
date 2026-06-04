package ch.rhosys.sbb.data.local.routing.algorithm

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

sealed class RoutingTime {
    // Find earliest arrival at destination departing no earlier than this time
    data class DepartAfter(val time: LocalTime) : RoutingTime()
    // Find latest departure from origin arriving no later than this time (reverse RAPTOR)
    data class ArriveBy(val time: LocalTime) : RoutingTime()
}

data class RoutingQuery(
    val originStopIds: List<Int>,
    val destinationStopIds: List<Int>,
    val date: LocalDate,
    val routingTime: RoutingTime,
    val walkToFirstStop: Duration,
    val walkFromLastStop: Duration,
) {
    val departureAfterSeconds: Int? get() =
        (routingTime as? RoutingTime.DepartAfter)?.time?.toSecondOfDay()

    val arriveBySeconds: Int? get() =
        (routingTime as? RoutingTime.ArriveBy)?.time?.toSecondOfDay()
}
