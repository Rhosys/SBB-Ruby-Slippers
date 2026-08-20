package ch.rhosys.sbb.domain.model

data class TripHistoryItem(
    val id: Long,
    val fromName: String,
    val toName: String,
    val searchedAtMillis: Long,
    val wasLockedIn: Boolean,
    val wasCancelled: Boolean,
    val departureEpoch: Long?,
)
