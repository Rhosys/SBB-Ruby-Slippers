package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.Place
import kotlinx.coroutines.flow.Flow

interface PlaceRepository {
    fun getPlaces(): Flow<List<Place>>
    suspend fun upsertPlace(
        name: String,
        lat: Double,
        lng: Double,
        sortOrder: Int = 0,
        label: String? = null,
        photoUri: String? = null,
        gridX: Int = 0,
        gridY: Int = 0,
        gridWidth: Int = 2,
        gridHeight: Int = 2,
    ): Long
    suspend fun updatePlace(place: Place)
    suspend fun deletePlace(id: Long)
}
