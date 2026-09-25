package ch.rhosys.sbb.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

// Part 3 of the Home → Search reset work: which time a search asks the router/API for.
// "now" is always passed in, so these never depend on the real clock.
class SearchTimeModeTest {

    private fun at(text: String): LocalDateTime = LocalDateTime.parse(text)

    // --- "Now" vs fixed time ---

    @Test
    fun `default time mode is Now and its label is Now`() {
        val mode: SearchTimeMode = SearchTimeMode.Now
        assertEquals("Now", mode.label())
    }

    @Test
    fun `in Now mode each query resolves the clock at query time`() {
        val mode = SearchTimeMode.Now
        assertEquals(at("2026-09-25T09:55"), mode.queryDateTime(at("2026-09-25T10:00"), isArriveBy = false))
        assertEquals(at("2026-09-25T10:35"), mode.queryDateTime(at("2026-09-25T10:40"), isArriveBy = false))
    }

    @Test
    fun `picking a time switches to Fixed and the label shows that time`() {
        val mode = SearchTimeMode.Now.afterTimePicked(at("2026-09-25T14:00"))
        assertEquals(SearchTimeMode.Fixed(at("2026-09-25T14:00")), mode)
        assertEquals("Fri, 25 Sep, 14:00", mode.label())
    }

    @Test
    fun `in Fixed mode later queries keep the picked time`() {
        val mode = SearchTimeMode.Now.afterTimePicked(at("2026-09-25T14:00"))
        assertEquals(at("2026-09-25T13:55"), mode.queryDateTime(at("2026-09-25T10:00"), isArriveBy = false))
        assertEquals(at("2026-09-25T13:55"), mode.queryDateTime(at("2026-09-25T11:30"), isArriveBy = false))
    }

    @Test
    fun `a trip request from Home resets Fixed back to Now`() {
        val mode = SearchTimeMode.Now.afterTimePicked(at("2026-09-25T14:00")).afterHomeTripRequest()
        assertEquals(SearchTimeMode.Now, mode)
        assertEquals("Now", mode.label())
        assertEquals(at("2026-09-25T09:55"), mode.queryDateTime(at("2026-09-25T10:00"), isArriveBy = false))
    }

    // --- 5-minute buffer ---

    @Test
    fun `Now mode requests now minus 5 minutes`() {
        assertEquals(
            at("2026-09-25T10:15"),
            SearchTimeMode.Now.queryDateTime(at("2026-09-25T10:20"), isArriveBy = false),
        )
    }

    @Test
    fun `Fixed mode requests the picked time minus 5 minutes`() {
        assertEquals(
            at("2026-09-25T17:25"),
            SearchTimeMode.Fixed(at("2026-09-25T17:30")).queryDateTime(at("2026-09-25T10:00"), isArriveBy = false),
        )
    }

    @Test
    fun `the buffer crossing midnight moves the date back a day`() {
        assertEquals(
            at("2026-09-25T23:58"),
            SearchTimeMode.Now.queryDateTime(at("2026-09-26T00:03"), isArriveBy = false),
        )
    }

    @Test
    fun `arrive by is not shifted`() {
        assertEquals(
            at("2026-09-25T14:00"),
            SearchTimeMode.Fixed(at("2026-09-25T14:00")).queryDateTime(at("2026-09-25T10:00"), isArriveBy = true),
        )
        assertEquals(
            at("2026-09-25T10:00"),
            SearchTimeMode.Now.queryDateTime(at("2026-09-25T10:00"), isArriveBy = true),
        )
    }
}
