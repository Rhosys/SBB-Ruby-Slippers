package ch.rhosys.sbb.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

// The watch's own fix, so connections start where the user actually is. Null without
// permission or a quick fix — the phone then falls back to its own location.
@SuppressLint("MissingPermission")
suspend fun watchLocationOrNull(context: Context): Pair<Double, Double>? {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    return withTimeoutOrNull(5_000L) {
        runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                .await()
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}
