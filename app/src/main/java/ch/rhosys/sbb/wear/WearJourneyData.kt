package ch.rhosys.sbb.wear

import kotlinx.serialization.Serializable

// Kept identical in :app and :wear.
@Serializable
data class WearJourneyData(
    val from: String = "",
    val to: String = "",
    val departureTime: String = "",
    val arrivalTime: String = "",
    val isActive: Boolean = false,
    // Timeline for the journey complication — effective (delay-adjusted) times.
    // Boarding the first vehicle:
    val departureEpochSeconds: Long? = null,
    // Arriving at each station where the rider changes vehicle, in order:
    val transfers: List<WearTransfer> = emptyList(),
    // Arriving at the last stop:
    val arrivalEpochSeconds: Long? = null,
)

@Serializable
data class WearTransfer(val stationName: String, val arrivalEpochSeconds: Long? = null)

const val WEAR_JOURNEY_PATH = "/sbb/journey"
const val WEAR_JOURNEY_KEY = "json"
