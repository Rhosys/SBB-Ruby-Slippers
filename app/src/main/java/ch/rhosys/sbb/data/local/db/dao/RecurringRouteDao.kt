package ch.rhosys.sbb.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ch.rhosys.sbb.data.local.db.entity.RecurringRouteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringRouteDao {
    @Query("SELECT * FROM recurring_routes ORDER BY label ASC")
    fun getAllRecurringRoutes(): Flow<List<RecurringRouteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: RecurringRouteEntity): Long

    @Update
    suspend fun update(route: RecurringRouteEntity)

    @Query("DELETE FROM recurring_routes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
