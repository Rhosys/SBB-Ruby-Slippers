package ch.rhosys.sbb.domain.model

sealed class SearchEndpoint {
    data class CurrentLocation(val lat: Double, val lng: Double) : SearchEndpoint()
    data class NamedPlace(
        val name: String,
        val lat: Double? = null,
        val lng: Double? = null,
    ) : SearchEndpoint()

    fun displayName(): String = when (this) {
        is CurrentLocation -> CURRENT_LOCATION_LABEL
        is NamedPlace -> name
    }

    fun latOrNull(): Double? = when (this) {
        is CurrentLocation -> lat
        is NamedPlace -> lat
    }

    fun lngOrNull(): Double? = when (this) {
        is CurrentLocation -> lng
        is NamedPlace -> lng
    }

    companion object {
        // Text-field placeholder for an unresolved GPS pick — recognized by search
        // flows so it can be swapped for an actual CurrentLocation(lat, lng).
        const val CURRENT_LOCATION_LABEL = "Current location"
    }
}
