package ch.rhosys.sbb.data.local.db.entity

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

// The whole connection of a trip, as saved in trip_history.connectionJson so a past trip
// can show its full details later. A storage format of its own (epoch seconds, not
// java.time) so changes to the domain classes never break reading old rows: unknown keys
// are ignored and a row that doesn't decode just shows no details.

private val json = Json { ignoreUnknownKeys = true }

fun Connection.toStoredJson(): String = json.encodeToString(toStored())

fun String.toStoredConnectionOrNull(): Connection? =
    runCatching { json.decodeFromString<StoredConnection>(this).toDomain() }.getOrNull()

@Serializable
private data class StoredConnection(
    val departure: StoredStop,
    val arrival: StoredStop,
    val legs: List<StoredLeg>,
    val transfers: Int,
    val walkToFirstStopSeconds: Long = 0,
    val walkFromLastStopSeconds: Long = 0,
)

@Serializable
private data class StoredStop(
    val stationName: String,
    val stationId: String? = null,
    val scheduledEpochSeconds: Long? = null,
    val delayMinutes: Int = 0,
    val platform: String? = null,
    val isCancelled: Boolean = false,
)

@Serializable
private sealed class StoredLeg {
    @Serializable
    @SerialName("transit")
    data class Transit(
        val departure: StoredStop,
        val arrival: StoredStop,
        val lineName: String,
        val lineCategory: String,
        val direction: String,
        val operator: String? = null,
        val tripNumber: String? = null,
        val intermediateStops: List<StoredStop> = emptyList(),
        val expectedDelayMinutes: Int = 0,
    ) : StoredLeg()

    @Serializable
    @SerialName("walk")
    data class Walk(val fromName: String, val toName: String, val durationMinutes: Int) : StoredLeg()
}

private fun Connection.toStored() = StoredConnection(
    departure = departure.toStored(),
    arrival = arrival.toStored(),
    legs = legs.map { leg ->
        when (leg) {
            is Leg.Transit -> StoredLeg.Transit(
                departure = leg.departure.toStored(),
                arrival = leg.arrival.toStored(),
                lineName = leg.lineName,
                lineCategory = leg.lineCategory,
                direction = leg.direction,
                operator = leg.operator,
                tripNumber = leg.tripNumber,
                intermediateStops = leg.intermediateStops.map { it.toStored() },
                expectedDelayMinutes = leg.expectedDelayMinutes,
            )
            is Leg.Walk -> StoredLeg.Walk(leg.fromName, leg.toName, leg.durationMinutes)
        }
    },
    transfers = transfers,
    walkToFirstStopSeconds = walkToFirstStop.seconds,
    walkFromLastStopSeconds = walkFromLastStop.seconds,
)

private fun StoredConnection.toDomain() = Connection(
    departure = departure.toDomain(),
    arrival = arrival.toDomain(),
    legs = legs.map { leg ->
        when (leg) {
            is StoredLeg.Transit -> Leg.Transit(
                departure = leg.departure.toDomain(),
                arrival = leg.arrival.toDomain(),
                lineName = leg.lineName,
                lineCategory = leg.lineCategory,
                direction = leg.direction,
                operator = leg.operator,
                tripNumber = leg.tripNumber,
                intermediateStops = leg.intermediateStops.map { it.toDomain() },
                expectedDelayMinutes = leg.expectedDelayMinutes,
            )
            is StoredLeg.Walk -> Leg.Walk(leg.fromName, leg.toName, leg.durationMinutes)
        }
    },
    transfers = transfers,
    walkToFirstStop = Duration.ofSeconds(walkToFirstStopSeconds),
    walkFromLastStop = Duration.ofSeconds(walkFromLastStopSeconds),
)

private fun Stop.toStored() = StoredStop(
    stationName = stationName,
    stationId = stationId,
    scheduledEpochSeconds = scheduledTime?.epochSecond,
    delayMinutes = delayMinutes,
    platform = platform,
    isCancelled = isCancelled,
)

private fun StoredStop.toDomain() = Stop(
    stationName = stationName,
    stationId = stationId,
    scheduledTime = scheduledEpochSeconds?.let(Instant::ofEpochSecond),
    delayMinutes = delayMinutes,
    platform = platform,
    isCancelled = isCancelled,
)
