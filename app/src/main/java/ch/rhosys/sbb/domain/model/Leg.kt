package ch.rhosys.sbb.domain.model

sealed class Leg {
    data class Transit(
        val departure: Stop,
        val arrival: Stop,
        val lineName: String,
        val lineCategory: String,
        val direction: String,
        val operator: String? = null,
        val intermediateStops: List<Stop> = emptyList(),
    ) : Leg()

    data class Walk(
        val fromName: String,
        val toName: String,
        val durationMinutes: Int,
    ) : Leg()
}
