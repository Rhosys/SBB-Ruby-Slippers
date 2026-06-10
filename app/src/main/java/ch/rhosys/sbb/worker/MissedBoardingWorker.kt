package ch.rhosys.sbb.worker

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// Runs shortly after scheduled departure. Clears the journey only if the user
// is still near the boarding location — they missed the train. If they've moved
// away, they're traveling and the journey stays active until arrival time + grace.
@HiltWorker
class MissedBoardingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val journeyStateHolder: JourneyStateHolder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val originLat = inputData.getDouble(KEY_ORIGIN_LAT, Double.NaN)
        val originLng = inputData.getDouble(KEY_ORIGIN_LNG, Double.NaN)

        if (originLat.isNaN() || originLng.isNaN()) return Result.success()

        val location = lastKnownLocation() ?: return Result.success()

        val distanceM = haversineMeters(location.first, location.second, originLat, originLng)
        if (distanceM <= ORIGIN_RADIUS_M) {
            journeyStateHolder.promptMissedBoarding()
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Pair<Double, Double>? {
        val fineGranted = ContextCompat.checkSelfPermission(
            applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null

        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ?: return null
        return loc.latitude to loc.longitude
    }

    companion object {
        const val WORK_NAME = "journey_missed_boarding"
        const val KEY_ORIGIN_LAT = "origin_lat"
        const val KEY_ORIGIN_LNG = "origin_lng"
        private const val ORIGIN_RADIUS_M = 300.0

        fun buildInputData(originLat: Double, originLng: Double) =
            workDataOf(KEY_ORIGIN_LAT to originLat, KEY_ORIGIN_LNG to originLng)
    }
}

private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2).let { it * it }
    return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
