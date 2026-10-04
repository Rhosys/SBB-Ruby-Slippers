package ch.rhosys.sbb.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import ch.rhosys.sbb.wear.PhoneClient
import ch.rhosys.sbb.wear.WearJourneyData
import ch.rhosys.sbb.wear.WearMainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

// Watch-face complication for the active journey: departure time until boarding, then a
// countdown to each change, then to the final stop. A timeline covers every phase, so the
// watch face moves between them on its own without asking the phone again.
class JourneyComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        if (type != ComplicationType.SHORT_TEXT && type != ComplicationType.LONG_TEXT) return null
        val journey = PhoneClient(this).journey()
        val tap = openApp(this)
        val entries = journeyPhases(journey).map { phase ->
            TimelineEntry(
                TimeInterval(
                    if (phase.start == Long.MIN_VALUE) Instant.EPOCH else Instant.ofEpochSecond(phase.start),
                    Instant.ofEpochSecond(phase.end()),
                ),
                phase.toData(type, tap),
            )
        }
        return ComplicationDataTimeline(noJourney(type, tap), entries)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = Instant.now().epochSecond
        return JourneyPhase.ToTransfer(now, now + 12 * 60, "Bern").toData(type, null)
    }

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, JourneyComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}

private fun JourneyPhase.toData(type: ComplicationType, tap: PendingIntent?): ComplicationData {
    val (title, shortText, longText) = when (this) {
        is JourneyPhase.BeforeDeparture -> {
            val time = Instant.ofEpochSecond(departureEpochSeconds).atZone(ZoneId.systemDefault()).format(TIME_FMT)
            Triple("Dep", plain(time), plain("Departs $time · $stationName"))
        }
        is JourneyPhase.ToTransfer ->
            Triple("Chg", countdown(arrivalEpochSeconds), countdown(arrivalEpochSeconds, "Change at $stationName in ^1"))
        is JourneyPhase.ToArrival ->
            Triple("Arr", countdown(arrivalEpochSeconds), countdown(arrivalEpochSeconds, "$stationName in ^1"))
    }
    return build(type, title, shortText, longText, tap)
}

private fun noJourney(type: ComplicationType, tap: PendingIntent?): ComplicationData =
    build(type, "SBB", plain("—"), plain("No active journey"), tap)

private fun build(
    type: ComplicationType,
    title: String,
    shortText: ComplicationText,
    longText: ComplicationText,
    tap: PendingIntent?,
): ComplicationData = when (type) {
    ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(longText, longText)
        .setTitle(plain(title))
        .setTapAction(tap)
        .build()
    else -> ShortTextComplicationData.Builder(shortText, longText)
        .setTitle(plain(title))
        .setTapAction(tap)
        .build()
}

private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

// "^1" in `text` is replaced by the live countdown, e.g. "12m".
private fun countdown(targetEpochSeconds: Long, text: String? = null): ComplicationText =
    TimeDifferenceComplicationText.Builder(
        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
        CountDownTimeReference(Instant.ofEpochSecond(targetEpochSeconds)),
    )
        .setMinimumTimeUnit(TimeUnit.MINUTES)
        .setText(text)
        .build()

private fun openApp(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        Intent(context, WearMainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
