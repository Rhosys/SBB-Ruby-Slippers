package ch.rhosys.sbb.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.rhosys.sbb.domain.model.SavedRoute
import java.time.Instant

@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String?,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val scheduledAtMillis: Long?,
    val calendarEventId: Long? = null,
    val isCalendarLinked: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    fun toDomain() = SavedRoute(
        id = id,
        label = label,
        destinationName = destinationName,
        destinationLat = destinationLat,
        destinationLng = destinationLng,
        scheduledAt = scheduledAtMillis?.let { Instant.ofEpochMilli(it) },
        calendarEventId = calendarEventId,
        isCalendarLinked = isCalendarLinked,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
    )
}

fun SavedRoute.toEntity() = SavedRouteEntity(
    id = id,
    label = label,
    destinationName = destinationName,
    destinationLat = destinationLat,
    destinationLng = destinationLng,
    scheduledAtMillis = scheduledAt?.toEpochMilli(),
    calendarEventId = calendarEventId,
    isCalendarLinked = isCalendarLinked,
    createdAtMillis = createdAt.toEpochMilli(),
)
