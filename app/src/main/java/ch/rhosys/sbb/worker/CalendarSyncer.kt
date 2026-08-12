package ch.rhosys.sbb.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ch.rhosys.sbb.data.local.calendar.CalendarRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CalendarSyncResult {
    data object Success : CalendarSyncResult
    data class Failure(val message: String) : CalendarSyncResult
}

/**
 * Reads device-calendar events with a location and upserts them as saved routes.
 * Shared by [CalendarSyncWorker] (periodic, best-effort) and the Settings screen
 * (manual "Sync now" / enable-toggle immediate check, which needs a pass/fail result).
 */
@Singleton
class CalendarSyncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarRepository: CalendarRepository,
    private val routeRepository: RouteRepository,
    private val transportRepository: TransportRepository,
    private val notificationScheduler: RouteNotificationScheduler,
) {
    suspend fun sync(): CalendarSyncResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return CalendarSyncResult.Failure("Calendar permission not granted")
        }

        return try {
            val events = calendarRepository.getEventsWithLocations(lookaheadDays = 7)
            val activeIds = mutableSetOf<Long>()

            for (event in events) {
                val locations = runCatching {
                    transportRepository.getLocations(event.location).stations
                }.getOrNull() ?: continue

                val resolved = locations.firstOrNull() ?: continue
                val name = resolved.name ?: continue
                val lat = resolved.coordinate?.y ?: continue
                val lng = resolved.coordinate?.x ?: continue

                routeRepository.upsertCalendarRoute(
                    calendarEventId = event.id,
                    destinationName = name,
                    destinationLat = lat,
                    destinationLng = lng,
                    scheduledAtMillis = event.startMillis,
                    label = event.title.takeIf { it.isNotBlank() },
                )
                activeIds += event.id
            }

            routeRepository.pruneStaleCalendarRoutes(activeIds)
            notificationScheduler.schedule(routeRepository.getRecurringRoutes().first())
            CalendarSyncResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CalendarSyncResult.Failure(e.message ?: "Calendar sync failed")
        }
    }
}
