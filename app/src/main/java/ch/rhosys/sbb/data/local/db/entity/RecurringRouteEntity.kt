package ch.rhosys.sbb.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.rhosys.sbb.domain.model.RecurringRoute

@Entity(tableName = "recurring_routes")
data class RecurringRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val rrule: String,
    val departureHour: Int,
    val departureMinute: Int,
    val notifyBeforeMinutes: Int = 0,
    val isPaused: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toDomain() = RecurringRoute(
        id = id,
        label = label,
        destinationName = destinationName,
        destinationLat = destinationLat,
        destinationLng = destinationLng,
        rrule = rrule,
        departureHour = departureHour,
        departureMinute = departureMinute,
        notifyBeforeMinutes = notifyBeforeMinutes,
        isPaused = isPaused,
    )
}

fun RecurringRoute.toEntity() = RecurringRouteEntity(
    id = id,
    label = label,
    destinationName = destinationName,
    destinationLat = destinationLat,
    destinationLng = destinationLng,
    rrule = rrule,
    departureHour = departureHour,
    departureMinute = departureMinute,
    notifyBeforeMinutes = notifyBeforeMinutes,
    isPaused = isPaused,
)
