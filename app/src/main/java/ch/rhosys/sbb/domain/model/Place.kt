package ch.rhosys.sbb.domain.model

data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val sortOrder: Int = 0,
    val label: String? = null,
    val photoUri: String? = null,
) {
    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: name

    fun distanceMetersTo(lat: Double, lng: Double): Double {
        val dLat = Math.toRadians(lat - this.lat)
        val dLng = Math.toRadians(lng - this.lng)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(this.lat)) * Math.cos(Math.toRadians(lat)) *
                Math.sin(dLng / 2).let { it * it }
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun toSearchEndpoint() = SearchEndpoint.NamedPlace(name, lat, lng)
}
