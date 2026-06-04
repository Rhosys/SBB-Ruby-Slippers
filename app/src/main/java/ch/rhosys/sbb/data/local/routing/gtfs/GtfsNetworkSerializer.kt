package ch.rhosys.sbb.data.local.routing.gtfs

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

// Custom binary format for GtfsNetwork + calendar rows.
// Version 1 layout:
//   [INT] magic, [INT] version
//   stops, routes, transfers, calendar patterns, calendar exceptions
// Strings use writeUTF/readUTF (2-byte length-prefixed modified UTF-8).
object GtfsNetworkSerializer {
    private const val MAGIC = 0x47544653.toInt() // 'G','T','F','S'
    private const val VERSION = 1

    private val DAY_KEYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    fun write(
        network: GtfsNetwork,
        patternRows: List<Map<String, String>>,
        exceptionRows: List<Map<String, String>>,
        out: OutputStream,
    ) {
        DataOutputStream(out.buffered()).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeInt(VERSION)
            writeStops(dos, network.stops)
            writeRoutes(dos, network.routes)
            writeTransfers(dos, network.transfers)
            writePatterns(dos, patternRows)
            writeExceptions(dos, exceptionRows)
        }
    }

    fun read(input: InputStream): Triple<GtfsNetwork, List<Map<String, String>>, List<Map<String, String>>> {
        DataInputStream(input.buffered()).use { dis ->
            require(dis.readInt() == MAGIC) { "Not a GTFS binary file" }
            require(dis.readInt() == VERSION) { "Unsupported GTFS binary version" }
            val stops = readStops(dis)
            val routes = readRoutes(dis)
            val transfers = readTransfers(dis)
            val patternRows = readPatterns(dis)
            val exceptionRows = readExceptions(dis)
            return Triple(GtfsNetwork(stops, routes, transfers), patternRows, exceptionRows)
        }
    }

    // ---- Stops ---------------------------------------------------------------

    private fun writeStops(dos: DataOutputStream, stops: List<GtfsStop>) {
        dos.writeInt(stops.size)
        for (s in stops) {
            dos.writeInt(s.id)
            dos.writeUTF(s.name)
            dos.writeDouble(s.lat)
            dos.writeDouble(s.lng)
        }
    }

    private fun readStops(dis: DataInputStream): List<GtfsStop> {
        val count = dis.readInt()
        return List(count) {
            GtfsStop(id = dis.readInt(), name = dis.readUTF(), lat = dis.readDouble(), lng = dis.readDouble())
        }
    }

    // ---- Routes + trips ------------------------------------------------------

    private fun writeRoutes(dos: DataOutputStream, routes: List<GtfsRoute>) {
        dos.writeInt(routes.size)
        for (r in routes) {
            dos.writeInt(r.id)
            dos.writeUTF(r.name)
            dos.writeInt(r.stopIds.size)
            for (id in r.stopIds) dos.writeInt(id)
            dos.writeInt(r.trips.size)
            for (t in r.trips) writeTrip(dos, t)
        }
    }

    private fun writeTrip(dos: DataOutputStream, trip: GtfsTrip) {
        dos.writeInt(trip.id)
        dos.writeUTF(trip.serviceId)
        dos.writeInt(trip.times.size)
        for (t in trip.times) dos.writeInt(t)
    }

    private fun readRoutes(dis: DataInputStream): List<GtfsRoute> {
        val count = dis.readInt()
        return List(count) {
            val id = dis.readInt()
            val name = dis.readUTF()
            val stopIds = List(dis.readInt()) { dis.readInt() }
            val trips = List(dis.readInt()) { readTrip(dis) }
            GtfsRoute(id, name, stopIds, trips)
        }
    }

    private fun readTrip(dis: DataInputStream): GtfsTrip {
        val id = dis.readInt()
        val serviceId = dis.readUTF()
        val times = List(dis.readInt()) { dis.readInt() }
        return GtfsTrip(id, serviceId, times)
    }

    // ---- Transfers -----------------------------------------------------------

    private fun writeTransfers(dos: DataOutputStream, transfers: List<GtfsTransfer>) {
        dos.writeInt(transfers.size)
        for (t in transfers) {
            dos.writeInt(t.fromStopId)
            dos.writeInt(t.toStopId)
            dos.writeInt(t.walkSeconds)
        }
    }

    private fun readTransfers(dis: DataInputStream): List<GtfsTransfer> {
        val count = dis.readInt()
        return List(count) {
            GtfsTransfer(dis.readInt(), dis.readInt(), dis.readInt())
        }
    }

    // ---- Calendar patterns ---------------------------------------------------
    // Stored as: serviceId (UTF), startDate (INT yyyyMMdd), endDate (INT), dayMask (BYTE)

    private fun writePatterns(dos: DataOutputStream, rows: List<Map<String, String>>) {
        val valid = rows.filter { it["service_id"] != null && it["start_date"] != null && it["end_date"] != null }
        dos.writeInt(valid.size)
        for (row in valid) {
            dos.writeUTF(row["service_id"]!!)
            dos.writeInt(row["start_date"]!!.toInt())
            dos.writeInt(row["end_date"]!!.toInt())
            var mask = 0
            for ((bit, key) in DAY_KEYS.withIndex()) {
                if (row[key] == "1") mask = mask or (1 shl bit)
            }
            dos.writeByte(mask)
        }
    }

    private fun readPatterns(dis: DataInputStream): List<Map<String, String>> {
        val count = dis.readInt()
        return List(count) {
            val serviceId = dis.readUTF()
            val startDate = dis.readInt()
            val endDate = dis.readInt()
            val mask = dis.readByte().toInt() and 0xFF
            buildMap {
                put("service_id", serviceId)
                put("start_date", startDate.toString())
                put("end_date", endDate.toString())
                for ((bit, key) in DAY_KEYS.withIndex()) {
                    put(key, if (mask and (1 shl bit) != 0) "1" else "0")
                }
            }
        }
    }

    // ---- Calendar exceptions -------------------------------------------------
    // Stored as: serviceId (UTF), date (INT yyyyMMdd), type (BYTE 1=add 2=remove)

    private fun writeExceptions(dos: DataOutputStream, rows: List<Map<String, String>>) {
        val valid = rows.filter { it["service_id"] != null && it["date"] != null && it["exception_type"] != null }
        dos.writeInt(valid.size)
        for (row in valid) {
            dos.writeUTF(row["service_id"]!!)
            dos.writeInt(row["date"]!!.toInt())
            dos.writeByte(row["exception_type"]!!.toInt())
        }
    }

    private fun readExceptions(dis: DataInputStream): List<Map<String, String>> {
        val count = dis.readInt()
        return List(count) {
            mapOf(
                "service_id"     to dis.readUTF(),
                "date"           to dis.readInt().toString(),
                "exception_type" to (dis.readByte().toInt() and 0xFF).toString(),
            )
        }
    }
}
