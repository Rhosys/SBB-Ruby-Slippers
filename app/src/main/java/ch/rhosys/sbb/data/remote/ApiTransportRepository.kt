package ch.rhosys.sbb.data.remote

import ch.rhosys.sbb.data.remote.dto.ConnectionDto
import ch.rhosys.sbb.data.remote.dto.JourneyEntryDto
import ch.rhosys.sbb.data.remote.dto.LocationsResponseDto
import ch.rhosys.sbb.data.remote.dto.SectionDto
import ch.rhosys.sbb.data.remote.dto.StopDto
import ch.rhosys.sbb.domain.TransportRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Departure
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.domain.model.Stop
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// transport.opendata.ch returns offsets without a colon (e.g. "+0100"), which
// DateTimeFormatter.ISO_OFFSET_DATE_TIME / OffsetDateTime.parse(CharSequence) rejects.
private val API_OFFSET_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")
private val API_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val API_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

class ApiTransportRepository @Inject constructor(
    private val api: TransportApi,
) : TransportRepository {

    override suspend fun getConnections(
        from: SearchEndpoint,
        to: SearchEndpoint,
        date: LocalDate,
        time: LocalTime,
        isArrivalTime: Boolean,
    ): List<Connection> {
        val fromStr = resolveEndpoint(from)
        val toStr = resolveEndpoint(to)
        return api.getConnections(
            from = fromStr,
            to = toStr,
            date = date.format(API_DATE_FMT),
            time = time.format(API_TIME_FMT),
            isArrivalTime = if (isArrivalTime) 1 else 0,
        ).connections.map { it.toDomainConnection() }
    }

    override suspend fun getStationboard(station: String): List<Departure> =
        api.getStationboard(station).stationboard.map { it.toDomain() }

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

    private fun JourneyEntryDto.toDomain(): Departure = Departure(
        lineName = name ?: number ?: "",
        lineCategory = category ?: "",
        direction = to ?: "",
        scheduledDeparture = stop?.departure?.toInstantOrNull(),
        delayMinutes = stop?.delay ?: 0,
        platform = stop?.platform,
        operator = operator,
    )

}

// The trip itself runs from the first boarding to the last alighting — any walk from
// the user's origin (e.g. a street address) to the first stop, or from the last stop to
// the destination, comes back as a leading/trailing walk section and is carried in
// walkToFirstStop/walkFromLastStop instead, so it's displayed separately from the trip
// times rather than silently moving them.
internal fun ConnectionDto.toDomainConnection(): Connection {
    val allLegs = sections.map { it.toDomain() }
    val firstTransit = allLegs.indexOfFirst { it is Leg.Transit }
    val lastTransit = allLegs.indexOfLast { it is Leg.Transit }
    if (firstTransit < 0) {
        return Connection(
            departure = from?.toDomainDeparture() ?: Stop(stationName = ""),
            arrival = to?.toDomainArrival() ?: Stop(stationName = ""),
            legs = allLegs,
            transfers = transfers ?: 0,
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )
    }
    fun walkMinutes(legs: List<Leg>) = legs.filterIsInstance<Leg.Walk>().sumOf { it.durationMinutes }.toLong()
    return Connection(
        departure = (allLegs[firstTransit] as Leg.Transit).departure,
        arrival = (allLegs[lastTransit] as Leg.Transit).arrival,
        legs = allLegs.subList(firstTransit, lastTransit + 1),
        transfers = transfers ?: 0,
        walkToFirstStop = Duration.ofMinutes(walkMinutes(allLegs.subList(0, firstTransit))),
        walkFromLastStop = Duration.ofMinutes(walkMinutes(allLegs.subList(lastTransit + 1, allLegs.size))),
    )
}

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
        // walk.duration is frequently absent (notably for address → stop walks), so the
        // section's own departure/arrival timestamps are the primary source.
        val start = this.departure?.departure?.toInstantOrNull()
        val end = this.arrival?.arrival?.toInstantOrNull()
        val fromTimestamps = if (start != null && end != null && !end.isBefore(start)) {
            Duration.between(start, end).toMinutes().toInt()
        } else null
        Leg.Walk(
            fromName = this.departure?.station?.name ?: "",
            toName = this.arrival?.station?.name ?: "",
            durationMinutes = fromTimestamps ?: walk?.duration ?: 0,
        )
    }
}

private fun String.toInstantOrNull(): Instant? =
    runCatching { OffsetDateTime.parse(this).toInstant() }
        .recoverCatching { OffsetDateTime.parse(this, API_OFFSET_TIME_FMT).toInstant() }
        .getOrNull()
