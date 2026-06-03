package ch.rhosys.sbb.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Shared ────────────────────────────────────────────────────────────────────

@Serializable
data class LocationDto(
    val id: String? = null,
    val name: String? = null,
    val score: Double? = null,
    val coordinate: CoordinateDto? = null,
    val distance: Int? = null,
)

@Serializable
data class CoordinateDto(
    val type: String? = null,
    val x: Double? = null,
    val y: Double? = null,
)

@Serializable
data class StopDto(
    val station: LocationDto? = null,
    val departure: String? = null,
    val arrival: String? = null,
    val delay: Int? = null,
    val platform: String? = null,
)

// ── /v1/connections ───────────────────────────────────────────────────────────

@Serializable
data class ConnectionsResponseDto(
    val connections: List<ConnectionDto> = emptyList(),
    val from: LocationDto? = null,
    val to: LocationDto? = null,
)

@Serializable
data class ConnectionDto(
    val from: StopDto? = null,
    val to: StopDto? = null,
    val duration: String? = null,
    val transfers: Int? = null,
    val products: List<String> = emptyList(),
    val sections: List<SectionDto> = emptyList(),
)

@Serializable
data class SectionDto(
    val departure: StopDto? = null,
    val arrival: StopDto? = null,
    val journey: JourneyDto? = null,
    val walk: WalkDto? = null,
)

@Serializable
data class JourneyDto(
    val name: String? = null,
    val category: String? = null,
    val number: String? = null,
    val operator: String? = null,
    val to: String? = null,
)

@Serializable
data class WalkDto(
    val duration: Int? = null,
)

// ── /v1/stationboard ─────────────────────────────────────────────────────────

@Serializable
data class StationboardResponseDto(
    val station: LocationDto? = null,
    val stationboard: List<JourneyEntryDto> = emptyList(),
)

@Serializable
data class JourneyEntryDto(
    val stop: StopDto? = null,
    val name: String? = null,
    val category: String? = null,
    val number: String? = null,
    val operator: String? = null,
    val to: String? = null,
    @SerialName("passList") val passList: List<StopDto> = emptyList(),
)

// ── /v1/locations ─────────────────────────────────────────────────────────────

@Serializable
data class LocationsResponseDto(
    val stations: List<LocationDto> = emptyList(),
)
