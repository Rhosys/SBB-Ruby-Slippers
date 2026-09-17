package ch.rhosys.sbb.data.local.routing.gtfs

data class GtfsStop(
    val id: Int,
    val name: String,
    val lat: Double,
    val lng: Double,
)

data class GtfsRoute(
    val id: Int,
    val name: String,
    val stopIds: List<Int>,
    val trips: List<GtfsTrip>,
)

// times: flat list of (arr, dep) seconds for each stop — 2 ints per stop
// First stop has no meaningful arrival; last stop has no meaningful departure.
// Layout: [dep0, arr1, dep1, arr2, dep2, ..., arrN]
data class GtfsTrip(
    val id: Int,
    val serviceId: String = "",
    val times: List<Int>,
)

// Transfers are stored as a physical distance, not a fixed duration — GTFS feeds
// don't reliably provide walking times, and a fixed time can't reflect the user's own
// pace anyway. The engine converts distance -> seconds at query time using the
// user-configured walking speed (see RoutingQuery.walkingPaceMetersPerSecond).
data class GtfsTransfer(
    val fromStopId: Int,
    val toStopId: Int,
    val distanceMeters: Double,
)

data class GtfsNetwork(
    val stops: List<GtfsStop>,
    val routes: List<GtfsRoute>,
    val transfers: List<GtfsTransfer>,
) {
    // Derived index: stop → list of (routeIdx, positionInRoute)
    val stopToRoutes: Map<Int, List<Pair<Int, Int>>> by lazy {
        buildMap<Int, MutableList<Pair<Int, Int>>> {
            routes.forEachIndexed { routeIdx, route ->
                route.stopIds.forEachIndexed { pos, stopId ->
                    getOrPut(stopId) { mutableListOf() }.add(routeIdx to pos)
                }
            }
        }
    }

    // Derived index: stop → list of (neighbourStopId, distanceMeters)
    val stopToTransfers: Map<Int, List<Pair<Int, Double>>> by lazy {
        buildMap<Int, MutableList<Pair<Int, Double>>> {
            transfers.forEach { t ->
                getOrPut(t.fromStopId) { mutableListOf() }.add(t.toStopId to t.distanceMeters)
                getOrPut(t.toStopId) { mutableListOf() }.add(t.fromStopId to t.distanceMeters)
            }
        }
    }
}
