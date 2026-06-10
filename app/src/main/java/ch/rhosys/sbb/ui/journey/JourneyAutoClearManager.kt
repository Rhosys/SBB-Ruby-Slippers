package ch.rhosys.sbb.ui.journey

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ch.rhosys.sbb.worker.JourneyClearWorker
import ch.rhosys.sbb.worker.MissedBoardingWorker
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val DESTINATION_GEOFENCE_ID = "journey_destination"
private const val DESTINATION_RADIUS_M = 150f

// How long after the scheduled arrival before we force-clear the widget.
private const val ARRIVAL_GRACE_MINUTES = 15L

// How long after scheduled departure before we check whether the user boarded.
private const val MISSED_BOARDING_CHECK_MINUTES = 10L

@Singleton
class JourneyAutoClearManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)
    private val geofencingClient = LocationServices.getGeofencingClient(context)

    fun schedule(
        fromLat: Double?,
        fromLng: Double?,
        toLat: Double?,
        toLng: Double?,
        departureEpoch: Long?,
        arrivalEpoch: Long?,
    ) {
        if (toLat != null && toLng != null) {
            registerDestinationGeofence(toLat, toLng)
        }
        if (arrivalEpoch != null) {
            scheduleArrivalClear(arrivalEpoch)
        }
        if (departureEpoch != null && fromLat != null && fromLng != null) {
            scheduleMissedBoardingCheck(departureEpoch, fromLat, fromLng)
        }
    }

    fun cancel() {
        geofencingClient.removeGeofences(listOf(DESTINATION_GEOFENCE_ID))
        workManager.cancelUniqueWork(JourneyClearWorker.WORK_NAME)
        workManager.cancelUniqueWork(MissedBoardingWorker.WORK_NAME)
    }

    @SuppressLint("MissingPermission")
    private fun registerDestinationGeofence(lat: Double, lng: Double) {
        val geofence = Geofence.Builder()
            .setRequestId(DESTINATION_GEOFENCE_ID)
            .setCircularRegion(lat, lng, DESTINATION_RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        geofencingClient.addGeofences(request, geofencePendingIntent())
    }

    private fun scheduleArrivalClear(arrivalEpoch: Long) {
        val delayMs = maxOf(0L, arrivalEpoch * 1_000L + TimeUnit.MINUTES.toMillis(ARRIVAL_GRACE_MINUTES) - System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<JourneyClearWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(JourneyClearWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun scheduleMissedBoardingCheck(
        departureEpoch: Long,
        fromLat: Double,
        fromLng: Double,
    ) {
        val delayMs = departureEpoch * 1_000L + TimeUnit.MINUTES.toMillis(MISSED_BOARDING_CHECK_MINUTES) - System.currentTimeMillis()
        if (delayMs < -TimeUnit.MINUTES.toMillis(5)) return // departure already well past, skip check
        val request = OneTimeWorkRequestBuilder<MissedBoardingWorker>()
            .setInitialDelay(maxOf(0L, delayMs), TimeUnit.MILLISECONDS)
            .setInputData(MissedBoardingWorker.buildInputData(fromLat, fromLng))
            .build()
        workManager.enqueueUniqueWork(MissedBoardingWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(context, JourneyGeofenceReceiver::class.java)
            .setAction("ch.rhosys.sbb.GEOFENCE_TRANSITION")
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
