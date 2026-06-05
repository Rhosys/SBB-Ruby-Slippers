package ch.rhosys.sbb.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ch.rhosys.sbb.data.local.db.dao.PlaceDao
import ch.rhosys.sbb.data.local.db.dao.RecurringRouteDao
import ch.rhosys.sbb.data.local.db.dao.SavedRouteDao
import ch.rhosys.sbb.data.local.db.dao.TripHistoryDao
import ch.rhosys.sbb.data.local.db.entity.PlaceEntity
import ch.rhosys.sbb.data.local.db.entity.RecurringRouteEntity
import ch.rhosys.sbb.data.local.db.entity.SavedRouteEntity
import ch.rhosys.sbb.data.local.db.entity.TripHistoryEntity

@Database(
    entities = [
        PlaceEntity::class,
        SavedRouteEntity::class,
        RecurringRouteEntity::class,
        TripHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun recurringRouteDao(): RecurringRouteDao
    abstract fun tripHistoryDao(): TripHistoryDao
}
