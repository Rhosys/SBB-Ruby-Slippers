package ch.rhosys.sbb.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trip_history")
data class TripHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromName: String,
    val toName: String,
    val toLat: Double,
    val toLng: Double,
    val searchedAtMillis: Long = System.currentTimeMillis(),
    val wasLockedIn: Boolean = false,
    val departureEpoch: Long? = null,
    val arrivalEpoch: Long? = null,
)
