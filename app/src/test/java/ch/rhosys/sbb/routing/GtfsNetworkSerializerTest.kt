package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class GtfsNetworkSerializerTest {

    private val weekdayPatterns = listOf(mapOf(
        "service_id" to "WD",
        "monday" to "1", "tuesday" to "1", "wednesday" to "1",
        "thursday" to "1", "friday" to "1",
        "saturday" to "0", "sunday" to "0",
        "start_date" to "20260101", "end_date" to "20261231",
    ))

    private val satAddException = listOf(mapOf(
        "service_id" to "WD",
        "date" to "20260801",
        "exception_type" to "1",
    ))

    @Test
    fun `stops round-trip preserves id, name, lat, lng`() {
        val (network, _, _) = roundTrip(buildNetwork())
        assertEquals(3, network.stops.size)
        assertEquals(0, network.stops[0].id)
        assertEquals("Bern", network.stops[0].name)
        assertEquals(46.948, network.stops[0].lat, 0.001)
        assertEquals(7.447, network.stops[0].lng, 0.001)
    }

    @Test
    fun `routes round-trip preserves name, stop sequence, trip count`() {
        val (network, _, _) = roundTrip(buildNetwork())
        val route = network.routes.first()
        assertEquals("IC6", route.name)
        assertEquals(listOf(0, 1, 2), route.stopIds)
        assertEquals(2, route.trips.size)
    }

    @Test
    fun `trip times round-trip exactly`() {
        val (network, _, _) = roundTrip(buildNetwork())
        val times = network.routes[0].trips[0].times
        assertEquals(listOf(8 * 3600, 8 * 3600 + 600, 8 * 3600 + 720, 8 * 3600 + 1500), times)
    }

    @Test
    fun `trip serviceId round-trip`() {
        val (network, _, _) = roundTrip(buildNetwork())
        assertEquals("WD", network.routes[0].trips[0].serviceId)
    }

    @Test
    fun `transfers round-trip`() {
        val (network, _, _) = roundTrip(buildNetwork())
        assertEquals(1, network.transfers.size)
        assertEquals(0, network.transfers[0].fromStopId)
        assertEquals(1, network.transfers[0].toStopId)
        assertEquals(180, network.transfers[0].walkSeconds)
    }

    @Test
    fun `calendar pattern round-trip — active on weekdays inactive on Saturday`() {
        val (_, patterns, exceptions) = roundTrip(buildNetwork())
        val calendar = GtfsCalendarResolver(patterns, exceptions)
        assertTrue(calendar.isActive("WD", LocalDate.of(2026, 6, 1)))  // Monday
        assertFalse(calendar.isActive("WD", LocalDate.of(2026, 6, 6))) // Saturday
    }

    @Test
    fun `calendar exception round-trip — addition overrides pattern`() {
        val (_, patterns, exceptions) = roundTrip(buildNetwork())
        val calendar = GtfsCalendarResolver(patterns, exceptions)
        // 2026-08-01 is Saturday (not in WD pattern), but the exception adds it
        assertTrue(calendar.isActive("WD", LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun `empty network serializes and deserializes cleanly`() {
        val (network, patterns, exceptions) = roundTrip(GtfsNetworkBuilder().build(), emptyList(), emptyList())
        assertTrue(network.stops.isEmpty())
        assertTrue(network.routes.isEmpty())
        assertTrue(network.transfers.isEmpty())
        assertTrue(patterns.isEmpty())
        assertTrue(exceptions.isEmpty())
    }

    @Test
    fun `stopToRoutes derived index is rebuilt after deserialization`() {
        val (network, _, _) = roundTrip(buildNetwork())
        assertTrue(network.stopToRoutes.isNotEmpty())
        assertTrue(0 in network.stopToRoutes)
    }

    // ---- Helpers -------------------------------------------------------------

    private fun buildNetwork(): GtfsNetwork = GtfsNetworkBuilder()
        .addStop(0, "Bern", 46.948, 7.447)
        .addStop(1, "Olten", 47.352, 7.907)
        .addStop(2, "Zürich HB", 47.378, 8.540)
        .addRoute(0, "IC6", listOf(0, 1, 2))
        .addTrip(0, 10, listOf(8 * 3600, 8 * 3600 + 600, 8 * 3600 + 720, 8 * 3600 + 1500), serviceId = "WD")
        .addTrip(0, 11, listOf(9 * 3600, 9 * 3600 + 600, 9 * 3600 + 720, 9 * 3600 + 1500), serviceId = "WD")
        .addTransfer(0, 1, 180)
        .build()

    private fun roundTrip(
        network: GtfsNetwork,
        patterns: List<Map<String, String>> = weekdayPatterns,
        exceptions: List<Map<String, String>> = satAddException,
    ): Triple<GtfsNetwork, List<Map<String, String>>, List<Map<String, String>>> {
        val buf = ByteArrayOutputStream()
        GtfsNetworkSerializer.write(network, patterns, exceptions, buf)
        return GtfsNetworkSerializer.read(ByteArrayInputStream(buf.toByteArray()))
    }
}
