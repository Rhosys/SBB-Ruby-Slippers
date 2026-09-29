package ch.rhosys.sbb.ui.search

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ConnectionListTest {

    private val base = Instant.parse("2026-09-25T08:00:00Z")

    private fun departingAt(minutes: Long, delayMinutes: Int = 0): Connection {
        val time = base.plusSeconds(minutes * 60)
        val leg = Leg.Transit(
            departure = Stop(stationName = "A", scheduledTime = time, delayMinutes = delayMinutes),
            arrival = Stop(stationName = "B", scheduledTime = time.plusSeconds(600)),
            lineName = "S12",
            lineCategory = "S",
            direction = "B",
        )
        return Connection(leg.departure, leg.arrival, listOf(leg), 0, Duration.ZERO, Duration.ZERO)
    }

    private fun List<Connection>.minutes() = map { Duration.between(base, it.departure.scheduledTime).toMinutes() }

    @Test
    fun `earlier connections are merged in above, sorted by departure`() {
        val merged = mergeConnections(listOf(departingAt(10), departingAt(20)), listOf(departingAt(0), departingAt(-10)))
        assertEquals(listOf(-10L, 0L, 10L, 20L), merged.minutes())
    }

    @Test
    fun `a connection already shown is not added twice, even with a new delay`() {
        val merged = mergeConnections(listOf(departingAt(10)), listOf(departingAt(10, delayMinutes = 3), departingAt(30)))
        assertEquals(listOf(10L, 30L), merged.minutes())
    }

    @Test
    fun `a trip that departed 3 minutes ago is shown above the NOW line`() {
        assertEquals(1, nowIndex(listOf(departingAt(-3), departingAt(5)), base))
    }

    @Test
    fun `a delayed trip that hasn't left yet is below the NOW line`() {
        assertEquals(0, nowIndex(listOf(departingAt(-3, delayMinutes = 5), departingAt(5)), base))
    }

    @Test
    fun `NOW sits below the results when every trip has left`() {
        assertEquals(2, nowIndex(listOf(departingAt(-20), departingAt(-10)), base))
    }
}
