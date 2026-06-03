package ch.rhosys.sbb.domain.model

sealed class SearchEndpoint {
    data class CurrentLocation(val lat: Double, val lng: Double) : SearchEndpoint()
    data class NamedPlace(
        val name: String,
        val lat: Double? = null,
        val lng: Double? = null,
    ) : SearchEndpoint()

    fun displayName(): String = when (this) {
        is CurrentLocation -> "Current location"
        is NamedPlace -> name
    }
}
