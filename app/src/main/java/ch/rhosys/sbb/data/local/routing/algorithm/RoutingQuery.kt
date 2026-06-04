package ch.rhosys.sbb.data.local.routing.algorithm

import java.time.Duration
import java.time.LocalDate

data class RoutingQuery(
    val originStopIds: List<Int>,
    val destinationStopIds: List<Int>,
    val departureAfterSeconds: Int,
    val date: LocalDate,
    val walkToFirstStop: Duration,
    val walkFromLastStop: Duration,
)
