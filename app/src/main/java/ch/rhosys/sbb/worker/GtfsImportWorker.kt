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
import ch.rhosys.sbb.data.local.routing.LocalTransportRepository
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkStore
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private const val GTFS_FEED_URL = "https://opentransportdata.swiss/en/dataset/timetable-2026-gtfs2020/permalink/resource/gtfs_fp2026.zip"
private const val REFRESH_INTERVAL_DAYS = 7L

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
        val url = inputData.getString(KEY_URL) ?: GTFS_FEED_URL

        val requestBuilder = Request.Builder().url(url)
        store.lastEtag()?.let { requestBuilder.header("If-None-Match", it) }

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
        localRepo.invalidate()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "gtfs_import"
        const val KEY_URL = "gtfs_url"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GtfsImportWorker>(REFRESH_INTERVAL_DAYS, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
