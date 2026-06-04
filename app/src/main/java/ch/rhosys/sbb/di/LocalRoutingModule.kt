package ch.rhosys.sbb.di

import android.content.Context
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalRoutingModule {
    @Provides
    @Singleton
    fun provideGtfsNetworkStore(@ApplicationContext context: Context): GtfsNetworkStore =
        GtfsNetworkStore(File(context.filesDir, "gtfs"))
}
