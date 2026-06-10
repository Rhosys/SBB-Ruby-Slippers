package ch.rhosys.sbb.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.rhosys.sbb.MainActivity
import ch.rhosys.sbb.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RouteNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val destination = inputData.getString(KEY_DESTINATION) ?: return Result.success()
        val label = inputData.getString(KEY_LABEL) ?: destination
        val notifyBefore = inputData.getInt(KEY_NOTIFY_BEFORE_MINUTES, 0)
        val notificationId = (inputData.getLong(KEY_ROUTE_ID, 0L) and 0x7FFFFFFF).toInt()

        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, notificationId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = buildString {
            append(if (notifyBefore > 0) "Departs in $notifyBefore min" else "Time to leave")
            append(" → $destination")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_route)
            .setContentTitle(label)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "route_notifications"
        const val KEY_ROUTE_ID = "route_id"
        const val KEY_DESTINATION = "destination"
        const val KEY_LABEL = "label"
        const val KEY_NOTIFY_BEFORE_MINUTES = "notify_before_minutes"
    }
}
