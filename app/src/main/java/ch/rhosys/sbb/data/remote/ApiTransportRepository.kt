package ch.rhosys.sbb.data.remote

import ch.rhosys.sbb.data.remote.dto.ConnectionDto
import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.data.remote.dto.SectionDto
import ch.rhosys.sbb.data.remote.dto.StationboardResponseDto
import ch.rhosys.sbb.data.remote.dto.StopDto
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.domain.model.Stop
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject

class ApiTransportRepository @Inject constructor(
    private val api: TransportApi,
) : TransportRepository {

    override suspend fun getConnections(from: SearchEndpoint, to: SearchEndpoint): List<Connection> {
        val fromStr = resolveEndpoint(from)
        val toStr = resolveEndpoint(to)
        return api.getConnections(from = fromStr, to = toStr).connections.map { it.toDomain() }
    }

    override suspend fun getStationboard(station: String): StationboardResponseDto =
        api.getStationboard(station)

    override suspend fun getLocations(query: String): LocationsResponseDto =
        api.getLocations(query)

    override suspend fun getLocationsByCoordinate(lat: Double, lng: Double): LocationsResponseDto =
        api.getLocationsByCoordinate(longitude = lng, latitude = lat)

    private suspend fun resolveEndpoint(endpoint: SearchEndpoint): String = when (endpoint) {
        is SearchEndpoint.NamedPlace -> endpoint.name
        is SearchEndpoint.CurrentLocation -> {
            val response = api.getLocationsByCoordinate(
                longitude = endpoint.lng,
                latitude = endpoint.lat,
            )
            response.stations.firstOrNull()?.name ?: "${endpoint.lat},${endpoint.lng}"
        }
    }

    private fun ConnectionDto.toDomain(): Connection = Connection(
        departure = from?.toDomainDeparture() ?: Stop(stationName = ""),
        arrival = to?.toDomainArrival() ?: Stop(stationName = ""),
        legs = sections.map { it.toDomain() },
        transfers = transfers ?: 0,
        // REST API doesn't return walk times; the ViewModel supplies these from M1.
        walkToFirstStop = java.time.Duration.ZERO,
        walkFromLastStop = java.time.Duration.ZERO,
    )

    private fun StopDto.toDomainDeparture(): Stop = Stop(
        stationName = station?.name ?: "",
        stationId = station?.id,
        scheduledTime = departure?.toInstantOrNull(),
        delayMinutes = delay ?: 0,
        platform = platform,
    )

    private fun StopDto.toDomainArrival(): Stop = Stop(
        stationName = station?.name ?: "",
        stationId = station?.id,
        scheduledTime = arrival?.toInstantOrNull(),
        delayMinutes = delay ?: 0,
        platform = platform,
    )

    private fun SectionDto.toDomain(): Leg {
        val jny = journey
        return if (jny != null) {
            Leg.Transit(
                departure = this.departure?.toDomainDeparture() ?: Stop(stationName = ""),
                arrival = this.arrival?.toDomainArrival() ?: Stop(stationName = ""),
                lineName = jny.name ?: jny.number ?: "",
                lineCategory = jny.category ?: "",
                direction = jny.to ?: "",
                operator = jny.operator,
            )
        } else {
            Leg.Walk(
                fromName = this.departure?.station?.name ?: "",
                toName = this.arrival?.station?.name ?: "",
                durationMinutes = walk?.duration ?: 0,
            )
        }
    }

    private fun String.toInstantOrNull(): Instant? = runCatching {
        OffsetDateTime.parse(this).toInstant()
    }.getOrNull()
}
