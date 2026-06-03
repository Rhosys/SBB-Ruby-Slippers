package ch.rhosys.sbb.di

import ch.rhosys.sbb.BuildConfig
import ch.rhosys.sbb.data.remote.ApiTransportRepository
import ch.rhosys.sbb.data.remote.TransportApi
import ch.rhosys.sbb.domain.TransportRepository
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

private const val BASE_URL = "https://transport.opendata.ch/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideTransportApi(retrofit: Retrofit): TransportApi =
        retrofit.create(TransportApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTransportRepository(impl: ApiTransportRepository): TransportRepository
}
