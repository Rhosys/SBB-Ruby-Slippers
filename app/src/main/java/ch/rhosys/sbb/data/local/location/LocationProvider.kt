package ch.rhosys.sbb.data.local.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the device's location continuously for the lifetime of the app
 * process, exposing it as a [StateFlow] so any screen can react to updates.
 *
 * [currentLocation] is only ever advanced on a successful fix — it is never
 * reset to null, so a transient GPS/permission failure leaves the last known
 * position (and anything filled from it) untouched.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    private val _currentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val currentLocation: StateFlow<Pair<Double, Double>?> = _currentLocation.asStateFlow()

    private var updatesRequested = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc -> _currentLocation.value = loc.latitude to loc.longitude }
        }
    }

    /** Begin continuous updates. Safe to call repeatedly (e.g. after a permission grant). */
    fun start() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (updatesRequested || !hasPermission) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .build()
        runCatching {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updatesRequested = true
        }
        refreshNow()
    }

    /** Fire-and-forget request for a fresh fix right now, outside the regular update cadence. */
    fun refreshNow() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return
        val cts = CancellationTokenSource()
        runCatching {
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    loc?.let { _currentLocation.value = it.latitude to it.longitude }
                }
        }
    }

    /** Cached value if we have one; otherwise a one-off suspend fetch. Never throws. */
    suspend fun getLocationOrNull(): Pair<Double, Double>? {
        _currentLocation.value?.let { return it }
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null
        return runCatching {
            val cts = CancellationTokenSource()
            val loc = fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            loc?.let { it.latitude to it.longitude }
        }.getOrNull()?.also { _currentLocation.value = it }
    }

    /**
     * Always requests a brand-new fix rather than returning a previously cached one, so
     * callers that must reflect the user's *current* position (not wherever they were when
     * "Current location" was last resolved) get up-to-date coordinates. Falls back to the
     * last known fix only if the fresh request fails outright.
     */
    suspend fun getFreshLocationOrNull(): Pair<Double, Double>? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return _currentLocation.value
        val fresh = runCatching {
            val cts = CancellationTokenSource()
            val loc = fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            loc?.let { it.latitude to it.longitude }
        }.getOrNull()
        if (fresh != null) _currentLocation.value = fresh
        return fresh ?: _currentLocation.value
    }
}
