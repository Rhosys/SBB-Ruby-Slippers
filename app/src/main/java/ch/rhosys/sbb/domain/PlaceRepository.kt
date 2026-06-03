package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.Place
import kotlinx.coroutines.flow.Flow

interface PlaceRepository {
    fun getPlaces(): Flow<List<Place>>
    fun getHomePlace(): Flow<Place?>
    suspend fun upsertPlace(name: String, lat: Double, lng: Double, isHome: Boolean = false, sortOrder: Int = 0): Long
    suspend fun updatePlace(place: Place)
    suspend fun deletePlace(id: Long)
    suspend fun setHome(id: Long)
}
