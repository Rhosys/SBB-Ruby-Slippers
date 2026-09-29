package ch.rhosys.sbb.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.rhosys.sbb.MainActivity
import ch.rhosys.sbb.R
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.ui.journey.JourneyProgress
import ch.rhosys.sbb.ui.journey.JourneySegment
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import ch.rhosys.sbb.ui.journey.buildJourneyTimeline
import ch.rhosys.sbb.ui.journey.formatJourneyClock
import ch.rhosys.sbb.ui.journey.formatJourneyMinutes
import ch.rhosys.sbb.ui.journey.headline
import ch.rhosys.sbb.ui.journey.journeyProgress
import ch.rhosys.sbb.ui.journey.nextStep
import ch.rhosys.sbb.ui.journey.summary
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
                        segments = segments,
                        progress = progress,
                        destinationName = journey.to.displayName(),
                        arrivalDelayMinutes = journey.connection.arrival.delayMinutes,
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

private fun formatDuration(duration: Duration): String = formatJourneyMinutes(duration)

// Most step rows the expanded notification has room for.
private const val MAX_STEP_ROWS = 4

private fun buildCollapsedViews(
    context: Context,
    segments: List<JourneySegment>,
    progress: JourneyProgress,
    destinationName: String,
    now: Instant,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.notification_journey)
    views.setImageViewResource(
        R.id.journeyLegIcon,
        if (progress.current.isWalk) R.drawable.ic_journey_walk else R.drawable.ic_journey_transit,
    )
    views.setTextViewText(R.id.journeyTitle, progress.headline(now, destinationName))
    views.setTextViewText(R.id.journeySubtitle, progress.nextStep(now, destinationName))
    views.setImageViewBitmap(R.id.journeyTrack, drawTrack(context, segments, now))
    return views
}

// The larger view: headline, what's next, the track with start / time left / arrival, and
// the trip's steps around the current one — done, now and coming up.
private fun buildExpandedViews(
    context: Context,
    segments: List<JourneySegment>,
    progress: JourneyProgress,
    destinationName: String,
    arrivalDelayMinutes: Int,
    now: Instant,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.notification_journey_expanded)
    views.setImageViewResource(
        R.id.journeyLegIcon,
        if (progress.current.isWalk) R.drawable.ic_journey_walk else R.drawable.ic_journey_transit,
    )
    views.setTextViewText(R.id.journeyTitle, progress.headline(now, destinationName))
    views.setTextViewText(R.id.journeySubtitle, progress.nextStep(now, destinationName))
    views.setImageViewBitmap(R.id.journeyTrack, drawTrack(context, segments, now))
    views.setTextViewText(R.id.journeyStartTime, formatJourneyClock(progress.tripStart))
    val left = Duration.between(maxOf(now, progress.tripStart), progress.tripEnd)
    views.setTextViewText(
        R.id.journeyRemaining,
        if (left.isNegative || left.isZero) "Arrived" else "${formatDuration(left)} left",
    )
    views.setTextViewText(
        R.id.journeyArrivalTime,
        "Arrive ${formatJourneyClock(progress.tripEnd)}" + if (arrivalDelayMinutes > 0) " (+$arrivalDelayMinutes)" else "",
    )

    views.removeAllViews(R.id.journeySteps)
    val currentIndex = segments.indexOf(progress.current).coerceAtLeast(0)
    // One finished step for context, then the current one and what follows.
    val from = (currentIndex - 1).coerceAtLeast(0)
    val shown = segments.subList(from, minOf(segments.size, from + MAX_STEP_ROWS))
    shown.forEachIndexed { offset, segment ->
        val index = from + offset
        val row = RemoteViews(context.packageName, R.layout.notification_journey_step)
        val done = !now.isBefore(segment.end)
        row.setImageViewResource(
            R.id.journeyStepDot,
            if (done || index == currentIndex) R.drawable.ic_journey_blip_filled else R.drawable.ic_journey_blip_upcoming,
        )
        row.setTextViewText(R.id.journeyStepTime, formatJourneyClock(segment.start))
        val text = (if (done) "✓ " else if (index == currentIndex) "▶ " else "") + segment.summary(destinationName)
        row.setTextViewText(R.id.journeyStepText, text)
        views.addView(R.id.journeySteps, row)
    }
    val hidden = segments.size - (from + shown.size)
    if (hidden > 0) {
        val more = RemoteViews(context.packageName, R.layout.notification_journey_step)
        more.setImageViewResource(R.id.journeyStepDot, R.drawable.ic_journey_blip_upcoming)
        more.setTextViewText(R.id.journeyStepTime, "")
        more.setTextViewText(R.id.journeyStepText, "+ $hidden more · arrive $destinationName")
        views.addView(R.id.journeySteps, more)
    }
    return views
}

