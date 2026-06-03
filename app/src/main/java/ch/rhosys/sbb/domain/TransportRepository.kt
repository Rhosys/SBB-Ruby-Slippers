package ch.rhosys.sbb.domain

import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.data.remote.dto.StationboardResponseDto
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint

interface TransportRepository {
    suspend fun getConnections(from: SearchEndpoint, to: SearchEndpoint): List<Connection>
    suspend fun getStationboard(station: String): StationboardResponseDto
    suspend fun getLocations(query: String): LocationsResponseDto
    suspend fun getLocationsByCoordinate(lat: Double, lng: Double): LocationsResponseDto
}
