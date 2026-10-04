package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.algorithm.ExpectedDelayProvider
import ch.rhosys.sbb.data.local.routing.algorithm.FoundConnection
import ch.rhosys.sbb.data.local.routing.algorithm.FoundLeg
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingEngine
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingQuery
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork
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
 * Range search, the elimination rule (JourneyCriteria) and transfer slack / expected delay.
 *
 * Stops: 0=Home, 1=Hub, 2=Work, 3=Detour (one bus stop past Home, the wrong way).
 */
class RangeRoutingTest {

    private fun h(hour: Int, min: Int = 0) = hour * 3600 + min * 60

    private fun query(
        at: RoutingTime,
        window: Duration = Duration.ofMinutes(60),
        from: Int = 0,
        to: Int = 2,
    ) = RoutingQuery(
        originStopIds = listOf(from),
        destinationStopIds = listOf(to),
        date = LocalDate.of(2026, 6, 1),
        routingTime = at,
        walkToFirstStop = Duration.ZERO,
        walkFromLastStop = Duration.ZERO,
        window = window,
    )

    private suspend fun RoutingEngine.connections(q: RoutingQuery): List<FoundConnection> =
        route(q).toList().lastOrNull()?.connections.orEmpty()

    // Direct train Home → Work every 30 min from 08:00 (25 min ride).
    private fun hourlyNetwork(): GtfsNetwork = GtfsNetworkBuilder()
        .addStop(0, "Home", 47.0, 8.0)
        .addStop(1, "Hub", 47.01, 8.01)
        .addStop(2, "Work", 47.02, 8.02)
        .addRoute(0, "S1", listOf(0, 2))
        .apply { for (i in 0 until 6) addTrip(0, i, listOf(h(8, 30 * i), h(8, 30 * i + 25))) }
        .build()

    @Test
    fun `every departure in the window is returned, not just the fastest`() = runTest {
        val found = RoutingEngine(hourlyNetwork()).connections(query(RoutingTime.DepartAfter(LocalTime.of(8, 0))))
        assertEquals(listOf(h(8, 0), h(8, 30), h(9, 0)), found.map { it.departureSeconds })
    }

    @Test
    fun `window opens at the first departure after a quiet period`() = runTest {
        val found = RoutingEngine(hourlyNetwork()).connections(query(RoutingTime.DepartAfter(LocalTime.of(5, 0))))
        assertEquals(h(8, 0), found.first().departureSeconds)
    }

    @Test
    fun `DepartBetween returns exactly the departures in its range`() = runTest {
        val found = RoutingEngine(hourlyNetwork()).connections(
            query(RoutingTime.DepartBetween(LocalTime.of(8, 0), LocalTime.of(8, 59, 59))),
        )
        assertEquals(listOf(h(8, 0), h(8, 30)), found.map { it.departureSeconds })
    }

    @Test
    fun `arrive-by returns the window of departures before the deadline, not only the latest`() = runTest {
        val found = RoutingEngine(hourlyNetwork()).connections(query(RoutingTime.ArriveBy(LocalTime.of(10, 0))))
        assertTrue(found.all { it.arrivalSeconds <= h(10) })
        assertEquals(listOf(h(8, 30), h(9, 0), h(9, 30)), found.map { it.departureSeconds })
    }

    @Test
    fun `riding one stop the wrong way and walking back is eliminated`() = runTest {
        // Bus B: Home 07:50 → Detour 07:53. Walk Detour → Home 3 min. Train S1 Home 08:00 → Work.
        // "Bus to Detour, walk back, take S1" leaves earlier and arrives no sooner than just
        // taking S1 — dominated.
        val network = GtfsNetworkBuilder()
            .addStop(0, "Home", 47.0, 8.0)
            .addStop(1, "Hub", 47.01, 8.01)
            .addStop(2, "Work", 47.02, 8.02)
            .addStop(3, "Detour", 47.001, 8.001)
            .addRoute(0, "S1", listOf(0, 2))
            .addTrip(0, 0, listOf(h(8), h(8, 25)))
            .addRoute(1, "B", listOf(0, 3))
            .addTrip(1, 1, listOf(h(7, 50), h(7, 53)))
            .addTransfer(3, 0, 300.0)
            .build()
        val found = RoutingEngine(network).connections(query(RoutingTime.DepartAfter(LocalTime.of(7, 45))))
        assertEquals(1, found.size)
        assertEquals(listOf("S1"), found.single().legs.filterIsInstance<FoundLeg.Transit>().map { it.routeName })
    }

