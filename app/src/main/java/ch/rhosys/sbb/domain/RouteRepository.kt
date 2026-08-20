package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.RecurringRoute
import ch.rhosys.sbb.domain.model.SavedRoute
import ch.rhosys.sbb.domain.model.TripHistoryItem
import kotlinx.coroutines.flow.Flow

interface RouteRepository {
    fun getSavedRoutes(): Flow<List<SavedRoute>>
    fun getRecurringRoutes(): Flow<List<RecurringRoute>>
    fun getLockedInTripHistory(): Flow<List<TripHistoryItem>>
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
    // Returns the inserted trip_history row id.
    suspend fun recordSearch(
        fromName: String,
        toName: String,
        toLat: Double,
        toLng: Double,
        wasLockedIn: Boolean,
        departureEpoch: Long? = null,
        arrivalEpoch: Long? = null,
    ): Long
    suspend fun markTripCancelled(id: Long)
    suspend fun getRecentSearches(limit: Int = 20): List<TripHistoryItem>
    suspend fun pruneExpiredBrowsedTrips()
}
