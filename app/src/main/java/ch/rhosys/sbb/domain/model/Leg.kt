package ch.rhosys.sbb.domain.model

sealed class Leg {
    data class Transit(
        val departure: Stop,
        val arrival: Stop,
        val lineName: String,
        val lineCategory: String,
        val direction: String,
        val operator: String? = null,
        // Operator's trip/run number (e.g. "021644") — fine detail only; lineName is what riders see.
        val tripNumber: String? = null,
        val intermediateStops: List<Stop> = emptyList(),
    ) : Leg()

    data class Walk(
        val fromName: String,
        val toName: String,
        val durationMinutes: Int,
    ) : Leg()
}
