package ch.rhosys.sbb.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ch.rhosys.sbb.MainActivity
import ch.rhosys.sbb.R
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.ui.journey.JourneyProgress
import ch.rhosys.sbb.ui.journey.JourneySegment
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import ch.rhosys.sbb.ui.journey.buildJourneyTimeline
import ch.rhosys.sbb.ui.journey.journeyProgress
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

// Live, ongoing notification (and, on Android 16+, status bar chip) tracking the active
// journey's progress. Started by JourneyStateHolder.lockIn(), stopped by clear()/cancel().
@AndroidEntryPoint
class JourneyNotificationService : Service() {

    @Inject lateinit var journeyStateHolder: JourneyStateHolder
    @Inject lateinit var prefs: UserPreferencesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_route)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // minSdk is 29 (Q), so the foreground-service-type overload is always available.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        val ticker = flow { while (true) { emit(Unit); delay(30_000) } }

        tickJob = scope.launch {
            combine(
                journeyStateHolder.activeJourney,
                prefs.journeyChipShowsTotalRemaining,
                ticker,
            ) { journey, showTotalRemaining, _ -> journey to showTotalRemaining }
                .collectLatest { (journey, showTotalRemaining) ->
                    if (journey == null) {
                        stopSelf()
                        return@collectLatest
                    }
                    val segments = buildJourneyTimeline(journey.connection)
                    val progress = journeyProgress(segments, Instant.now())
                    if (progress == null) {
                        stopSelf()
                        return@collectLatest
                    }
                    val notification = buildNotification(
                        context = this@JourneyNotificationService,
                        progress = progress,
                        destinationName = journey.to.displayName(),
                        showTotalRemaining = showTotalRemaining,
                    )
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        tickJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "active_journey"
        private const val NOTIFICATION_ID = 4242
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(TIME_FMT)

private fun formatDuration(duration: Duration): String {
    val totalMinutes = maxOf(0, duration.toMinutes())
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun segmentLabel(segment: JourneySegment, isFinal: Boolean, destinationName: String): String {
    val destination = if (isFinal) destinationName else segment.destinationName
    return if (segment.isWalk) "Walk to $destination" else "${segment.lineName ?: "Transit"} → $destination"
}

private fun buildRemoteViews(
    context: Context,
    progress: JourneyProgress,
    destinationName: String,
): RemoteViews {
    val current = progress.current
    val isFinal = !current.isTransferPoint
    val views = RemoteViews(context.packageName, R.layout.notification_journey)

    views.setImageViewResource(
        R.id.journeyLegIcon,
        if (current.isWalk) R.drawable.ic_journey_walk else R.drawable.ic_journey_transit,
    )
    views.setTextViewText(R.id.journeyTitle, segmentLabel(current, isFinal, destinationName))

    val remaining = formatDuration(progress.timeToNextChange)
    val subtitle = if (isFinal) {
        "Arrive ${formatClock(progress.tripEnd)} · $destinationName · in $remaining"
    } else {
        buildString {
            append("Change at ${current.destinationName}")
            progress.next?.platform?.let { append(" · Pl. $it") }
            append(" · in $remaining")
        }
    }
    views.setTextViewText(R.id.journeySubtitle, subtitle)

    views.setProgressBar(R.id.journeyProgressBar, 1000, (progress.fractionComplete * 1000).roundToInt(), false)

    views.removeAllViews(R.id.journeyBlipRow)
    progress.transferFractions.forEach { fraction ->
        val blip = RemoteViews(context.packageName, R.layout.notification_journey_blip)
        val passed = fraction <= progress.fractionComplete
        blip.setImageViewResource(
            R.id.journeyBlipDot,
            if (passed) R.drawable.ic_journey_blip_filled else R.drawable.ic_journey_blip_upcoming,
        )
        views.addView(R.id.journeyBlipRow, blip)
    }

    val endTripIntent = Intent(context, JourneyNotificationActionReceiver::class.java)
        .setAction(ACTION_END_JOURNEY)
    val endTripPendingIntent = PendingIntent.getBroadcast(
        context, 0, endTripIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.journeyEndTrip, endTripPendingIntent)

    return views
}

private fun buildNotification(
    context: Context,
    progress: JourneyProgress,
    destinationName: String,
    showTotalRemaining: Boolean,
): Notification {
    val remoteViews = buildRemoteViews(context, progress, destinationName)
    val tapIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val tapPendingIntent = PendingIntent.getActivity(
        context, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val chipText = if (showTotalRemaining) {
        formatDuration(Duration.between(Instant.now(), progress.tripEnd).let { if (it.isNegative) Duration.ZERO else it })
    } else {
        formatDuration(progress.timeToNextChange)
    }

    if (Build.VERSION.SDK_INT >= 36) {
        return runCatching {
            buildLiveUpdateNotification(context, remoteViews, tapPendingIntent, progress, chipText)
        }.getOrElse {
            buildCompatNotification(context, remoteViews, tapPendingIntent)
        }
    }
    return buildCompatNotification(context, remoteViews, tapPendingIntent)
}

private fun buildCompatNotification(
    context: Context,
    remoteViews: RemoteViews,
    tapPendingIntent: PendingIntent,
): Notification =
    NotificationCompat.Builder(context, JourneyNotificationService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_route)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setContentIntent(tapPendingIntent)
        .setCustomContentView(remoteViews)
        .setCustomBigContentView(remoteViews)
        .build()

// Android 16 (API 36) "Live Updates": promotes this ongoing notification into a status
// bar chip with short critical text and a progress track marked with our transfer points.
// Called via reflection rather than direct API references: this surface is brand new and
// its exact shape isn't guaranteed across every compileSdk 36 build of the framework, so a
// missing/renamed method fails at runtime (caught by the caller) instead of breaking the
// build for everyone.
private fun buildLiveUpdateNotification(
    context: Context,
    remoteViews: RemoteViews,
    tapPendingIntent: PendingIntent,
    progress: JourneyProgress,
    shortCriticalText: String,
): Notification {
    val trackColor = androidx.core.content.ContextCompat.getColor(context, R.color.journey_track)
    val accentColor = androidx.core.content.ContextCompat.getColor(context, R.color.journey_accent)

    val builder = Notification.Builder(context, JourneyNotificationService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_route)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_NAVIGATION)
        .setContentIntent(tapPendingIntent)
        .setCustomContentView(remoteViews)
        .setCustomBigContentView(remoteViews)

    val builderClass = Notification.Builder::class.java
    builderClass.getMethod("setShortCriticalText", String::class.java).invoke(builder, shortCriticalText)
    builderClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
        .invoke(builder, true)

    val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
    val style = progressStyleClass.getDeclaredConstructor().newInstance()
    progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
        .invoke(style, (progress.fractionComplete * 1000).roundToInt())

    val segmentClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
    val segment = segmentClass.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(1000)
    segmentClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(segment, trackColor)
    progressStyleClass.getMethod("addProgressSegment", segmentClass).invoke(style, segment)

    val pointClass = Class.forName("android.app.Notification\$ProgressStyle\$Point")
    val addPoint = progressStyleClass.getMethod("addProgressPoint", pointClass)
    progress.transferFractions.forEach { fraction ->
        val point = pointClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .newInstance((fraction * 1000).roundToInt())
        pointClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(point, accentColor)
        addPoint.invoke(style, point)
    }

    builderClass.getMethod("setStyle", Class.forName("android.app.Notification\$Style")).invoke(builder, style)

    return builder.build()
}
