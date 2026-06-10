package ch.rhosys.sbb.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.rhosys.sbb.data.local.db.entity.TripHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripHistoryDao {
    @Query("SELECT * FROM trip_history ORDER BY searchedAtMillis DESC LIMIT 50")
    fun getRecentHistory(): Flow<List<TripHistoryEntity>>

    @Query("SELECT * FROM trip_history WHERE wasLockedIn = 1 ORDER BY departureEpoch DESC, searchedAtMillis DESC")
    fun getLockedInHistory(): Flow<List<TripHistoryEntity>>

    @Query("SELECT * FROM trip_history ORDER BY searchedAtMillis DESC LIMIT :limit")
    suspend fun getRecentHistoryOnce(limit: Int = 20): List<TripHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TripHistoryEntity)

    @Query("DELETE FROM trip_history WHERE id NOT IN (SELECT id FROM trip_history ORDER BY searchedAtMillis DESC LIMIT 200)")
    suspend fun pruneOldEntries()

    @Query("DELETE FROM trip_history WHERE wasLockedIn = 0 AND arrivalEpoch IS NOT NULL AND arrivalEpoch < :nowEpoch")
    suspend fun pruneExpiredBrowsed(nowEpoch: Long)
}
