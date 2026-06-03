package ch.rhosys.sbb.domain

import ch.rhosys.sbb.data.remote.dto.ConnectionsResponseDto
import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.data.remote.dto.StationboardResponseDto

interface TransportRepository {
    suspend fun getConnections(from: String, to: String): ConnectionsResponseDto
    suspend fun getStationboard(station: String): StationboardResponseDto
    suspend fun getLocations(query: String): LocationsResponseDto
}
