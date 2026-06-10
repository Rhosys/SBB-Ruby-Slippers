package ch.rhosys.sbb.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.rhosys.sbb.domain.model.RecurringRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val SWISS_ZONE = ZoneId.of("Europe/Zurich")

@Singleton
class RouteNotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedule(routes: List<RecurringRoute>) {
        val wm = WorkManager.getInstance(context)
        routes.forEach { route ->
            val name = workName(route.id)
            if (route.isPaused || route.notifyBeforeMinutes <= 0) {
                wm.cancelUniqueWork(name)
                return@forEach
            }
            val nextMs = nextOccurrenceMillis(route)
            if (nextMs == Long.MAX_VALUE) return@forEach
            val delayMs = nextMs - TimeUnit.MINUTES.toMillis(route.notifyBeforeMinutes.toLong()) - System.currentTimeMillis()
            if (delayMs <= 0) return@forEach

            val request = OneTimeWorkRequestBuilder<RouteNotificationWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(
                    RouteNotificationWorker.KEY_ROUTE_ID to route.id,
                    RouteNotificationWorker.KEY_DESTINATION to route.destinationName,
                    RouteNotificationWorker.KEY_LABEL to route.label,
                    RouteNotificationWorker.KEY_NOTIFY_BEFORE_MINUTES to route.notifyBeforeMinutes,
                ))
                .build()
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }

    private fun workName(routeId: Long) = "route_notification_$routeId"

    private fun nextOccurrenceMillis(route: RecurringRoute): Long {
        val parts = route.rrule.split(";").mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()

        val freq = parts["FREQ"] ?: "WEEKLY"
        val time = LocalTime.of(route.departureHour, route.departureMinute)
        val now = LocalDateTime.now(SWISS_ZONE)
        val todayAt = LocalDate.now(SWISS_ZONE).atTime(time)
        val startDate = if (todayAt.isAfter(now)) todayAt.toLocalDate()
                        else todayAt.toLocalDate().plusDays(1)

        if (freq == "DAILY") {
            return startDate.atTime(time).atZone(SWISS_ZONE).toInstant().toEpochMilli()
        }

        // WEEKLY — find the soonest day-of-week that matches BYDAY
        val byDay: Set<DayOfWeek> = parts["BYDAY"]
            ?.split(",")
            ?.mapNotNull { code ->
                when (code.trim().takeLast(2).uppercase()) {
                    "MO" -> DayOfWeek.MONDAY
                    "TU" -> DayOfWeek.TUESDAY
                    "WE" -> DayOfWeek.WEDNESDAY
                    "TH" -> DayOfWeek.THURSDAY
                    "FR" -> DayOfWeek.FRIDAY
                    "SA" -> DayOfWeek.SATURDAY
                    "SU" -> DayOfWeek.SUNDAY
                    else -> null
                }
            }?.toSet() ?: return Long.MAX_VALUE

        var check = startDate
        repeat(7) {
            if (check.dayOfWeek in byDay) {
                return check.atTime(time).atZone(SWISS_ZONE).toInstant().toEpochMilli()
            }
            check = check.plusDays(1)
        }
        return Long.MAX_VALUE
    }
}
