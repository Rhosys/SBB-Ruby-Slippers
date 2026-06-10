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
}
