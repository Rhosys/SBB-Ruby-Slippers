package ch.rhosys.sbb.data.remote

import ch.rhosys.sbb.data.remote.dto.ConnectionsResponseDto
import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.data.remote.dto.StationboardResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

interface TransportApi {

    @GET("v1/connections")
    suspend fun getConnections(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("date") date: String? = null,
        @Query("time") time: String? = null,
        @Query("isArrivalTime") isArrivalTime: Int? = null,
        // More per request means fewer requests per page — route queries are rate-limited
        // (~3/min per IP), results are not.
        @Query("limit") limit: Int = 6,
    ): ConnectionsResponseDto

    @GET("v1/stationboard")
    suspend fun getStationboard(
        @Query("station") station: String,
        @Query("limit") limit: Int = 10,
    ): StationboardResponseDto

    @GET("v1/locations")
    suspend fun getLocations(
        @Query("query") query: String,
    ): LocationsResponseDto

    @GET("v1/locations")
    suspend fun getLocationsByCoordinate(
        @Query("x") longitude: Double,
        @Query("y") latitude: Double,
    ): LocationsResponseDto
}
