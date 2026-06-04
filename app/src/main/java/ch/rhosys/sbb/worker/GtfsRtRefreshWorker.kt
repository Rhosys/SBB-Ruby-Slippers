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
import ch.rhosys.sbb.data.local.routing.rt.GtfsRtDecoder
import ch.rhosys.sbb.data.local.routing.rt.GtfsRtStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// GTFS-RT feed from opentransportdata.swiss — requires a free API token.
// Set the token in UserPreferencesRepository once the token onboarding flow is built.
// See todo.md for the infra steps needed to obtain and configure the token.
private const val RT_FEED_URL = "https://api.opentransportdata.swiss/gtfs-rt-datasets/resource/gtfs-rt.pb"

private const val REFRESH_INTERVAL_MINUTES = 15L

@HiltWorker
class GtfsRtRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val rtStore: GtfsRtStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: RT_FEED_URL
        val token = inputData.getString(KEY_TOKEN) ?: ""

        val bytes = try {
            fetchFeed(url, token)
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        return try {
            val (updates, alerts) = GtfsRtDecoder().decode(bytes)
            rtStore.update(updates, alerts)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun fetchFeed(url: String, token: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val response = okHttpClient.newCall(req).execute()
        check(response.isSuccessful) { "RT feed download failed: ${response.code}" }
        return response.body!!.bytes()
    }

    companion object {
        private const val WORK_NAME = "gtfs_rt_refresh"
        const val KEY_URL = "rt_feed_url"
        const val KEY_TOKEN = "rt_feed_token"

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
    }
}
