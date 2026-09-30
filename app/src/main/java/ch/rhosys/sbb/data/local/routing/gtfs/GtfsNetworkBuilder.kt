package ch.rhosys.sbb.data.local.routing.gtfs

import ch.rhosys.sbb.domain.model.TransportMode

class GtfsNetworkBuilder {
    private val stops = mutableListOf<GtfsStop>()
    private val routes = mutableListOf<RouteInProgress>()
    private val transfers = mutableListOf<GtfsTransfer>()

    private data class RouteInProgress(
        val id: Int,
        val name: String,
        val stopIds: List<Int>,
        val mode: TransportMode,
        val trips: MutableList<GtfsTrip> = mutableListOf(),
    )

    fun addStop(id: Int, name: String, lat: Double, lng: Double) = apply {
        stops.add(GtfsStop(id, name, lat, lng))
    }

    fun addRoute(id: Int, name: String, stops: List<Int>, mode: TransportMode = TransportMode.OTHER) = apply {
        routes.add(RouteInProgress(id, name, stops, mode))
    }

    fun addTrip(routeId: Int, tripId: Int, times: List<Int>, serviceId: String = "") = apply {
        routes.first { it.id == routeId }.trips.add(GtfsTrip(tripId, serviceId, times))
    }

    fun addTransfer(fromStop: Int, toStop: Int, distanceMeters: Double) = apply {
        transfers.add(GtfsTransfer(fromStop, toStop, distanceMeters))
    }

    fun build(): GtfsNetwork = GtfsNetwork(
        stops = stops.toList(),
        routes = routes.map { r -> GtfsRoute(r.id, r.name, r.stopIds, r.trips.toList(), r.mode) },
        transfers = transfers.toList(),
    )
}
