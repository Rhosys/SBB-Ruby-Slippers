package ch.rhosys.sbb.domain.model

// Coarse vehicle category of a stop, used to decide which stops the map picker shows
// at a given zoom level. Declared in display-priority order: a stop served by several
// modes (a station with a bus stop out front) is shown as its first/most prominent one.
enum class TransportMode {
    TRAIN, FERRY, CABLEWAY, TRAM, BUS, OTHER;

    companion object {
        // Basic GTFS route_type values (0–12) plus the extended HVT types (100–1700)
        // that the Swiss feed actually uses.
        fun fromGtfsRouteType(type: Int): TransportMode = when (type) {
            1, 2, 12, in 100..199, in 400..499 -> TRAIN
            0, 5, in 900..999 -> TRAM
            3, 11, in 200..299, in 700..899 -> BUS
            4, in 1000..1299 -> FERRY
            6, 7, in 1300..1499 -> CABLEWAY
            else -> OTHER
        }

        // transport.opendata.ch's `icon` field on a location result.
        fun fromApiIcon(icon: String?): TransportMode = when (icon?.lowercase()) {
            "train" -> TRAIN
            "tram" -> TRAM
            "bus" -> BUS
            "ship" -> FERRY
            "cableway", "funicular", "gondola" -> CABLEWAY
            else -> OTHER
        }
    }
}