    @Test
    fun `slower direct train survives a faster journey with a change`() = runTest {
        // Direct D: Home 08:00 → Work 09:00. Change: Home 08:10 → Hub 08:20, Hub 08:25 → Work 08:55.
        val network = GtfsNetworkBuilder()
            .addStop(0, "Home", 47.0, 8.0)
            .addStop(1, "Hub", 47.01, 8.01)
            .addStop(2, "Work", 47.02, 8.02)
            .addRoute(0, "D", listOf(0, 2)).addTrip(0, 0, listOf(h(8), h(9)))
            .addRoute(1, "A", listOf(0, 1)).addTrip(1, 1, listOf(h(8, 10), h(8, 20)))
            .addRoute(2, "C", listOf(1, 2)).addTrip(2, 2, listOf(h(8, 25), h(8, 55)))
            .build()
        val found = RoutingEngine(network).connections(query(RoutingTime.DepartAfter(LocalTime.of(8, 0))))
        assertEquals(listOf(h(8, 0), h(8, 10)), found.map { it.departureSeconds })
    }

    // Home → Hub on A (08:00 → 08:20, or 07:50 → 08:10); Hub → Work on C at 08:21 only.
    // Taking the 08:00 A leaves 1 min at Hub; the 07:50 A leaves 11 min for the same C.
    private fun tightChangeNetwork(): GtfsNetwork = GtfsNetworkBuilder()
        .addStop(0, "Home", 47.0, 8.0)
        .addStop(1, "Hub", 47.01, 8.01)
        .addStop(2, "Work", 47.02, 8.02)
        .addRoute(0, "A", listOf(0, 1))
        .addTrip(0, 0, listOf(h(7, 50), h(8, 10)))
        .addTrip(0, 1, listOf(h(8, 0), h(8, 20)))
        .addRoute(1, "C", listOf(1, 2))
        .addTrip(1, 2, listOf(h(8, 21), h(8, 50)))
        .build()

    @Test
    fun `a comfortable earlier change is kept alongside a tight later one`() = runTest {
        val found = RoutingEngine(tightChangeNetwork()).connections(query(RoutingTime.DepartAfter(LocalTime.of(7, 45))))
        assertEquals(listOf(h(7, 50), h(8, 0)), found.map { it.departureSeconds })
        assertEquals(listOf(11 * 60, 60), found.map { it.tightestTransferSlackSeconds })
    }

    @Test
    fun `an expected delay on the incoming leg rules out a tight change`() = runTest {
        // The 08:00 A is expected 2 min late at Hub — arriving 08:22, after C leaves.
        val delays = ExpectedDelayProvider { _, trip, _ -> if (trip.id == 1) 120 else 0 }
        val found = RoutingEngine(tightChangeNetwork(), expectedDelays = delays)
            .connections(query(RoutingTime.DepartAfter(LocalTime.of(7, 45))))
        assertEquals(listOf(h(7, 50)), found.map { it.departureSeconds })
    }

    @Test
    fun `expected delay is carried on the leg`() = runTest {
        val delays = ExpectedDelayProvider { _, _, _ -> 90 }
        val found = RoutingEngine(hourlyNetwork(), expectedDelays = delays)
            .connections(query(RoutingTime.DepartAfter(LocalTime.of(8, 0))))
        assertEquals(90, (found.first().legs.single() as FoundLeg.Transit).expectedDelaySeconds)
    }
}
