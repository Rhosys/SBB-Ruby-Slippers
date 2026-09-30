package ch.rhosys.sbb.domain.model

// A stop as drawn on the map picker: one pin per distinct stop name (a station's
// individual platforms collapse into it), shown as its most prominent mode.
data class MapStop(
    val name: String,
    val lat: Double,
    val lng: Double,
    val mode: TransportMode,
)
