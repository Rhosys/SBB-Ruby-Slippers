package ch.rhosys.sbb.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.rhosys.sbb.worker.JourneyCancelWorker

const val ACTION_END_JOURNEY = "ch.rhosys.sbb.action.END_JOURNEY"

class JourneyNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_END_JOURNEY) {
            JourneyCancelWorker.enqueueImmediate(context)
        }
    }
}
