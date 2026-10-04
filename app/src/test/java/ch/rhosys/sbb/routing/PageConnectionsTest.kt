package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.PageDirection
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkStore
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsParser
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Paging outward from an edge of the shown list: at most 10, nearest first, ≤ 24 h away. */
class PageConnectionsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Zurich")
    private val date = LocalDate.of(2026, 6, 2)
    private val from = SearchEndpoint.NamedPlace("Home")
    private val to = SearchEndpoint.NamedPlace("Work")

    // Home → Work (20 min) at each of [departures] (minutes after midnight), every day.
    private fun repo(departures: List<Int>): LocalTransportRepository {
        val builder = GtfsNetworkBuilder()
            .addStop(0, "Home", 47.0, 8.0)
            .addStop(1, "Work", 47.1, 8.1)
            .addRoute(0, "S1", listOf(0, 1))
        departures.forEachIndexed { i, min -> builder.addTrip(0, i, listOf(min * 60, (min + 20) * 60)) }
        val store = GtfsNetworkStore(tmp.newFolder())
        store.write(
            GtfsParser.ParsedGtfs(
                network = builder.build(),
                calendar = GtfsCalendarResolver(emptyList(), emptyList()),
                calendarPatternRows = emptyList(),
                calendarExceptionRows = emptyList(),
            )
        )
        return LocalTransportRepository(store)
    }

    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant()
    private fun List<Connection>.times() = map { it.departure.scheduledTime!!.atZone(zone).let { z -> "${z.toLocalDate().dayOfMonth} ${z.toLocalTime()}" } }

    private val everyTenMinutes = (6 * 60 until 22 * 60 step 10).toList()

    @Test
    fun `later page is the 10 departures right after the edge`() = runTest {
        val page = repo(everyTenMinutes).pageConnections(from, to, at(date, 8, 0), PageDirection.LATER)
        assertEquals(10, page.size)
        assertEquals(LocalTime.of(8, 10), page.first().departure.scheduledTime!!.atZone(zone).toLocalTime())
        assertEquals(LocalTime.of(9, 40), page.last().departure.scheduledTime!!.atZone(zone).toLocalTime())
    }

    @Test
    fun `earlier page is the 10 departures right before the edge, oldest first`() = runTest {
        val page = repo(everyTenMinutes).pageConnections(from, to, at(date, 8, 0), PageDirection.EARLIER)
        assertEquals(10, page.size)
        assertEquals(LocalTime.of(6, 20), page.first().departure.scheduledTime!!.atZone(zone).toLocalTime())
        assertEquals(LocalTime.of(7, 50), page.last().departure.scheduledTime!!.atZone(zone).toLocalTime())
    }

    @Test
    fun `a quiet route jumps to the next day's first train`() = runTest {
        // Only 07:00 and 21:00. Paging later from 21:00 crosses midnight to tomorrow's 07:00.
        val page = repo(listOf(7 * 60, 21 * 60)).pageConnections(from, to, at(date, 21, 0), PageDirection.LATER)
        assertEquals(listOf("3 07:00", "3 21:00"), page.times())
    }

    @Test
    fun `earlier page reaches back into the previous day`() = runTest {
        val page = repo(listOf(7 * 60, 21 * 60)).pageConnections(from, to, at(date, 7, 0), PageDirection.EARLIER)
        assertEquals(listOf("1 07:00", "1 21:00"), page.times())
    }

    @Test
    fun `nothing further than 24 hours from the edge`() = runTest {
        val page = repo(listOf(7 * 60)).pageConnections(from, to, at(date, 7, 0), PageDirection.LATER)
        // Tomorrow's 07:00 is exactly 24 h out — included; nothing beyond it.
        assertEquals(listOf("3 07:00"), page.times())
        assertTrue(page.all { !it.departure.scheduledTime!!.isAfter(at(date.plusDays(1), 7, 0)) })
    }
}
