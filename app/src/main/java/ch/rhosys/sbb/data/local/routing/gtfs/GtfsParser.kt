package ch.rhosys.sbb.data.local.routing.gtfs

class GtfsParser {

    data class ParsedGtfs(
        val network: GtfsNetwork,
        val calendar: GtfsCalendarResolver,
        val calendarPatternRows: List<Map<String, String>>,
        val calendarExceptionRows: List<Map<String, String>>,
    )

    // Parses a GTFS feed from a map of filename → CSV text content.
    // Supports stops.txt, routes.txt, trips.txt, stop_times.txt,
    // calendar.txt, calendar_dates.txt, transfers.txt.
    fun parse(files: Map<String, String>): ParsedGtfs {
        val stopIdMap = mutableMapOf<String, Int>()
        val stops = buildStops(parseCsv(files["stops.txt"] ?: ""), stopIdMap)
        val routeNames = buildRouteNames(parseCsv(files["routes.txt"] ?: ""))
        val tripMeta = buildTripMeta(parseCsv(files["trips.txt"] ?: ""))
        val tripStopTimes = buildTripStopTimes(parseCsv(files["stop_times.txt"] ?: ""))
        val routes = buildRoutes(tripMeta, tripStopTimes, routeNames, stopIdMap)
        val transfers = buildTransfers(parseCsv(files["transfers.txt"] ?: ""), stopIdMap)
        val calendarPatternRows = parseCsv(files["calendar.txt"] ?: "")
        val calendarExceptionRows = parseCsv(files["calendar_dates.txt"] ?: "")
        val calendar = GtfsCalendarResolver(
            patterns = calendarPatternRows,
            exceptions = calendarExceptionRows,
        )
        return ParsedGtfs(
            network = GtfsNetwork(stops, routes, transfers),
            calendar = calendar,
            calendarPatternRows = calendarPatternRows,
            calendarExceptionRows = calendarExceptionRows,
        )
    }

    private fun buildStops(
        rows: List<Map<String, String>>,
        stopIdMap: MutableMap<String, Int>,
    ): List<GtfsStop> {
        val stops = mutableListOf<GtfsStop>()
        for (row in rows) {
            val gtfsId = row["stop_id"] ?: continue
            val internalId = stops.size
            stopIdMap[gtfsId] = internalId
            stops.add(GtfsStop(
                id = internalId,
                name = row["stop_name"] ?: gtfsId,
                lat = row["stop_lat"]?.toDoubleOrNull() ?: 0.0,
                lng = row["stop_lon"]?.toDoubleOrNull() ?: 0.0,
            ))
        }
        return stops
    }

    private fun buildRouteNames(rows: List<Map<String, String>>): Map<String, String> =
        rows.mapNotNull { row ->
            val id = row["route_id"] ?: return@mapNotNull null
            val name = row["route_short_name"]?.takeIf { it.isNotBlank() }
                ?: row["route_long_name"] ?: id
            id to name
        }.toMap()

    private data class TripMeta(val routeId: String, val serviceId: String)

    private fun buildTripMeta(rows: List<Map<String, String>>): Map<String, TripMeta> =
        rows.mapNotNull { row ->
            val tripId = row["trip_id"] ?: return@mapNotNull null
            val routeId = row["route_id"] ?: return@mapNotNull null
            val serviceId = row["service_id"] ?: return@mapNotNull null
            tripId to TripMeta(routeId, serviceId)
        }.toMap()

    private data class StopTimeEntry(
        val stopGtfsId: String,
        val arrSec: Int,
        val depSec: Int,
        val seq: Int,
    )

