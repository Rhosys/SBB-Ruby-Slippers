package ch.rhosys.sbb.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ch.rhosys.sbb.data.local.db.entity.SavedRouteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {
    @Query("SELECT * FROM saved_routes ORDER BY scheduledAtMillis ASC NULLS LAST, createdAtMillis DESC")
    fun getAllSavedRoutes(): Flow<List<SavedRouteEntity>>

    @Query("SELECT * FROM saved_routes WHERE scheduledAtMillis BETWEEN :fromMillis AND :toMillis")
    fun getRoutesInWindow(fromMillis: Long, toMillis: Long): Flow<List<SavedRouteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: SavedRouteEntity): Long

    @Update
    suspend fun update(route: SavedRouteEntity)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_routes WHERE calendarEventId = :eventId LIMIT 1")
    suspend fun getByCalendarEventId(eventId: Long): SavedRouteEntity?

    @Query("DELETE FROM saved_routes WHERE isCalendarLinked = 1 AND calendarEventId NOT IN (:activeEventIds)")
    suspend fun deleteStaleCalendarRoutes(activeEventIds: List<Long>)
}
