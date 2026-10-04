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
        // How late we plan for this leg to arrive — what transfer slack and the arrival
        // used to rank connections (JourneyCriteria) are measured against. Defaults to the
        // real-time arrival delay when one is known (API results); the local router sets it
        // from its ExpectedDelayProvider, which is where a live GTFS-RT or historical delay
        // model plugs in.
        val expectedDelayMinutes: Int = arrival.delayMinutes,
    ) : Leg()

    data class Walk(
        val fromName: String,
        val toName: String,
        val durationMinutes: Int,
    ) : Leg()
}
