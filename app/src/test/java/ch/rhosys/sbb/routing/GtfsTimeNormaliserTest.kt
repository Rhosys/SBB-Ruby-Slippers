package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsTimeNormaliser
import org.junit.Assert.assertEquals
import org.junit.Test

class GtfsTimeNormaliserTest {

    @Test
    fun `normal time within service day parses correctly`() {
        assertEquals(8 * 3600, GtfsTimeNormaliser.toSeconds("08:00:00"))
        assertEquals(8 * 3600 + 60 + 30, GtfsTimeNormaliser.toSeconds("08:01:30"))
        assertEquals(23 * 3600 + 59 * 60 + 59, GtfsTimeNormaliser.toSeconds("23:59:59"))
    }

    @Test
    fun `time past midnight normalises to seconds past service day start`() {
        // 24:30:00 = 1:30 AM the following calendar day = 88200 seconds
        assertEquals(24 * 3600 + 30 * 60, GtfsTimeNormaliser.toSeconds("24:30:00"))
        // 25:15:00 = 2:15 AM = 90900 seconds
        assertEquals(25 * 3600 + 15 * 60, GtfsTimeNormaliser.toSeconds("25:15:00"))
    }

    @Test
    fun `midnight exactly parses to 86400`() {
        assertEquals(24 * 3600, GtfsTimeNormaliser.toSeconds("24:00:00"))
    }

    @Test
    fun `service day offset for overnight time is next calendar day`() {
        // A time of 25:30:00 is on the next calendar day relative to the service date
        assertEquals(1, GtfsTimeNormaliser.calendarDayOffset("25:30:00"))
        assertEquals(0, GtfsTimeNormaliser.calendarDayOffset("23:59:00"))
        assertEquals(1, GtfsTimeNormaliser.calendarDayOffset("24:00:00"))
    }
}
