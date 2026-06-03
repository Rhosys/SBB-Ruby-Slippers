package ch.rhosys.sbb.data.remote

import ch.rhosys.sbb.data.remote.dto.ConnectionsResponseDto
import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.data.remote.dto.StationboardResponseDto
import ch.rhosys.sbb.domain.TransportRepository
import javax.inject.Inject

class TransportRepositoryImpl @Inject constructor(
    private val api: TransportApi,
) : TransportRepository {

    override suspend fun getConnections(from: String, to: String): ConnectionsResponseDto =
        api.getConnections(from = from, to = to)

    override suspend fun getStationboard(station: String): StationboardResponseDto =
        api.getStationboard(station = station)

    override suspend fun getLocations(query: String): LocationsResponseDto =
        api.getLocations(query = query)
}
