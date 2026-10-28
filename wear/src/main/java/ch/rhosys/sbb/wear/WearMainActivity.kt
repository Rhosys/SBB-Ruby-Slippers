package ch.rhosys.sbb.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow

class WearMainActivity : ComponentActivity() {

    private val launchedPlaceId = MutableStateFlow<Long?>(null)

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        requestLocationPermission()
        setContent {
            MaterialTheme {
                val placeId by launchedPlaceId.collectAsState()
                WearApp(
                    launchedPlaceId = placeId,
                    onLaunchedPlaceHandled = { launchedPlaceId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val placeId = intent?.getLongExtra(EXTRA_PLACE_ID, -1L) ?: -1L
        if (placeId >= 0) launchedPlaceId.value = placeId
    }

    // Optional: without it connections start from the phone's location instead.
    private fun requestLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
    }
}
