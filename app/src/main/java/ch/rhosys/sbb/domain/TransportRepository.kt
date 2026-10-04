package ch.rhosys.sbb.domain

import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Departure
import ch.rhosys.sbb.domain.model.SearchEndpoint
import java.time.LocalDate
import java.time.LocalTime

const val DEFAULT_CONNECTION_LIMIT = 6

interface TransportRepository {
    // date/time default to "now"; isArrivalTime false means "depart after", true means
    // "arrive by" — mirrors transport.opendata.ch's own date/time/isArrivalTime params.
    suspend fun getConnections(
        from: SearchEndpoint,
        to: SearchEndpoint,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now(),
        isArrivalTime: Boolean = false,
        // How many connections to return (transport.opendata.ch allows 1–16).
        limit: Int = DEFAULT_CONNECTION_LIMIT,
    ): List<Connection>
    suspend fun getStationboard(station: String): List<Departure>
    suspend fun getLocations(query: String): LocationsResponseDto
    suspend fun getLocationsByCoordinate(lat: Double, lng: Double): LocationsResponseDto
}
