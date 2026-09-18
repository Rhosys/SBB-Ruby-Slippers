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

// Default: 6 km/h, matching UserPreferencesRepository's default walking pace.
const val DEFAULT_WALKING_PACE_METERS_PER_SECOND: Double = 6.0 * 1000.0 / 3600.0

data class RoutingQuery(
    val originStopIds: List<Int>,
    val destinationStopIds: List<Int>,
    val date: LocalDate,
    val routingTime: RoutingTime,
    val walkToFirstStop: Duration,
    val walkFromLastStop: Duration,
    // Used to convert a GtfsTransfer's distanceMeters into a walking duration —
    // transfers are stored as distance so this can reflect the user's own pace rather
    // than a fixed, unpersonalised time baked into the GTFS feed.
    val walkingPaceMetersPerSecond: Double = DEFAULT_WALKING_PACE_METERS_PER_SECOND,
) {
    val departureAfterSeconds: Int? get() =
        (routingTime as? RoutingTime.DepartAfter)?.time?.toSecondOfDay()

    val arriveBySeconds: Int? get() =
        (routingTime as? RoutingTime.ArriveBy)?.time?.toSecondOfDay()
}
