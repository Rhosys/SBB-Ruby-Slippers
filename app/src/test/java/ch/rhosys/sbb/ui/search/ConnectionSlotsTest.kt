package ch.rhosys.sbb.ui.search

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Stop
import ch.rhosys.sbb.ui.search.ConnectionSlots.Companion.START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ConnectionSlotsTest {

    private val base = Instant.parse("2026-09-25T08:00:00Z")

    private fun departingAt(minutes: Long): Connection {
        val time = base.plusSeconds(minutes * 60)
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
    fun `a new search fills from the start slot in departure order`() {
        val a = departingAt(10); val b = departingAt(20)
        val slots = ConnectionSlots.startingWith(listOf(b, a))

        assertEquals(START, slots.first)
        assertEquals(START + 1, slots.last)
        assertSame(a, slots[START])
        assertSame(b, slots[START + 1])
        assertNull(slots[START - 1])
    }

    @Test
    fun `earlier connections go directly above the first without moving it`() {
        val first = departingAt(10)
        val slots = ConnectionSlots.startingWith(listOf(first, departingAt(20)))
            .withEarlier(listOf(departingAt(-10), departingAt(0), first))

        assertEquals(START - 2, slots.first)
        assertEquals(base.minusSeconds(600), slots[START - 2]!!.departure.scheduledTime)
        assertEquals(base, slots[START - 1]!!.departure.scheduledTime)
        assertSame(first, slots[START])
    }

    @Test
    fun `later connections go directly below the last and skip ones already shown`() {
        val last = departingAt(20)
        val slots = ConnectionSlots.startingWith(listOf(departingAt(10), last))
            .withLater(listOf(last, departingAt(30)))

        assertEquals(START + 2, slots.last)
        assertSame(last, slots[START + 1])
        assertEquals(base.plusSeconds(1800), slots[START + 2]!!.departure.scheduledTime)
    }

    @Test
    fun `nothing new leaves the slots unchanged`() {
        val slots = ConnectionSlots.startingWith(listOf(departingAt(10)))
        assertSame(slots, slots.withEarlier(listOf(departingAt(10))))
        assertSame(slots, slots.withLater(emptyList()))
    }

    @Test
    fun `a trip that departed 3 minutes ago is shown above the NOW line`() {
        val now = base
        val slots = ConnectionSlots.startingWith(listOf(departingAt(-3), departingAt(5)))
        assertEquals(START + 1, slots.nowIndex(now))
    }

    @Test
    fun `NOW sits below the results when every trip has left`() {
        val slots = ConnectionSlots.startingWith(listOf(departingAt(-20), departingAt(-10)))
        assertEquals(START + 2, slots.nowIndex(base))
    }
}
