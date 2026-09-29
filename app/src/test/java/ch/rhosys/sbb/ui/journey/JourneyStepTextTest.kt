package ch.rhosys.sbb.ui.journey

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class JourneyStepTextTest {

    private val t0 = Instant.parse("2026-09-25T08:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    // Walk 5 min to A, S12 A→B (08:05–08:20), IC 5 B→C (08:25–09:00).
    private val connection: Connection = run {
        val s12 = Leg.Transit(
            departure = Stop("A", scheduledTime = at(5), platform = "3"),
            arrival = Stop("B", scheduledTime = at(20)),
            lineName = "S12", lineCategory = "S", direction = "B",
        )
        val ic5 = Leg.Transit(
            departure = Stop("B", scheduledTime = at(25), platform = "7"),
            arrival = Stop("C", scheduledTime = at(60)),
            lineName = "IC 5", lineCategory = "IC", direction = "C",
        )
        Connection(s12.departure, ic5.arrival, listOf(s12, ic5), 1, Duration.ofMinutes(5), Duration.ZERO)
    }
    private val segments = buildJourneyTimeline(connection)
    private fun progressAt(minutes: Long) = journeyProgress(segments, at(minutes))!!

    @Test
    fun `before the trip it says when to leave`() {
        assertEquals("Leave in 10 min", progressAt(-10).headline(at(-10)))
    }

    @Test
    fun `walking to the first stop says which train to board next`() {
        val p = progressAt(2)
        assertEquals("Walk to A", p.headline(at(2)))
        assertTrue(p.nextStep(at(2)).startsWith("Board S12 → B · Pl. 3 at "))
    }

    @Test
    fun `on a train it says where to get off`() {
        val p = progressAt(10)
        assertEquals("On S12 → B", p.headline(at(10)))
        assertTrue(p.nextStep(at(10)).startsWith("Get off at B in 10 min"))
    }

    @Test
    fun `on the last ride it names the rider's destination`() {
        val p = progressAt(40)
        assertTrue(p.nextStep(at(40), finalDestination = "Home").startsWith("Arrive Home in 20 min"))
    }

    @Test
    fun `after the trip it says you've arrived`() {
        assertEquals("You've arrived", progressAt(70).headline(at(70)))
    }
}
