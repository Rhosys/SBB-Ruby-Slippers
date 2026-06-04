package ch.rhosys.sbb.data.local.routing.rt

data class RtStopDelay(
    val stopId: String?,
    val stopSeq: Int?,
    val arrivalDelaySec: Int?,
    val departureDelaySec: Int?,
    val arrivalAbsoluteEpoch: Long?,
    val departureAbsoluteEpoch: Long?,
)

data class RtTripUpdate(
    val tripId: String,
    val routeId: String,
    val startDate: String,
    val stopDelays: List<RtStopDelay>,
)

data class RtAlert(
    val id: String,
    val headerText: String,
    val descriptionText: String,
    val informedTripIds: List<String>,
    val informedRouteIds: List<String>,
    val informedStopIds: List<String>,
    val cause: Int = 0,
    val effect: Int = 0,
)
