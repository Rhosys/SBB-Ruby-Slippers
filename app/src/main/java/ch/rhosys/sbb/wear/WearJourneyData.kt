package ch.rhosys.sbb.wear

import kotlinx.serialization.Serializable

@Serializable
data class WearJourneyData(
    val from: String = "",
    val to: String = "",
    val departureTime: String = "",
    val arrivalTime: String = "",
    val isActive: Boolean = false,
)

const val WEAR_JOURNEY_PATH = "/sbb/journey"
const val WEAR_JOURNEY_KEY = "json"
