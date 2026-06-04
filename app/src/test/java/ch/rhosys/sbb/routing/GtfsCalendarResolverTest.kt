package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsCalendarResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GtfsCalendarResolverTest {

    // calendar.txt rows: serviceId, mon,tue,wed,thu,fri,sat,sun, startDate, endDate
    // calendar_dates.txt rows: serviceId, date, exceptionType (1=add, 2=remove)

    private val weekdayPattern = mapOf(
        "service_id" to "WD",
        "monday" to "1", "tuesday" to "1", "wednesday" to "1",
        "thursday" to "1", "friday" to "1",
        "saturday" to "0", "sunday" to "0",
        "start_date" to "20260101", "end_date" to "20261231",
    )
    private val weekendPattern = mapOf(
        "service_id" to "WE",
        "monday" to "0", "tuesday" to "0", "wednesday" to "0",
        "thursday" to "0", "friday" to "0",
        "saturday" to "1", "sunday" to "1",
        "start_date" to "20260101", "end_date" to "20261231",
    )

    @Test
    fun `weekday service active on Monday`() {
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = emptyList(),
        )
        assertTrue(resolver.isActive("WD", LocalDate.of(2026, 6, 1))) // Monday
    }

    @Test
    fun `weekday service inactive on Saturday`() {
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = emptyList(),
        )
        assertFalse(resolver.isActive("WD", LocalDate.of(2026, 6, 6))) // Saturday
    }

    @Test
    fun `service inactive before start date`() {
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = emptyList(),
        )
        assertFalse(resolver.isActive("WD", LocalDate.of(2025, 12, 31)))
    }

    @Test
    fun `service inactive after end date`() {
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = emptyList(),
        )
        assertFalse(resolver.isActive("WD", LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `calendar_dates addition adds service on non-running day`() {
        // WD normally doesn't run Saturday, but an exception adds it on 2026-08-01
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = listOf(mapOf(
                "service_id" to "WD",
                "date" to "20260801",      // Saturday
                "exception_type" to "1",   // add
            )),
        )
        assertTrue(resolver.isActive("WD", LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun `calendar_dates removal removes service on normally-running day`() {
        // WD runs Monday, but 2026-12-28 (Monday) is a holiday — removed
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = listOf(mapOf(
                "service_id" to "WD",
                "date" to "20261228",      // Monday
                "exception_type" to "2",   // remove
            )),
        )
        assertFalse(resolver.isActive("WD", LocalDate.of(2026, 12, 28)))
    }

    @Test
    fun `exception wins over weekly pattern on holiday`() {
        // Christmas 2026 is Friday — WD would normally run, but exception removes it
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern),
            exceptions = listOf(mapOf(
                "service_id" to "WD",
                "date" to "20261225",
                "exception_type" to "2",
            )),
        )
        assertFalse(resolver.isActive("WD", LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `active service ids for a date returns all matching services`() {
        val resolver = GtfsCalendarResolver(
            patterns = listOf(weekdayPattern, weekendPattern),
            exceptions = emptyList(),
        )
        val active = resolver.activeServiceIds(LocalDate.of(2026, 6, 6)) // Saturday
        assertFalse("WD should not be active on Saturday", "WD" in active)
        assertTrue("WE should be active on Saturday", "WE" in active)
    }
}
