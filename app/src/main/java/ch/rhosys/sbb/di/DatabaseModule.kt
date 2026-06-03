package ch.rhosys.sbb.di

import android.content.Context
import androidx.room.Room
import ch.rhosys.sbb.data.local.db.AppDatabase
import ch.rhosys.sbb.data.local.db.dao.PlaceDao
import ch.rhosys.sbb.data.local.db.dao.RecurringRouteDao
import ch.rhosys.sbb.data.local.db.dao.SavedRouteDao
import ch.rhosys.sbb.data.local.db.dao.TripHistoryDao
import ch.rhosys.sbb.data.local.repository.RoomPlaceRepository
import ch.rhosys.sbb.data.local.repository.RoomRouteRepository
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.RouteRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "sbb_ruby_slippers.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun providePlaceDao(db: AppDatabase): PlaceDao = db.placeDao()
    @Provides fun provideSavedRouteDao(db: AppDatabase): SavedRouteDao = db.savedRouteDao()
    @Provides fun provideRecurringRouteDao(db: AppDatabase): RecurringRouteDao = db.recurringRouteDao()
    @Provides fun provideTripHistoryDao(db: AppDatabase): TripHistoryDao = db.tripHistoryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingsModule {
    @Binds @Singleton abstract fun bindPlaceRepository(impl: RoomPlaceRepository): PlaceRepository
    @Binds @Singleton abstract fun bindRouteRepository(impl: RoomRouteRepository): RouteRepository
}
