package ch.rhosys.sbb.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class JourneyClearWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val journeyStateHolder: JourneyStateHolder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        journeyStateHolder.clear()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "journey_auto_clear"

        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<JourneyClearWorker>().build(),
            )
        }
    }
}
