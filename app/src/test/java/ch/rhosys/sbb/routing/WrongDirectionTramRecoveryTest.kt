package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.algorithm.FoundLeg
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingEngine
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingQuery
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Real-world scenario reported on Thu 17 Sep 2026: rider is on tram 9 at
 * Talweissenstrasse heading the wrong way (towards Heuried). Correct recovery is to
 * get off at 21:01, cross to the opposite-direction platform -- a real walk, not an
 * instant same-spot transfer: transfers are modelled as a *distance* (500 m, roughly
 * a busy intersection crossing) and converted to time via the rider's configured
 * walking pace, which takes 5 minutes at the app's default 6 km/h -- then board tram
 * 14 the other way at 21:06, ride to Stauffacher, catch the *early* tram 2 departing
 * 21:09 (a later 21:15 tram 2 also exists but is too late), arrive Stadelhofen,
 * Bahnhof at 21:19, and make the 21:20 Stadelhofen -> Winterthur train with one
 * minute to spare.
 *
 * Stop ids: 0=Talweissenstrasse (Heuried-bound platform), 1=Heuried,
 * 2=Talweissenstrasse (city-bound platform, across the road from stop 0),
 * 3=Stauffacher, 4=Stadelhofen Bahnhof, 5=Winterthur.
 */
class WrongDirectionTramRecoveryTest {

    private fun buildNetwork() = GtfsNetworkBuilder()
        .addStop(id = 0, name = "Talweissenstrasse", lat = 47.3745, lng = 8.5216)
        .addStop(id = 1, name = "Heuried", lat = 47.3696, lng = 8.5122)
        .addStop(id = 2, name = "Talweissenstrasse", lat = 47.3746, lng = 8.5217)
        .addStop(id = 3, name = "Stauffacher", lat = 47.3737, lng = 8.5292)
        .addStop(id = 4, name = "Stadelhofen, Bahnhof", lat = 47.3667, lng = 8.5483)
        .addStop(id = 5, name = "Winterthur", lat = 47.5001, lng = 8.7241)
        // Tram 9, wrong direction: arrives Talweissenstrasse (stop 0) 21:01, continues to
        // Heuried -- a dead end here, modelling that staying aboard never reaches
        // Winterthur in time.
        .addRoute(id = 0, name = "9", stops = listOf(0, 1))
        .addTrip(routeId = 0, tripId = 0, serviceId = "WEEKDAY", times = listOf(20 * 3600 + 3300, 21 * 3600 + 60))
        // Crossing the street to the opposite platform (stop 0 -> stop 2) is 500 m --
        // a busy intersection, not an instant same-spot transfer -- which takes 5
        // minutes at the app's default 6 km/h walking pace.
        .addTransfer(fromStop = 0, toStop = 2, distanceMeters = 500.0)
        // Tram 14, the other direction: Talweissenstrasse (stop 2) dep 21:06 -> Stauffacher arr 21:08.
        .addRoute(id = 1, name = "14", stops = listOf(2, 3))
        .addTrip(
            routeId = 1, tripId = 1, serviceId = "WEEKDAY",
            times = listOf(21 * 3600 + 360, 21 * 3600 + 480),
        )
        // Tram 2 from Stauffacher to Stadelhofen: an early trip (dep 21:09, arr 21:19 --
        // makes the train) and a later one (dep 21:15, arr 21:25 -- misses it). Earliest-
        // arrival routing must prefer the early one, not just any tram 2.
        .addRoute(id = 2, name = "2", stops = listOf(3, 4))
        .addTrip(
            routeId = 2, tripId = 2, serviceId = "WEEKDAY",
            times = listOf(21 * 3600 + 540, 21 * 3600 + 1140), // dep 21:09, arr 21:19
        )
        .addTrip(
            routeId = 2, tripId = 3, serviceId = "WEEKDAY",
            times = listOf(21 * 3600 + 900, 21 * 3600 + 1500), // dep 21:15, arr 21:25
        )
        // S-Bahn Stadelhofen -> Winterthur, dep 21:20, arr 21:45 -- a one-minute
        // connection off the 21:19 tram 2 arrival.
        .addRoute(id = 3, name = "S-Bahn", stops = listOf(4, 5))
        .addTrip(
            routeId = 3, tripId = 4, serviceId = "WEEKDAY",
            times = listOf(21 * 3600 + 1200, 21 * 3600 + 2700), // dep 21:20, arr 21:45
        )
        .build()

    private fun buildWeekdayCalendar() = GtfsCalendarResolver(
        patterns = listOf(
            mapOf(
                "service_id" to "WEEKDAY",
                "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                "friday" to "1", "saturday" to "0", "sunday" to "0",
                "start_date" to "20260101", "end_date" to "20261231",
            ),
        ),
        exceptions = emptyList(),
    )

    @Test
    fun `recovery from wrong-direction tram 9 catches the 21_20 Winterthur train`() = runTest {
        val engine = RoutingEngine(buildNetwork(), buildWeekdayCalendar())

        val query = RoutingQuery(
            originStopIds = listOf(0),   // Talweissenstrasse, wrong-direction platform, right after getting off tram 9
            destinationStopIds = listOf(5), // Winterthur
            date = LocalDate.of(2026, 9, 17), // Thursday -- WEEKDAY service must be active
            routingTime = RoutingTime.DepartAfter(LocalTime.of(21, 0)),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()
        assertTrue("Expected a connection to Winterthur", results.isNotEmpty())

        val connection = results.last().connections.first()
        val walkLegs = connection.legs.filterIsInstance<FoundLeg.Walk>()
        val transitLegs = connection.legs.filterIsInstance<FoundLeg.Transit>()

        assertEquals("Expected the cross-platform walk to appear as its own leg",
            1, walkLegs.size)
        assertEquals("Crossing to the opposite platform starts at the wrong-direction stop",
            0, walkLegs.first().fromStopId)
        assertEquals("Crossing to the opposite platform ends at the correct-direction stop",
            2, walkLegs.first().toStopId)
        assertEquals("Crossing 500m at the default 6 km/h pace takes 5 minutes, not an instant transfer",
            300, walkLegs.first().durationSeconds)

        assertEquals("Expected exactly 3 transit legs (14, 2, S-Bahn) -- not via tram 9",
            3, transitLegs.size)
        assertEquals("14", transitLegs[0].routeName)
        assertEquals("2", transitLegs[1].routeName)
        assertEquals("S-Bahn", transitLegs[2].routeName)

        // Tram 14: Talweissenstrasse (opposite platform) 21:06 -> Stauffacher 21:08.
        assertEquals(21 * 3600 + 360, transitLegs[0].boardSeconds)
        assertEquals(21 * 3600 + 480, transitLegs[0].alightSeconds)

        // Tram 2: must be the EARLY 21:09 departure, not the 21:15 one.
        assertEquals("Must board the early tram 2, not the later 21:15 one",
            21 * 3600 + 540, transitLegs[1].boardSeconds)
        assertEquals(21 * 3600 + 1140, transitLegs[1].alightSeconds) // arr Stadelhofen 21:19

        // S-Bahn: Stadelhofen 21:20 -> Winterthur 21:45 -- a 1-minute transfer off the tram.
        assertEquals(21 * 3600 + 1200, transitLegs[2].boardSeconds)
        assertEquals(21 * 3600 + 2700, transitLegs[2].alightSeconds)

        val transferBufferSeconds = transitLegs[2].boardSeconds - transitLegs[1].alightSeconds
        assertEquals("Transfer at Stadelhofen should be exactly 1 minute", 60, transferBufferSeconds)
    }
}
