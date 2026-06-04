package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.LocalRoutingState
import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkStore
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsParser
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.SearchEndpoint
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class LocalTransportRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: GtfsNetworkStore
    private lateinit var repo: LocalTransportRepository

    @Before
    fun setUp() {
        val network = GtfsNetworkBuilder()
            .addStop(0, "Bern",      46.9480, 7.4474)
            .addStop(1, "Olten",     47.3520, 7.9067)
            .addStop(2, "Zürich HB", 47.3779, 8.5403)
            .addRoute(0, "IC6", listOf(0, 1, 2))
            .addTrip(0, 1, listOf(
                8 * 3600,        // Bern dep 08:00
                8 * 3600 + 1500, // Olten arr 08:25
                8 * 3600 + 1680, // Olten dep 08:28
                8 * 3600 + 3600, // Zürich arr 09:00
            ))
            .build()

        val parsed = GtfsParser.ParsedGtfs(
            network = network,
            calendar = GtfsCalendarResolver(emptyList(), emptyList()),
            calendarPatternRows = emptyList(),
            calendarExceptionRows = emptyList(),
        )

        store = GtfsNetworkStore(tmp.newFolder("gtfs"))
        store.write(parsed)
        repo = LocalTransportRepository(store)
    }

    @Test
    fun `emits NoData when store is empty`() = runTest {
        val emptyStore = GtfsNetworkStore(tmp.newFolder("empty"))
        val emptyRepo = LocalTransportRepository(emptyStore)

        val states = emptyRepo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich HB"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        assertTrue(states.any { it is LocalRoutingState.NoData })
    }

    @Test
    fun `resolves stops by name and returns connection`() = runTest {
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        val results = states.filterIsInstance<LocalRoutingState.Results>()
        assertTrue("Expected at least one Results state", results.isNotEmpty())
        val conn = results.last().connections.first()
        assertEquals("Bern", conn.departure.stationName)
        assertEquals("Zürich HB", conn.arrival.stationName)
    }

    @Test
    fun `departure and arrival times are set and in order`() = runTest {
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        val conn = states.filterIsInstance<LocalRoutingState.Results>().last().connections.first()
        assertNotNull(conn.departure.scheduledTime)
        assertNotNull(conn.arrival.scheduledTime)
        assertTrue(conn.arrival.scheduledTime!! > conn.departure.scheduledTime!!)
    }

    @Test
    fun `connection legs contain a Transit leg`() = runTest {
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        val conn = states.filterIsInstance<LocalRoutingState.Results>().last().connections.first()
        assertTrue(conn.legs.any { it is Leg.Transit })
    }

    @Test
    fun `transfers count is transit legs minus one`() = runTest {
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        val conn = states.filterIsInstance<LocalRoutingState.Results>().last().connections.first()
        val transitLegs = conn.legs.filterIsInstance<Leg.Transit>().size
        assertEquals(maxOf(0, transitLegs - 1), conn.transfers)
    }

    @Test
    fun `no results emitted when no stop matches name`() = runTest {
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Nonexistent Station XYZ"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        assertTrue(states.any { it is LocalRoutingState.NoResults })
    }

    @Test
    fun `arrive-by finds connection arriving before deadline`() = runTest {
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.ArriveBy(LocalTime.of(9, 30)),
        ).toList()

        val results = states.filterIsInstance<LocalRoutingState.Results>()
        assertTrue("Expected arrive-by to find IC6", results.isNotEmpty())
        val arrival = results.last().connections.first().arrival.scheduledTime!!
        val deadline = LocalDate.of(2026, 6, 1).atTime(9, 30)
            .atZone(ZoneId.of("Europe/Zurich")).toInstant()
        assertTrue("Arrival must be ≤ 09:30", !arrival.isAfter(deadline))
    }

    @Test
    fun `invalidate forces reload on next query`() = runTest {
        // First query loads the cache
        repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        // Invalidate and query again — should still work (reloads from same store)
        repo.invalidate()
        val states = repo.routeConnections(
            from = SearchEndpoint.NamedPlace("Bern"),
            to = SearchEndpoint.NamedPlace("Zürich"),
            date = LocalDate.of(2026, 6, 1),
            routingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0)),
        ).toList()

        assertTrue(states.any { it is LocalRoutingState.Results })
    }
}
