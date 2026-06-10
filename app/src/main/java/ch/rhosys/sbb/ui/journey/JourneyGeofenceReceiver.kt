package ch.rhosys.sbb.ui.journey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.rhosys.sbb.worker.JourneyClearWorker
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class JourneyGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            JourneyClearWorker.enqueueImmediate(context)
        }
    }
}
