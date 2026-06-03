package ch.rhosys.sbb.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.rhosys.sbb.data.local.calendar.CalendarRepository
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.TransportRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class CalendarSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val calendarRepository: CalendarRepository,
    private val routeRepository: RouteRepository,
    private val transportRepository: TransportRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
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
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "calendar_sync"

        fun schedule(context: Context, intervalHours: Int) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
                intervalHours.toLong(), TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
