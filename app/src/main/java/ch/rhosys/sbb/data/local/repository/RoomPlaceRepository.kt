package ch.rhosys.sbb.data.local.repository

import ch.rhosys.sbb.data.local.db.dao.PlaceDao
import ch.rhosys.sbb.data.local.db.entity.PlaceEntity
import ch.rhosys.sbb.data.local.db.entity.toEntity
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.model.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomPlaceRepository @Inject constructor(
    private val dao: PlaceDao,
) : PlaceRepository {

    override fun getPlaces(): Flow<List<Place>> =
        dao.getAllPlaces().map { list -> list.map { it.toDomain() } }

    override fun getHomePlace(): Flow<Place?> =
        dao.getHomePlace().map { it?.toDomain() }

    override suspend fun upsertPlace(
        name: String,
        lat: Double,
        lng: Double,
        isHome: Boolean,
        sortOrder: Int,
    ): Long = dao.insert(PlaceEntity(name = name, lat = lat, lng = lng, isHome = isHome, sortOrder = sortOrder))

    override suspend fun updatePlace(place: Place) = dao.update(place.toEntity())

    override suspend fun deletePlace(id: Long) = dao.deleteById(id)

    override suspend fun setHome(id: Long) {
        dao.clearHomeFlag()
        dao.setHome(id)
    }
}
