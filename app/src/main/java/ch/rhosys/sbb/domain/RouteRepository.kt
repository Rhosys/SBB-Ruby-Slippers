package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.RecurringRoute
import ch.rhosys.sbb.domain.model.SavedRoute
import kotlinx.coroutines.flow.Flow

interface RouteRepository {
    fun getSavedRoutes(): Flow<List<SavedRoute>>
    fun getRecurringRoutes(): Flow<List<RecurringRoute>>
    suspend fun insertSavedRoute(route: SavedRoute): Long
    suspend fun updateSavedRoute(route: SavedRoute)
    suspend fun deleteSavedRoute(id: Long)
    suspend fun insertRecurringRoute(route: RecurringRoute): Long
    suspend fun updateRecurringRoute(route: RecurringRoute)
    suspend fun deleteRecurringRoute(id: Long)
    suspend fun upsertCalendarRoute(
        calendarEventId: Long,
        destinationName: String,
        destinationLat: Double,
        destinationLng: Double,
        scheduledAtMillis: Long,
        label: String?,
    )
    suspend fun pruneStaleCalendarRoutes(activeEventIds: Set<Long>)
    suspend fun recordSearch(fromName: String, toName: String, toLat: Double, toLng: Double, wasLockedIn: Boolean)
}
