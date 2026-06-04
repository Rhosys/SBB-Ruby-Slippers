package ch.rhosys.sbb.data.local.routing.gtfs

class GtfsNetworkBuilder {
    private val stops = mutableListOf<GtfsStop>()
    private val routes = mutableListOf<RouteInProgress>()
    private val transfers = mutableListOf<GtfsTransfer>()

    private data class RouteInProgress(
        val id: Int,
        val name: String,
        val stopIds: List<Int>,
        val trips: MutableList<GtfsTrip> = mutableListOf(),
    )

    fun addStop(id: Int, name: String, lat: Double, lng: Double) = apply {
        stops.add(GtfsStop(id, name, lat, lng))
    }

    fun addRoute(id: Int, name: String, stops: List<Int>) = apply {
        routes.add(RouteInProgress(id, name, stops))
    }

    fun addTrip(routeId: Int, tripId: Int, times: List<Int>) = apply {
        routes.first { it.id == routeId }.trips.add(GtfsTrip(tripId, times))
    }

    fun addTransfer(fromStop: Int, toStop: Int, walkSeconds: Int) = apply {
        transfers.add(GtfsTransfer(fromStop, toStop, walkSeconds))
    }

    fun build(): GtfsNetwork = GtfsNetwork(
        stops = stops.toList(),
        routes = routes.map { r -> GtfsRoute(r.id, r.name, r.stopIds, r.trips.toList()) },
        transfers = transfers.toList(),
    )
}