    private fun buildTripStopTimes(rows: List<Map<String, String>>): Map<String, List<StopTimeEntry>> {
        val result = mutableMapOf<String, MutableList<StopTimeEntry>>()
        for (row in rows) {
            val tripId = row["trip_id"] ?: continue
            result.getOrPut(tripId) { mutableListOf() }.add(
                StopTimeEntry(
                    stopGtfsId = row["stop_id"] ?: continue,
                    arrSec = GtfsTimeNormaliser.toSeconds(row["arrival_time"] ?: "00:00:00"),
                    depSec = GtfsTimeNormaliser.toSeconds(row["departure_time"] ?: "00:00:00"),
                    seq = row["stop_sequence"]?.toIntOrNull() ?: 0,
                )
            )
        }
        result.values.forEach { it.sortBy { e -> e.seq } }
        return result
    }

    private fun buildRoutes(
        tripMeta: Map<String, TripMeta>,
        tripStopTimes: Map<String, List<StopTimeEntry>>,
        routeNames: Map<String, String>,
        stopIdMap: Map<String, Int>,
    ): List<GtfsRoute> {
        val routeToTripIds = mutableMapOf<String, MutableList<String>>()
        tripMeta.forEach { (tripId, meta) ->
            routeToTripIds.getOrPut(meta.routeId) { mutableListOf() }.add(tripId)
        }

        val routes = mutableListOf<GtfsRoute>()
        for ((gtfsRouteId, tripIds) in routeToTripIds) {
            val firstId = tripIds.firstOrNull { tripStopTimes.containsKey(it) } ?: continue
            val canonical = tripStopTimes[firstId] ?: continue
            val stopIdList = canonical.mapNotNull { stopIdMap[it.stopGtfsId] }
            if (stopIdList.size < 2) continue

            val trips = tripIds.mapNotNull { tripId ->
                val sts = tripStopTimes[tripId] ?: return@mapNotNull null
                if (sts.size != canonical.size) return@mapNotNull null
                if (sts.map { it.stopGtfsId } != canonical.map { it.stopGtfsId }) return@mapNotNull null
                GtfsTrip(
                    id = tripId.hashCode(),
                    serviceId = tripMeta[tripId]?.serviceId ?: return@mapNotNull null,
                    times = buildTripTimes(sts),
                )
            }
            if (trips.isEmpty()) continue

            routes.add(GtfsRoute(
                id = routes.size,
                name = routeNames[gtfsRouteId] ?: gtfsRouteId,
                stopIds = stopIdList,
                trips = trips,
            ))
        }
        return routes
    }

    private fun buildTripTimes(stopTimes: List<StopTimeEntry>): List<Int> {
        val n = stopTimes.size
        // Layout: [dep0, arr1, dep1, arr2, ..., arr(N-1)] — 2*(N-1) elements
        // times[p*2] = departure at p, times[p*2-1] = arrival at p
        val times = IntArray(2 * (n - 1))
        for (i in stopTimes.indices) {
            if (i < n - 1) times[i * 2] = stopTimes[i].depSec
            if (i > 0)     times[i * 2 - 1] = stopTimes[i].arrSec
        }
        return times.toList()
    }

    private fun buildTransfers(
        rows: List<Map<String, String>>,
        stopIdMap: Map<String, Int>,
    ): List<GtfsTransfer> = rows.mapNotNull { row ->
        val fromId = stopIdMap[row["from_stop_id"] ?: return@mapNotNull null] ?: return@mapNotNull null
        val toId = stopIdMap[row["to_stop_id"] ?: return@mapNotNull null] ?: return@mapNotNull null
        val walkSec = row["min_transfer_time"]?.toIntOrNull() ?: return@mapNotNull null
        GtfsTransfer(fromId, toId, walkSec)
    }

    // CSV parsing — handles quoted fields and BOM
    fun parseCsv(content: String): List<Map<String, String>> {
        if (content.isBlank()) return emptyList()
        val lines = content.trimStart('﻿').lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val headers = splitCsvLine(lines[0])
        return lines.drop(1).map { line ->
            val values = splitCsvLine(line)
            buildMap {
                headers.forEachIndexed { i, h ->
                    put(h.trim(), values.getOrNull(i)?.trim() ?: "")
                }
            }
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuote = false
        val current = StringBuilder()
        for (ch in line) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch == ',' && !inQuote -> { result.add(current.toString()); current.clear() }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }
}
