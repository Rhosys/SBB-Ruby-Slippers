package ch.rhosys.sbb.data.local.db.entity

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StoredConnectionTest {

    private val t0 = Instant.parse("2026-11-26T07:02:00Z")

    private val connection = Connection(
        departure = Stop("Zürich HB", "8503000", t0, delayMinutes = 2, platform = "7"),
        arrival = Stop("Lausanne", "8501120", t0.plusSeconds(9000)),
        legs = listOf(
            Leg.Transit(
                departure = Stop("Zürich HB", "8503000", t0, delayMinutes = 2, platform = "7"),
                arrival = Stop("Bern", "8507000", t0.plusSeconds(3360), platform = "8"),
                lineName = "IC 1",
                lineCategory = "IC",
                direction = "Genève-Aéroport",
                operator = "SBB",
                tripNumber = "721",
                intermediateStops = listOf(Stop("Olten", scheduledTime = t0.plusSeconds(1800))),
                expectedDelayMinutes = 3,
            ),
            Leg.Walk("Bern", "Bern", durationMinutes = 4),
            Leg.Transit(
                departure = Stop("Bern", "8507000", t0.plusSeconds(4200), platform = "5", isCancelled = true),
                arrival = Stop("Lausanne", "8501120", t0.plusSeconds(9000)),
                lineName = "IR 15",
                lineCategory = "IR",
                direction = "Lausanne",
            ),
        ),
        transfers = 1,
        walkToFirstStop = Duration.ofMinutes(6),
        walkFromLastStop = Duration.ofMinutes(3),
    )

    @Test
    fun roundTripsEveryField() {
        assertEquals(connection, connection.toStoredJson().toStoredConnectionOrNull())
    }

    @Test
    fun unreadableRowShowsNoDetailsInsteadOfFailing() {
        assertNull("{not json".toStoredConnectionOrNull())
    }
}
