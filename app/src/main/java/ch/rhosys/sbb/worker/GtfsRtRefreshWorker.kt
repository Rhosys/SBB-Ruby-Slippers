package ch.rhosys.sbb.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.data.local.routing.rt.GtfsRtDecoder
import ch.rhosys.sbb.data.local.routing.rt.GtfsRtStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

// GTFS-RT feed from opentransportdata.swiss — requires a free API token.
// Enter the token in Settings → Real-time data. Worker skips silently if no token is configured.
private const val RT_FEED_URL = "https://api.opentransportdata.swiss/gtfs-rt-datasets/resource/gtfs-rt.pb"

private const val REFRESH_INTERVAL_MINUTES = 15L

@HiltWorker
class GtfsRtRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val rtStore: GtfsRtStore,
    private val prefs: UserPreferencesRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: RT_FEED_URL
        val token = prefs.rtToken.first()

        if (token.isBlank()) return Result.success()

        val bytes = try {
            fetchFeed(url, token)
        } catch (e: Exception) {
            if (runAttemptCount < 3) return Result.retry()
            prefs.recordRtError(Instant.now().epochSecond, e.message ?: "Feed download failed")
            return Result.failure()
        }

        return try {
            val (updates, alerts) = GtfsRtDecoder().decode(bytes)
            rtStore.update(updates, alerts)
            prefs.recordRtSuccess(Instant.now().epochSecond)
            Result.success()
        } catch (e: Exception) {
            prefs.recordRtError(Instant.now().epochSecond, e.message ?: "Failed to decode feed")
            Result.failure()
        }
    }

    private fun fetchFeed(url: String, token: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        val response = okHttpClient.newCall(req).execute()
        check(response.isSuccessful) { "RT feed download failed: ${response.code}" }
        return response.body!!.bytes()
    }

    companion object {
        private const val WORK_NAME = "gtfs_rt_refresh"
        const val KEY_URL = "rt_feed_url"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GtfsRtRefreshWorker>(
                REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES,
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

        // Runs an immediate one-off refresh, e.g. right after the user saves a new token,
        // so the Settings status line doesn't sit on stale state until the next periodic tick.
        fun triggerOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<GtfsRtRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
