package ch.rhosys.sbb.domain.model

import java.time.Instant

data class SavedRoute(
    val id: Long,
    val label: String?,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val scheduledAt: Instant?,
    val calendarEventId: Long? = null,
    val isCalendarLinked: Boolean = false,
    val createdAt: Instant = Instant.now(),
) {
    val isAsap: Boolean get() = scheduledAt == null

    fun toDestinationEndpoint() =
        SearchEndpoint.NamedPlace(destinationName, destinationLat, destinationLng)
}
