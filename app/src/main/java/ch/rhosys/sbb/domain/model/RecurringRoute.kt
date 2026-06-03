package ch.rhosys.sbb.domain.model

data class RecurringRoute(
    val id: Long,
    val label: String,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val rrule: String,
    val departureHour: Int,
    val departureMinute: Int,
    val notifyBeforeMinutes: Int = 0,
    val isPaused: Boolean = false,
) {
    fun toDestinationEndpoint() =
        SearchEndpoint.NamedPlace(destinationName, destinationLat, destinationLng)
}
