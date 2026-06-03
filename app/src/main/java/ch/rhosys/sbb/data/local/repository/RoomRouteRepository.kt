package ch.rhosys.sbb.data.local.repository

import ch.rhosys.sbb.data.local.db.dao.RecurringRouteDao
import ch.rhosys.sbb.data.local.db.dao.SavedRouteDao
import ch.rhosys.sbb.data.local.db.entity.SavedRouteEntity
import ch.rhosys.sbb.data.local.db.entity.toEntity
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.model.RecurringRoute
import ch.rhosys.sbb.domain.model.SavedRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomRouteRepository @Inject constructor(
    private val savedRouteDao: SavedRouteDao,
    private val recurringRouteDao: RecurringRouteDao,
) : RouteRepository {

    override fun getSavedRoutes(): Flow<List<SavedRoute>> =
        savedRouteDao.getAllSavedRoutes().map { list -> list.map { it.toDomain() } }

    override fun getRecurringRoutes(): Flow<List<RecurringRoute>> =
        recurringRouteDao.getAllRecurringRoutes().map { list -> list.map { it.toDomain() } }

    override suspend fun insertSavedRoute(route: SavedRoute): Long =
        savedRouteDao.insert(route.toEntity())

    override suspend fun updateSavedRoute(route: SavedRoute) =
        savedRouteDao.update(route.toEntity())

    override suspend fun deleteSavedRoute(id: Long) =
        savedRouteDao.deleteById(id)

    override suspend fun insertRecurringRoute(route: RecurringRoute): Long =
        recurringRouteDao.insert(route.toEntity())

    override suspend fun updateRecurringRoute(route: RecurringRoute) =
        recurringRouteDao.update(route.toEntity())

    override suspend fun deleteRecurringRoute(id: Long) =
        recurringRouteDao.deleteById(id)

    override suspend fun upsertCalendarRoute(
        calendarEventId: Long,
        destinationName: String,
        destinationLat: Double,
        destinationLng: Double,
        scheduledAtMillis: Long,
        label: String?,
    ) {
        val existing = savedRouteDao.getByCalendarEventId(calendarEventId)
        val entity = SavedRouteEntity(
            id = existing?.id ?: 0,
            label = label,
            destinationName = destinationName,
            destinationLat = destinationLat,
            destinationLng = destinationLng,
            scheduledAtMillis = scheduledAtMillis,
            calendarEventId = calendarEventId,
            isCalendarLinked = true,
            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
        )
        savedRouteDao.insert(entity)
    }

    override suspend fun pruneStaleCalendarRoutes(activeEventIds: Set<Long>) {
        if (activeEventIds.isEmpty()) return
        savedRouteDao.deleteStaleCalendarRoutes(activeEventIds.toList())
    }
}
