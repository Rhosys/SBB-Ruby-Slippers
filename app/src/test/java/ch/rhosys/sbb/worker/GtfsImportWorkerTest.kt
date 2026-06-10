package ch.rhosys.sbb.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class GtfsImportWorkerTest {

    @Test
    fun `fahrplanwechselDate returns second Sunday of December`() {
        // Known values cross-checked against Swiss SBB timetable change announcements.
        assertEquals(LocalDate.of(2023, 12, 10), GtfsImportWorker.fahrplanwechselDate(2023))
        assertEquals(LocalDate.of(2024, 12,  8), GtfsImportWorker.fahrplanwechselDate(2024))
        assertEquals(LocalDate.of(2025, 12, 14), GtfsImportWorker.fahrplanwechselDate(2025))
        assertEquals(LocalDate.of(2026, 12, 13), GtfsImportWorker.fahrplanwechselDate(2026))
    }

    @Test
    fun `fahrplanwechselDate is always a Sunday`() {
        (2020..2035).forEach { year ->
            assertEquals(
                "Expected Sunday for year $year",
                DayOfWeek.SUNDAY,
                GtfsImportWorker.fahrplanwechselDate(year).dayOfWeek,
            )
        }
    }

    @Test
    fun `nextFahrplanwechsel returns same year when called before changeover`() {
        val beforeSwitch = LocalDate.of(2025, 6, 10)
        assertEquals(GtfsImportWorker.fahrplanwechselDate(2025), GtfsImportWorker.nextFahrplanwechsel(beforeSwitch))
    }

    @Test
    fun `nextFahrplanwechsel returns next year when called after changeover`() {
        val afterSwitch = LocalDate.of(2025, 12, 15)
        assertEquals(GtfsImportWorker.fahrplanwechselDate(2026), GtfsImportWorker.nextFahrplanwechsel(afterSwitch))
    }

    @Test
    fun `nextFahrplanwechsel returns same day when called on changeover day`() {
        val switchDay = GtfsImportWorker.fahrplanwechselDate(2025)
        assertEquals(switchDay, GtfsImportWorker.nextFahrplanwechsel(switchDay))
    }

    @Test
    fun `currentTimetableYear is current year before changeover`() {
        // June 10, 2026 — changeover is Dec 13 2026, so timetable year = 2026
        assertEquals(2026, GtfsImportWorker.currentTimetableYear(LocalDate.of(2026, 6, 10)))
    }

    @Test
    fun `currentTimetableYear advances on changeover day`() {
        val switchDay2025 = GtfsImportWorker.fahrplanwechselDate(2025) // Dec 14 2025
        assertEquals(2025, GtfsImportWorker.currentTimetableYear(switchDay2025.minusDays(1)))
        assertEquals(2026, GtfsImportWorker.currentTimetableYear(switchDay2025))
        assertEquals(2026, GtfsImportWorker.currentTimetableYear(switchDay2025.plusDays(1)))
    }

    @Test
    fun `gtfsFeedUrl contains no hardcoded year`() {
        val url2025 = GtfsImportWorker.gtfsFeedUrl(LocalDate.of(2025, 6, 1))
        val url2026 = GtfsImportWorker.gtfsFeedUrl(LocalDate.of(2026, 6, 1))
        assertTrue(url2025.contains("timetable-gtfs2025"))
        assertTrue(url2026.contains("timetable-gtfs2026"))
        assertFalse(url2025.contains("gtfs2020"))
        assertFalse(url2026.contains("gtfs2020"))
    }
}
