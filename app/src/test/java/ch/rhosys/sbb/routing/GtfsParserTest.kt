package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GtfsParserTest {

    private val parser = GtfsParser()

    // ---- CSV helper --------------------------------------------------------

    @Test
    fun `parseCsv handles empty content`() {
        assertTrue(parser.parseCsv("").isEmpty())
    }

    @Test
    fun `parseCsv parses header and one row`() {
        val csv = "a,b,c\n1,2,3"
        val rows = parser.parseCsv(csv)
        assertEquals(1, rows.size)
        assertEquals("1", rows[0]["a"])
        assertEquals("2", rows[0]["b"])
        assertEquals("3", rows[0]["c"])
    }

    @Test
    fun `parseCsv trims whitespace around values`() {
        val csv = "key\n  value  "
        val rows = parser.parseCsv(csv)
        assertEquals("value", rows[0]["key"])
    }

    @Test
    fun `parseCsv handles quoted fields containing commas`() {
        val csv = "name,desc\n\"Zürich, HB\",central"
        val rows = parser.parseCsv(csv)
        assertEquals("Zürich, HB", rows[0]["name"])
        assertEquals("central", rows[0]["desc"])
    }

    // ---- Stops -------------------------------------------------------------

    @Test
    fun `stops are assigned sequential internal IDs`() {
        val feed = feedWith(
            stops = """
                stop_id,stop_name,stop_lat,stop_lon
                S1,Bern,46.9480,7.4474
                S2,Zürich HB,47.3779,8.5403
            """.trimIndent()
        )
        val result = parser.parse(feed)
        val stops = result.network.stops
        assertEquals(2, stops.size)
        assertEquals(0, stops[0].id)
        assertEquals("Bern", stops[0].name)
        assertEquals(1, stops[1].id)
        assertEquals("Zürich HB", stops[1].name)
    }

    @Test
    fun `stop lat lng parsed correctly`() {
        val feed = feedWith(
            stops = "stop_id,stop_name,stop_lat,stop_lon\nS1,Test,46.9480,7.4474"
        )
        val stop = parser.parse(feed).network.stops[0]
        assertEquals(46.9480, stop.lat, 0.0001)
        assertEquals(7.4474, stop.lng, 0.0001)
    }

    // ---- Routes and trips --------------------------------------------------

    @Test
    fun `route short name preferred over long name`() {
        val feed = minimalFeed(
            routeShortName = "IC6",
            routeLongName = "InterCity 6"
        )
        val route = parser.parse(feed).network.routes.first()
        assertEquals("IC6", route.name)
    }

    @Test
    fun `route long name used when short name is blank`() {
        val feed = minimalFeed(routeShortName = "", routeLongName = "InterCity 6")
        val route = parser.parse(feed).network.routes.first()
        assertEquals("InterCity 6", route.name)
    }

    @Test
    fun `trip stop sequence is derived from canonical stop order`() {
        val feed = minimalFeed()
        val route = parser.parse(feed).network.routes.first()
        // minimalFeed has stops S1 → S2 (internal 0 → 1)
        assertEquals(listOf(0, 1), route.stopIds)
    }

    @Test
    fun `time array for two-stop trip has two elements`() {
        // Two stops: [dep0, arr1] → 2*(2-1) = 2 elements
        val feed = minimalFeed(dep0 = "08:00:00", arr1 = "08:20:00")
        val trip = parser.parse(feed).network.routes.first().trips.first()
        assertEquals(2, trip.times.size)
        assertEquals(8 * 3600, trip.times[0])        // dep0
        assertEquals(8 * 3600 + 1200, trip.times[1]) // arr1
    }

    @Test
    fun `time array for three-stop trip has four elements`() {
        // Three stops: [dep0, arr1, dep1, arr2] → 2*(3-1) = 4 elements
        val feed = threestopFeed(
            dep0 = "08:00:00", arr1 = "08:10:00", dep1 = "08:12:00", arr2 = "08:25:00"
        )
        val trip = parser.parse(feed).network.routes.first().trips.first()
        assertEquals(4, trip.times.size)
        assertEquals(8 * 3600,        trip.times[0]) // dep0
        assertEquals(8 * 3600 + 600,  trip.times[1]) // arr1
        assertEquals(8 * 3600 + 720,  trip.times[2]) // dep1
        assertEquals(8 * 3600 + 1500, trip.times[3]) // arr2
    }

    @Test
    fun `overnight time normalised correctly`() {
        // 25:00:00 = 1 hour past midnight = 90000 seconds
        val feed = minimalFeed(dep0 = "24:00:00", arr1 = "25:00:00")
        val trip = parser.parse(feed).network.routes.first().trips.first()
        assertEquals(24 * 3600, trip.times[0])
        assertEquals(25 * 3600, trip.times[1])
    }

    @Test
    fun `service id attached to trip`() {
        val feed = minimalFeed(serviceId = "WD")
        val trip = parser.parse(feed).network.routes.first().trips.first()
        assertEquals("WD", trip.serviceId)
    }

    // ---- Transfers ---------------------------------------------------------

    @Test
    fun `transfers parsed with correct walk time`() {
        val feed = feedWith(
            stops = "stop_id,stop_name,stop_lat,stop_lon\nS1,A,0.0,0.0\nS2,B,0.0,0.0",
            transfers = "from_stop_id,to_stop_id,transfer_type,min_transfer_time\nS1,S2,2,180"
        )
        val transfers = parser.parse(feed).network.transfers
        assertEquals(1, transfers.size)
        assertEquals(0, transfers[0].fromStopId)
        assertEquals(1, transfers[0].toStopId)
        assertEquals(180, transfers[0].walkSeconds)
    }

    // ---- Calendar integration ----------------------------------------------

    @Test
    fun `calendar resolver marks weekday service active on Monday`() {
        val feed = minimalFeed(serviceId = "WD").plus(
            "calendar.txt" to """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                WD,1,1,1,1,1,0,0,20260101,20261231
            """.trimIndent()
        )
        val calendar = parser.parse(feed).calendar
        assertTrue(calendar.isActive("WD", LocalDate.of(2026, 6, 1))) // Monday
        assertFalse(calendar.isActive("WD", LocalDate.of(2026, 6, 6))) // Saturday
    }

    // ---- End-to-end --------------------------------------------------------

    @Test
    fun `full parse produces routable network`() {
        val feed = threestopFeed() + mapOf(
            "calendar.txt" to """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                DAILY,1,1,1,1,1,1,1,20260101,20261231
            """.trimIndent()
        )
        val result = parser.parse(feed)
        val network = result.network
        assertEquals(3, network.stops.size)
        assertEquals(1, network.routes.size)
        assertEquals(3, network.routes[0].stopIds.size)
        // stopToRoutes index populated
        assertTrue(network.stopToRoutes.isNotEmpty())
    }

    // ---- Fixtures ----------------------------------------------------------

    private fun feedWith(
        stops: String = "stop_id,stop_name,stop_lat,stop_lon",
        transfers: String = "from_stop_id,to_stop_id,transfer_type,min_transfer_time",
    ): Map<String, String> = mapOf(
        "stops.txt" to stops,
        "routes.txt" to "route_id,route_short_name,route_long_name",
        "trips.txt" to "trip_id,route_id,service_id",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence",
        "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
        "calendar_dates.txt" to "service_id,date,exception_type",
        "transfers.txt" to transfers,
    )

    private fun minimalFeed(
        routeShortName: String = "R1",
        routeLongName: String = "Route 1",
        serviceId: String = "DAILY",
        dep0: String = "08:00:00",
        arr1: String = "08:20:00",
    ): Map<String, String> = mapOf(
        "stops.txt" to """
            stop_id,stop_name,stop_lat,stop_lon
            S1,StopA,47.0,8.0
            S2,StopB,47.1,8.1
        """.trimIndent(),
        "routes.txt" to "route_id,route_short_name,route_long_name\nR1,$routeShortName,$routeLongName",
        "trips.txt" to "trip_id,route_id,service_id\nT1,R1,$serviceId",
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            T1,$dep0,$dep0,S1,1
            T1,$arr1,$arr1,S2,2
        """.trimIndent(),
        "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
        "calendar_dates.txt" to "service_id,date,exception_type",
        "transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time",
    )

    private fun threestopFeed(
        dep0: String = "08:00:00",
        arr1: String = "08:10:00",
        dep1: String = "08:12:00",
        arr2: String = "08:25:00",
    ): Map<String, String> = mapOf(
        "stops.txt" to """
            stop_id,stop_name,stop_lat,stop_lon
            S1,StopA,47.0,8.0
            S2,StopB,47.1,8.1
            S3,StopC,47.2,8.2
        """.trimIndent(),
        "routes.txt" to "route_id,route_short_name,route_long_name\nR1,IC1,InterCity 1",
        "trips.txt" to "trip_id,route_id,service_id\nT1,R1,DAILY",
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            T1,$dep0,$dep0,S1,1
            T1,$arr1,$dep1,S2,2
            T1,$arr2,$arr2,S3,3
        """.trimIndent(),
        "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
        "calendar_dates.txt" to "service_id,date,exception_type",
        "transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time",
    )
}
