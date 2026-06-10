package ch.rhosys.sbb.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkStore
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private const val REFRESH_INTERVAL_DAYS = 1L

private val NEEDED_FILES = setOf(
    "stops.txt", "routes.txt", "trips.txt", "stop_times.txt",
    "calendar.txt", "calendar_dates.txt", "transfers.txt",
)

@HiltWorker
class GtfsImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val store: GtfsNetworkStore,
    private val localRepo: LocalTransportRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: gtfsFeedUrl()

        // If the URL has changed (Fahrplanwechsel), the stored ETag is for the old feed — don't send it.
        val urlChanged = store.lastUrl() != url
        val requestBuilder = Request.Builder().url(url)
        if (!urlChanged) store.lastEtag()?.let { requestBuilder.header("If-None-Match", it) }

        val response = try {
            okHttpClient.newCall(requestBuilder.build()).execute()
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        if (response.code == 304) {
            response.close()
            return Result.success()
        }

        if (!response.isSuccessful) {
            response.close()
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val newEtag = response.header("ETag")

        val files = try {
            response.body!!.use { body ->
                ZipInputStream(body.byteStream()).use { zip ->
                    buildMap {
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name in NEEDED_FILES) {
                                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                            }
                            entry = zip.nextEntry
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val parsed = try {
            GtfsParser().parse(files)
        } catch (e: Exception) {
            return Result.failure()
        }

        store.write(parsed)
        if (newEtag != null) store.writeEtag(newEtag)
        store.writeUrl(url)
        localRepo.invalidate()
        scheduleFahrplanwechsel(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "gtfs_import"
        private const val FAHRPLANWECHSEL_WORK_NAME = "gtfs_import_fahrplanwechsel"
        private const val FAHRPLANWECHSEL_STARTUP_WORK_NAME = "gtfs_import_startup_stale"
        const val KEY_URL = "gtfs_url"

        private val SWISS_ZONE = ZoneId.of("Europe/Zurich")

        // Timetable year = year whose name matches the dataset on opentransportdata.swiss,
        // e.g. "timetable-gtfs2026". Changes on the second Sunday of December each year.
        fun currentTimetableYear(date: LocalDate = LocalDate.now(SWISS_ZONE)): Int =
            if (date.isBefore(fahrplanwechselDate(date.year))) date.year else date.year + 1

        fun gtfsFeedUrl(date: LocalDate = LocalDate.now(SWISS_ZONE)): String =
            "https://opentransportdata.swiss/en/dataset/timetable-gtfs${currentTimetableYear(date)}/permalink/resource/gtfs_fp.zip"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<GtfsImportWorker>(REFRESH_INTERVAL_DAYS, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
            )
            scheduleFahrplanwechsel(context)
            scheduleImmediateIfStale(context)
        }

        // On startup: if we're within the changeover window and our stored data pre-dates
        // the most recent Fahrplanwechsel, enqueue an immediate check. The worker's ETag
        // logic ensures we only download if the feed actually changed.
        fun scheduleImmediateIfStale(context: Context) {
            val today = LocalDate.now(SWISS_ZONE)
            val thisYearSwitch = fahrplanwechselDate(today.year)
            val mostRecentSwitch = if (!today.isBefore(thisYearSwitch)) thisYearSwitch
                                   else fahrplanwechselDate(today.year - 1)

            val daysFromSwitch = ChronoUnit.DAYS.between(mostRecentSwitch, today)
            if (daysFromSwitch !in -2..3) return

            val switchEpochMs = mostRecentSwitch.atStartOfDay(SWISS_ZONE).toInstant().toEpochMilli()
            val lastImportMs = File(context.filesDir, "gtfs/meta.txt")
                .runCatching { readText().trim().toLong() }.getOrDefault(0L)
            if (lastImportMs >= switchEpochMs) return

            WorkManager.getInstance(context).enqueueUniqueWork(
                FAHRPLANWECHSEL_STARTUP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GtfsImportWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        // Schedules a one-shot run at 04:00 Swiss time on the next Fahrplanwechsel Sunday
        // (second Sunday of December). Constraints are relaxed — metered network and low
        // battery are both acceptable because the changeover only happens once a year.
        fun scheduleFahrplanwechsel(context: Context) {
            val nowDate = LocalDate.now(SWISS_ZONE)
            val nextSwitch = nextFahrplanwechsel(nowDate)
            val runAt = nextSwitch.atTime(4, 0).atZone(SWISS_ZONE)
            val delayMs = runAt.toInstant().toEpochMilli() - System.currentTimeMillis()
            if (delayMs <= 0) return

            WorkManager.getInstance(context).enqueueUniqueWork(
                FAHRPLANWECHSEL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GtfsImportWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }

        fun nextFahrplanwechsel(from: LocalDate): LocalDate {
            val thisYear = fahrplanwechselDate(from.year)
            return if (!from.isAfter(thisYear)) thisYear else fahrplanwechselDate(from.year + 1)
        }

        // Second Sunday of December — the Swiss annual timetable changeover date.
        fun fahrplanwechselDate(year: Int): LocalDate {
            val firstSunday = LocalDate.of(year, 12, 1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            return firstSunday.plusWeeks(1)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(FAHRPLANWECHSEL_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(FAHRPLANWECHSEL_STARTUP_WORK_NAME)
        }
    }
}
