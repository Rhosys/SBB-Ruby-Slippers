package ch.rhosys.sbb.domain.model

import java.time.Instant

data class Departure(
    val lineName: String,
    val lineCategory: String,
    val direction: String,
    val scheduledDeparture: Instant?,
    val delayMinutes: Int,
    val platform: String?,
    val operator: String?,
)
