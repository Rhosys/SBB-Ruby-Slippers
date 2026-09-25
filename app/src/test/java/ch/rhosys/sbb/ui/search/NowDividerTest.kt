package ch.rhosys.sbb.ui.search

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class NowDividerTest {

    private fun departingAt(time: Instant): Connection {
        val leg = Leg.Transit(
            departure = Stop(stationName = "A", scheduledTime = time),
            arrival = Stop(stationName = "B", scheduledTime = time.plusSeconds(600)),
            lineName = "S12",
            lineCategory = "S",
            direction = "B",
        )
        return Connection(leg.departure, leg.arrival, listOf(leg), 0, Duration.ZERO, Duration.ZERO)
    }

    @Test
    fun `a trip that departed 3 minutes ago is shown above the NOW divider`() {
        val now = Instant.parse("2026-09-25T08:00:00Z")
        val justLeft = departingAt(now.minusSeconds(180))
        val upcoming = departingAt(now.plusSeconds(300))

        val rows = buildRowsWithNowDivider(listOf(justLeft, upcoming), now)

        assertEquals(3, rows.size)
        assertEquals(justLeft, (rows[0] as ConnectionListRow.ConnectionRow).connection)
        assertEquals(ConnectionListRow.NowDivider, rows[1])
        assertEquals(upcoming, (rows[2] as ConnectionListRow.ConnectionRow).connection)
    }
}
