package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.RequiredRun
import ch.rhosys.sbb.domain.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ConnectionTest {

    // Two transit legs with a modeled 5-minute Walk leg between them (a cross-platform
    // transfer at "Talweissenstrasse"), scheduled with a 10-minute buffer. Real-time
    // delay on the incoming leg is applied via Stop.delayMinutes.
    private fun buildConnection(delayMinutes: Int): Connection {
        val base = Instant.parse("2026-09-17T20:00:00Z")
        val legs = listOf(
            Leg.Transit(
                departure = Stop(stationName = "X", scheduledTime = base.minusSeconds(600)),
                arrival = Stop(stationName = "Talweissenstrasse", scheduledTime = base, delayMinutes = delayMinutes),
                lineName = "9",
                lineCategory = "",
                direction = "Heuried",
            ),
            Leg.Walk(fromName = "Talweissenstrasse", toName = "Talweissenstrasse", durationMinutes = 5),
            Leg.Transit(
                departure = Stop(stationName = "Talweissenstrasse", scheduledTime = base.plusSeconds(600)),
                arrival = Stop(stationName = "Y", scheduledTime = base.plusSeconds(1200)),
                lineName = "14",
                lineCategory = "",
                direction = "Stauffacher",
            ),
        )
        return Connection(
            departure = (legs.first() as Leg.Transit).departure,
            arrival = (legs.last() as Leg.Transit).arrival,
            legs = legs,
            transfers = 1,
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )
    }

    @Test
    fun `ample buffer does not require running`() {
        val info = buildConnection(delayMinutes = 0).transferInfos.first()
        assertFalse(info.requiresRunning(walkingPaceKmh = 6f, runningPaceKmh = 10f))
    }

    @Test
    fun `delay that eats into the walk time but not the run time requires running`() {
        // Effective buffer 10-6=4min; walking the 5-min transfer no longer fits, but
        // running it (5 * 6/10 = 3min) still does.
        val info = buildConnection(delayMinutes = 6).transferInfos.first()
        assertTrue(info.requiresRunning(walkingPaceKmh = 6f, runningPaceKmh = 10f))
    }

    @Test
    fun `delay past even the run time does not flag requiresRunning, only isAtRisk`() {
        // Effective buffer 10-8=2min, below the 3-min run time -- unmakeable either way.
        val info = buildConnection(delayMinutes = 8).transferInfos.first()
        assertFalse(info.requiresRunning(walkingPaceKmh = 6f, runningPaceKmh = 10f))
        assertTrue(info.isAtRisk)
    }

    @Test
    fun `same-stop transfer with no walk leg never requires running`() {
        val base = Instant.parse("2026-09-17T20:00:00Z")
        val legs = listOf(
            Leg.Transit(
                departure = Stop(stationName = "X", scheduledTime = base.minusSeconds(600)),
                arrival = Stop(stationName = "Stauffacher", scheduledTime = base, delayMinutes = 9),
                lineName = "9",
                lineCategory = "",
                direction = "Heuried",
            ),
            Leg.Transit(
                departure = Stop(stationName = "Stauffacher", scheduledTime = base.plusSeconds(600)),
                arrival = Stop(stationName = "Y", scheduledTime = base.plusSeconds(1200)),
                lineName = "2",
                lineCategory = "",
                direction = "Stadelhofen",
            ),
        )
        val connection = Connection(
            departure = (legs.first() as Leg.Transit).departure,
            arrival = (legs.last() as Leg.Transit).arrival,
            legs = legs,
            transfers = 1,
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )
        val info = connection.transferInfos.first()
        assertFalse(info.requiresRunning(walkingPaceKmh = 6f, runningPaceKmh = 10f))
    }

    @Test
    fun `longest required run picks the transfer run when leaving now is walkable`() {
        val connection = buildConnection(delayMinutes = 6)
        val now = connection.departure.effectiveTime!!.minusSeconds(3600)
        val run = connection.longestRequiredRun(now, walkingPaceKmh = 6f, runningPaceKmh = 10f)
        assertEquals(RequiredRun("Talweissenstrasse", 3), run)
    }

    @Test
    fun `no required run when everything can be walked`() {
        val connection = buildConnection(delayMinutes = 0)
        val now = connection.departure.effectiveTime!!.minusSeconds(3600)
        assertNull(connection.longestRequiredRun(now, walkingPaceKmh = 6f, runningPaceKmh = 10f))
    }

    @Test
    fun `walk to first stop requires running once it no longer fits at walking pace`() {
        // 10-minute walk to the first stop, run takes 6 min at 6 vs 10 km/h.
        val connection = buildConnection(delayMinutes = 0).copy(walkToFirstStop = Duration.ofMinutes(10))
        val dep = connection.departure.effectiveTime!!
        assertFalse(connection.requiresRunningToFirstStop(dep.minusSeconds(11 * 60), 6f, 10f))
        assertTrue(connection.requiresRunningToFirstStop(dep.minusSeconds(8 * 60), 6f, 10f))
        assertFalse(connection.requiresRunningToFirstStop(dep.minusSeconds(5 * 60), 6f, 10f))
        assertEquals(
            RequiredRun("X", 6),
            connection.longestRequiredRun(dep.minusSeconds(8 * 60), 6f, 10f),
        )
    }
}