// The trip as one bar scaled to the notification's width: rides thick, walks thin, finished
// parts muted, a ring at every change and a larger dot at the current position.
private fun drawTrack(context: Context, segments: List<JourneySegment>, now: Instant): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (context.resources.displayMetrics.widthPixels - 64 * density).toInt().coerceAtLeast(100)
    val height = (20 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val accent = ContextCompat.getColor(context, R.color.journey_accent)
    val track = ContextCompat.getColor(context, R.color.journey_track)
    val muted = ContextCompat.getColor(context, R.color.journey_icon_muted)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val start = segments.first().start.toEpochMilli()
    val total = (segments.last().end.toEpochMilli() - start).coerceAtLeast(1L).toFloat()
    val pad = 8 * density
    val span = width - 2 * pad
    fun x(instant: Instant) = pad + (instant.toEpochMilli() - start) / total * span
    val centerY = height / 2f

    segments.forEach { segment ->
        val bar = (if (segment.isWalk) 3 else 7) * density
        paint.color = when {
            !now.isBefore(segment.end) -> track
            segment.isWalk -> muted
            else -> accent
        }
        canvas.drawRoundRect(
            RectF(x(segment.start), centerY - bar / 2, maxOf(x(segment.end), x(segment.start) + 1), centerY + bar / 2),
            bar / 2, bar / 2, paint,
        )
    }
    segments.filter { it.isTransferPoint }.forEach { segment ->
        paint.color = accent
        canvas.drawCircle(x(segment.end), centerY, 5 * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x(segment.end), centerY, 2.5f * density, paint)
    }
    val nowX = x(now.coerceIn(segments.first().start, segments.last().end))
    paint.color = Color.WHITE
    canvas.drawCircle(nowX, centerY, 7 * density, paint)
    paint.color = accent
    canvas.drawCircle(nowX, centerY, 5 * density, paint)
    return bitmap
}

private fun buildNotification(
    context: Context,
    segments: List<JourneySegment>,
    progress: JourneyProgress,
    destinationName: String,
    arrivalDelayMinutes: Int,
    showTotalRemaining: Boolean,
): Notification {
    val now = Instant.now()
    val collapsed = buildCollapsedViews(context, segments, progress, destinationName, now)
    val expanded = buildExpandedViews(context, segments, progress, destinationName, arrivalDelayMinutes, now)
    val tapIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_JOURNEY, true)
    }
    val tapPendingIntent = PendingIntent.getActivity(
        context, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val chipText = if (showTotalRemaining) {
        formatDuration(Duration.between(now, progress.tripEnd).let { if (it.isNegative) Duration.ZERO else it })
    } else {
        formatDuration(progress.timeToNextChange)
    }

    if (Build.VERSION.SDK_INT >= 36) {
        return runCatching {
            buildLiveUpdateNotification(context, collapsed, expanded, tapPendingIntent, progress, chipText)
        }.getOrElse {
            buildCompatNotification(context, collapsed, expanded, tapPendingIntent)
        }
    }
    return buildCompatNotification(context, collapsed, expanded, tapPendingIntent)
}

private fun buildCompatNotification(
    context: Context,
    collapsed: RemoteViews,
    expanded: RemoteViews,
    tapPendingIntent: PendingIntent,
): Notification =
    NotificationCompat.Builder(context, JourneyNotificationService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_route)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setContentIntent(tapPendingIntent)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(collapsed)
        .setCustomBigContentView(expanded)
        .build()

// Android 16 (API 36) "Live Updates": promotes this ongoing notification into a status
// bar chip with short critical text and a progress track marked with our transfer points.
// Called via reflection rather than direct API references: this surface is brand new and
// its exact shape isn't guaranteed across every compileSdk 36 build of the framework, so a
// missing/renamed method fails at runtime (caught by the caller) instead of breaking the
// build for everyone.
private fun buildLiveUpdateNotification(
    context: Context,
    collapsed: RemoteViews,
    expanded: RemoteViews,
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
        .setCustomContentView(collapsed)
        .setCustomBigContentView(expanded)

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
