package ch.rhosys.sbb.data.local.routing.gtfs

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

private val DAY_KEYS = mapOf(
    DayOfWeek.MONDAY    to "monday",
    DayOfWeek.TUESDAY   to "tuesday",
    DayOfWeek.WEDNESDAY to "wednesday",
    DayOfWeek.THURSDAY  to "thursday",
    DayOfWeek.FRIDAY    to "friday",
    DayOfWeek.SATURDAY  to "saturday",
    DayOfWeek.SUNDAY    to "sunday",
)

class GtfsCalendarResolver(
    patterns: List<Map<String, String>>,
    exceptions: List<Map<String, String>>,
) {
    // service_id → parsed pattern
    private data class Pattern(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val days: Set<DayOfWeek>,
    )

    // exception_type 1=add, 2=remove
    private data class Exception(val date: LocalDate, val type: Int)

    private val patternIndex: Map<String, List<Pattern>> = buildMap {
        for (row in patterns) {
            val id = row["service_id"] ?: continue
            val pattern = Pattern(
                startDate = LocalDate.parse(row["start_date"] ?: continue, GTFS_DATE),
                endDate   = LocalDate.parse(row["end_date"]   ?: continue, GTFS_DATE),
                days      = DAY_KEYS.entries
                    .filter { (_, key) -> row[key] == "1" }
                    .map { (dow, _) -> dow }
                    .toSet(),
            )
            getOrPut(id) { mutableListOf() }.add(pattern)
        }
    }

    private val exceptionIndex: Map<String, List<Exception>> = buildMap {
        for (row in exceptions) {
            val id = row["service_id"] ?: continue
            val exc = Exception(
                date = LocalDate.parse(row["date"] ?: continue, GTFS_DATE),
                type = row["exception_type"]?.toIntOrNull() ?: continue,
            )
            getOrPut(id) { mutableListOf() }.add(exc)
        }
    }

    fun isActive(serviceId: String, date: LocalDate): Boolean {
        // Exceptions always win over the weekly pattern.
        exceptionIndex[serviceId]?.firstOrNull { it.date == date }?.let { exc ->
            return exc.type == 1
        }

        return patternIndex[serviceId]?.any { pattern ->
            !date.isBefore(pattern.startDate) &&
            !date.isAfter(pattern.endDate) &&
            date.dayOfWeek in pattern.days
        } ?: false
    }

    fun activeServiceIds(date: LocalDate): Set<String> =
        (patternIndex.keys + exceptionIndex.keys)
            .filter { isActive(it, date) }
            .toSet()
}
