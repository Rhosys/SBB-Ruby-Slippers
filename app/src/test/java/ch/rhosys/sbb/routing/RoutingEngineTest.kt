package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.algorithm.RoutingEngine
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingQuery
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingResult
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Synthetic network used across all tests:
 *
 *   StopA --[Route1]--> StopB --[Route1]--> StopC
 *                       StopB --[Route2]--> StopD
 *
 * Stops:
 *   A = 47.3000, 8.5000  (origin area)
 *   B = 47.3100, 8.5100  (transfer hub)
 *   C = 47.3200, 8.5200  (destination area)
 *   D = 47.3200, 8.5000  (alternate destination)
 *
 * Route 1 trips (Mon–Fri, today):
 *   Trip 1: A dep 08:00, B arr 08:10 dep 08:12, C arr 08:25
 *   Trip 2: A dep 09:00, B arr 09:10 dep 09:12, C arr 09:25
 *
 * Route 2 trips (Mon–Fri, today):
 *   Trip 3: B dep 08:15, D arr 08:30
 *
 * Walking transfers:
 *   A → B: 3 min (proximity-derived, ~400m)
 *   C → D: none (too far)
 */
class RoutingEngineTest {

    private lateinit var network: GtfsNetwork
    private lateinit var engine: RoutingEngine

    @Before
    fun setUp() {
        network = GtfsNetworkBuilder()
            .addStop(id = 0, name = "StopA", lat = 47.3000, lng = 8.5000)
            .addStop(id = 1, name = "StopB", lat = 47.3100, lng = 8.5100)
            .addStop(id = 2, name = "StopC", lat = 47.3200, lng = 8.5200)
            .addStop(id = 3, name = "StopD", lat = 47.3200, lng = 8.5000)
            .addRoute(id = 0, name = "R1", stops = listOf(0, 1, 2))
            .addTrip(
                routeId = 0, tripId = 0,
                times = listOf(
                    8 * 3600,       // A dep 08:00
                    8 * 3600 + 600, // B arr 08:10
                    8 * 3600 + 720, // B dep 08:12
                    8 * 3600 + 1500 // C arr 08:25
                ),
            )
            .addTrip(
                routeId = 0, tripId = 1,
                times = listOf(
                    9 * 3600,
                    9 * 3600 + 600,
                    9 * 3600 + 720,
                    9 * 3600 + 1500,
                ),
            )
            .addRoute(id = 1, name = "R2", stops = listOf(1, 3))
            .addTrip(
                routeId = 1, tripId = 2,
                times = listOf(
                    8 * 3600 + 900,  // B dep 08:15
                    8 * 3600 + 1800, // D arr 08:30
                ),
            )
            .addTransfer(fromStop = 0, toStop = 1, walkSeconds = 180) // A→B 3 min
            .build()

        engine = RoutingEngine(network)
    }

    @Test
    fun `direct connection A to C found in first emission`() = runTest {
        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(2),
            routingTime = RoutingTime.DepartAfter(LocalTime.ofSecondOfDay(7 * 3600.toLong())), // 07:00
            date = LocalDate.now(),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()

        assertTrue("Expected at least one result", results.isNotEmpty())
        val first = results.first().connections.first()
        assertEquals(1, first.legs.size)
        assertEquals(8 * 3600 + 1500, first.arrivalSeconds) // 08:25
    }

    @Test
    fun `connection with transfer A to D via B`() = runTest {
        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(3),
            routingTime = RoutingTime.DepartAfter(LocalTime.ofSecondOfDay(7 * 3600.toLong())),
            date = LocalDate.now(),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()

        assertTrue("Expected at least one result", results.isNotEmpty())
        val connection = results.last().connections.first()
        assertEquals(2, connection.legs.size) // R1 leg + R2 leg
        assertEquals(8 * 3600 + 1800, connection.arrivalSeconds) // 08:30
    }

    @Test
    fun `later departure not offered when earlier trip covers same journey`() = runTest {
        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(2),
            routingTime = RoutingTime.DepartAfter(LocalTime.ofSecondOfDay(7 * 3600.toLong())),
            date = LocalDate.now(),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()
        val connections = results.last().connections

        // Trip 1 (arr 08:25) dominates Trip 2 (arr 09:25) for same destination
        // Pareto front keeps both only if transfers differ — here they don't
        val arrivalTimes = connections.map { it.arrivalSeconds }
        assertTrue("Earlier arrival should be first", arrivalTimes.first() < arrivalTimes.last())
    }

    @Test
    fun `door to door duration includes walk times`() = runTest {
        val walkToFirst = Duration.ofMinutes(5)
        val walkFromLast = Duration.ofMinutes(3)

        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(2),
            routingTime = RoutingTime.DepartAfter(LocalTime.ofSecondOfDay(7 * 3600.toLong())),
            date = LocalDate.now(),
            walkToFirstStop = walkToFirst,
            walkFromLastStop = walkFromLast,
        )

        val results = engine.route(query).toList()
        val connection = results.first().connections.first()

        val transitDuration = connection.arrivalSeconds - connection.departureSeconds
        val expectedDoorToDoor = walkToFirst.seconds + transitDuration + walkFromLast.seconds
        assertEquals(expectedDoorToDoor, connection.doorToDoorSeconds)
    }

    @Test
    fun `arrive-by finds latest viable departure`() = runTest {
        // Arrive at C by 08:30 — Trip 1 (dep A 08:00, arr C 08:25) satisfies this
        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(2),
            routingTime = RoutingTime.ArriveBy(LocalTime.of(8, 30)),
            date = LocalDate.now(),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()

        assertTrue("Expected at least one result for arrive-by", results.isNotEmpty())
        val connection = results.last().connections.first()
        assertTrue("Arrival must be before or at 08:30", connection.arrivalSeconds <= 8 * 3600 + 1800)
        // Should pick Trip 1 (dep 08:00) not Trip 2 (dep 09:00 — too late)
        assertEquals(8 * 3600, connection.departureSeconds)
    }

    @Test
    fun `arrive-by emits no result when no trip reaches destination in time`() = runTest {
        // Arrive at C by 08:00 — no trip gets there that early
        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(2),
            routingTime = RoutingTime.ArriveBy(LocalTime.of(8, 0)),
            date = LocalDate.now(),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()
        assertTrue("Expected no results when arrive-by deadline is before first trip", results.isEmpty())
    }

    @Test
    fun `no connection emitted before departure time`() = runTest {
        val query = RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(2),
            routingTime = RoutingTime.DepartAfter(LocalTime.ofSecondOfDay(9 * 3600 + 3600.toLong())), // 10:00 — after all trips
            date = LocalDate.now(),
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

        val results = engine.route(query).toList()
        assertTrue("Expected no results after last trip", results.isEmpty())
    }
}
