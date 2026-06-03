package ch.rhosys.sbb.data.local.calendar

import android.content.Context
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class CalendarEvent(
        val id: Long,
        val title: String,
        val location: String,
        val startMillis: Long,
        val calendarId: Long,
    )

    fun getEventsWithLocations(lookaheadDays: Int = 7): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val end = now + lookaheadDays * 24L * 60 * 60 * 1000

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CALENDAR_ID,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} <= ? AND " +
                "${CalendarContract.Events.EVENT_LOCATION} IS NOT NULL AND " +
                "${CalendarContract.Events.EVENT_LOCATION} != ''"
        val args = arrayOf(now.toString(), end.toString())

        val events = mutableListOf<CalendarEvent>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val locationCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                val startCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val calCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
                while (cursor.moveToNext()) {
                    val location = cursor.getString(locationCol) ?: continue
                    if (location.isBlank()) continue
                    events += CalendarEvent(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "",
                        location = location,
                        startMillis = cursor.getLong(startCol),
                        calendarId = cursor.getLong(calCol),
                    )
                }
            }
        }
        return events
    }
}
