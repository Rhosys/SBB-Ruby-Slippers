package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.COMFORTABLE_TRANSFER_SLACK_SECONDS
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.JourneyCriteria
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import ch.rhosys.sbb.domain.model.paretoOptimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class JourneyCriteriaTest {

    private val base = Instant.parse("2026-09-25T08:00:00Z")
    private fun at(min: Long) = base.plusSeconds(min * 60)

    private fun leg(dep: Long, arr: Long, line: String, arrDelay: Int = 0, expectedDelay: Int? = null) =
        Leg.Transit(
            departure = Stop("X", scheduledTime = at(dep)),
            arrival = Stop("Y", scheduledTime = at(arr), delayMinutes = arrDelay),
            lineName = line, lineCategory = "", direction = "",
        ).let { if (expectedDelay != null) it.copy(expectedDelayMinutes = expectedDelay) else it }

    private fun connection(vararg legs: Leg.Transit) =
        Connection(legs.first().departure, legs.last().arrival, legs.toList(), legs.size - 1, Duration.ZERO, Duration.ZERO)

    private fun criteria(dep: Long, arr: Long, transfers: Int = 0, slack: Long = COMFORTABLE_TRANSFER_SLACK_SECONDS) =
        JourneyCriteria(dep, arr, transfers, slack)

    @Test
    fun `leaving earlier and arriving no sooner is dominated`() {
        assertTrue(criteria(10, 50).dominates(criteria(0, 50)))
    }

    @Test
    fun `fewer transfers or more slack keeps a slower connection`() {
        assertFalse(criteria(10, 40, transfers = 1).dominates(criteria(0, 50, transfers = 0)))
        assertFalse(criteria(10, 40, slack = 60).dominates(criteria(0, 50, slack = 300)))
    }

    @Test
    fun `identical criteria never knock each other out`() {
        assertFalse(criteria(0, 50).dominates(criteria(0, 50)))
    }

    @Test
    fun `slack is measured after the incoming leg's expected delay`() {
        val tight = connection(leg(0, 20, "A", expectedDelay = 3), leg(25, 50, "C"))
        assertEquals(2, tight.transferInfos.single().slackMinutes)
        assertEquals(120L, tight.criteria!!.transferSlackSeconds)
    }

    @Test
    fun `real-time arrival delay is the default expected delay`() {
        assertEquals(4, leg(0, 20, "A", arrDelay = 4).expectedDelayMinutes)
    }

    @Test
    fun `a late last leg pushes the ranked arrival back`() {
        val c = connection(leg(0, 20, "A", expectedDelay = 5))
        assertEquals(at(25).epochSecond, c.criteria!!.arrivalEpochSeconds)
    }

    @Test
    fun `paretoOptimal drops only dominated connections`() {
        val direct = connection(leg(0, 60, "D"))
        val faster = connection(leg(10, 20, "A"), leg(25, 55, "C"))
        val detour = connection(leg(-10, 20, "B"), leg(25, 55, "C"))  // leaves earlier, same arrival, same changes
        assertEquals(listOf(direct, faster), listOf(direct, faster, detour).paretoOptimal { it.criteria })
    }
}
