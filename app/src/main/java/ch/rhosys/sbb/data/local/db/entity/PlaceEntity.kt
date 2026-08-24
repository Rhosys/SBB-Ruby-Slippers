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
    val sortOrder: Int = 0,
    val label: String? = null,
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val gridX: Int = 0,
    val gridY: Int = 0,
    val gridWidth: Int = 2,
    val gridHeight: Int = 2,
) {
    fun toDomain() = Place(id, name, lat, lng, sortOrder, label, photoUri, gridX, gridY, gridWidth, gridHeight)
}

fun Place.toEntity() = PlaceEntity(
    id = id,
    name = name,
    lat = lat,
    lng = lng,
    sortOrder = sortOrder,
    label = label,
    photoUri = photoUri,
    gridX = gridX,
    gridY = gridY,
    gridWidth = gridWidth,
    gridHeight = gridHeight,
)
