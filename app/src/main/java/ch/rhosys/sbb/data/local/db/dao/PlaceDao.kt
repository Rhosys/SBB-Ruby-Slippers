package ch.rhosys.sbb.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ch.rhosys.sbb.data.local.db.entity.PlaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllPlaces(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE isHome = 1 LIMIT 1")
    fun getHomePlace(): Flow<PlaceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE places SET isHome = 0")
    suspend fun clearHomeFlag()

    @Query("UPDATE places SET isHome = 1 WHERE id = :id")
    suspend fun setHome(id: Long)
}
