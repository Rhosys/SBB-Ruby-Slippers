package ch.rhosys.sbb.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.rhosys.sbb.domain.model.Place

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val isHome: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toDomain() = Place(id, name, lat, lng, isHome, sortOrder)
}

fun Place.toEntity() = PlaceEntity(id, name, lat, lng, isHome, sortOrder)
