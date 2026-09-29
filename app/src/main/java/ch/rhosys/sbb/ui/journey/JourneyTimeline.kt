package ch.rhosys.sbb.ui.journey

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// A single leg of the door-to-door trip with absolute start/end times, including the
// boundary walks (to the first stop, from the last stop) which Connection only stores
// as bare Durations. Anchoring those walks to the adjacent transit times is what lets
// the notification show one continuous progress bar across the whole trip.
data class JourneySegment(
    val start: Instant,
    val end: Instant,
    val isWalk: Boolean,
    val lineName: String?,
    val destinationName: String,
    val platform: String?,
    // False only for the very last segment — every other segment boundary is a change
    // (a transfer, or stepping off transit to start the final walk).
    val isTransferPoint: Boolean,
    // The ride itself for transit segments (stops, delays, direction); null for walks.
    val transit: Leg.Transit? = null,
)

data class JourneyProgress(
    val fractionComplete: Float,
    // The segment happening right now (or the final one, once the trip is over).
    // Its `end` is the time of the next change (or arrival, if it's the last segment).
    val current: JourneySegment,
    // The segment starting right after `current`'s change point — null once `current` is
    // the last segment (nothing to board next, the trip just ends).
    val next: JourneySegment?,
    val timeToNextChange: Duration,
    // Fraction (0..1) along the whole trip of each transfer point, in order.
    val transferFractions: List<Float>,
    val tripStart: Instant,
    val tripEnd: Instant,
)

fun buildJourneyTimeline(connection: Connection): List<JourneySegment> {
    val segments = mutableListOf<JourneySegment>()
    var cursor: Instant? = null

    if (connection.walkToFirstStop > Duration.ZERO) {
        val end = connection.departure.effectiveTime
        if (end != null) {
            segments += JourneySegment(
                start = end - connection.walkToFirstStop,
                end = end,
                isWalk = true,
                lineName = null,
                destinationName = connection.departure.stationName,
                platform = connection.departure.platform,
                isTransferPoint = true,
            )
            cursor = end
        }
    }

    for (leg in connection.legs) {
        when (leg) {
            is Leg.Transit -> {
                val start = leg.departure.effectiveTime ?: cursor ?: continue
                val end = leg.arrival.effectiveTime ?: start
                segments += JourneySegment(
                    start = start,
                    end = end,
                    isWalk = false,
                    lineName = leg.lineName,
                    destinationName = leg.arrival.stationName,
                    platform = leg.departure.platform,
                    isTransferPoint = true,
                    transit = leg,
                )
                cursor = end
            }
            is Leg.Walk -> {
                val start = cursor ?: continue
                val end = start.plusSeconds(leg.durationMinutes * 60L)
                segments += JourneySegment(
                    start = start,
                    end = end,
                    isWalk = true,
                    lineName = null,
                    destinationName = leg.toName,
                    platform = null,
                    isTransferPoint = true,
                )
                cursor = end
            }
        }
    }

    if (connection.walkFromLastStop > Duration.ZERO) {
        val start = cursor
        if (start != null) {
            segments += JourneySegment(
                start = start,
                end = start + connection.walkFromLastStop,
                isWalk = true,
                lineName = null,
                destinationName = connection.arrival.stationName,
                platform = null,
                isTransferPoint = true,
            )
        }
    }

    // Only the very last segment boundary is "arrival", not a change.
    return segments.mapIndexed { index, segment ->
        if (index == segments.lastIndex) segment.copy(isTransferPoint = false) else segment
    }
}

fun journeyProgress(segments: List<JourneySegment>, now: Instant): JourneyProgress? {
    if (segments.isEmpty()) return null
    val tripStart = segments.first().start
    val tripEnd = segments.last().end
    val totalMs = (tripEnd.toEpochMilli() - tripStart.toEpochMilli()).coerceAtLeast(1L)
    val elapsedMs = (now.toEpochMilli() - tripStart.toEpochMilli()).coerceIn(0L, totalMs)
    val fraction = elapsedMs.toFloat() / totalMs

    val currentIndex = segments.indexOfFirst { now.isBefore(it.end) }.let { if (it == -1) segments.lastIndex else it }
    val current = segments[currentIndex]
    val next = segments.getOrNull(currentIndex + 1)
    val timeToChange = Duration.between(now, current.end).let { if (it.isNegative) Duration.ZERO else it }

    val transferFractions = segments.filter { it.isTransferPoint }.map { segment ->
        ((segment.end.toEpochMilli() - tripStart.toEpochMilli()).toFloat() / totalMs).coerceIn(0f, 1f)
    }

    return JourneyProgress(
        fractionComplete = fraction,
        current = current,
        next = next,
        timeToNextChange = timeToChange,
        transferFractions = transferFractions,
        tripStart = tripStart,
        tripEnd = tripEnd,
    )
}

private val JOURNEY_CLOCK = DateTimeFormatter.ofPattern("HH:mm")

fun formatJourneyClock(instant: Instant): String = instant.atZone(ZoneId.systemDefault()).format(JOURNEY_CLOCK)

fun formatJourneyMinutes(duration: Duration): String {
    val minutes = maxOf(0, duration.toMinutes())
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "$minutes min"
}

/** Where [segment] ends: the rider's own destination for the last one, else its stop. */
private fun JourneyProgress.endName(segment: JourneySegment, finalDestination: String?): String =
    if (!segment.isTransferPoint && finalDestination != null) finalDestination else segment.destinationName

/** What the rider is doing at [now], e.g. "On S12 → Zürich HB" or "Leave in 4 min". */
fun JourneyProgress.headline(now: Instant, finalDestination: String? = null): String {
    val ride = current.transit
    return when {
        !now.isBefore(tripEnd) -> "You've arrived"
        now.isBefore(tripStart) -> "Leave in ${formatJourneyMinutes(Duration.between(now, tripStart))}"
        ride != null -> "On ${ride.lineName} → ${ride.direction}"
        else -> "Walk to ${endName(current, finalDestination)}"
    }
}

/** What happens next and when, e.g. "Get off at Zürich HB in 12 min (08:29)". */
fun JourneyProgress.nextStep(now: Instant, finalDestination: String? = null): String {
    val next = next
    val end = endName(current, finalDestination)
    val inTime = "in ${formatJourneyMinutes(timeToNextChange)} (${formatJourneyClock(current.end)})"
    return when {
        !now.isBefore(tripEnd) -> "Arrived at $end ${formatJourneyClock(tripEnd)}"
        now.isBefore(tripStart) -> buildString {
            append(current.transit?.let { "Board ${it.lineName}" } ?: "Walk to $end")
            current.platform?.let { append(" · Pl. $it") }
            append(" at ${formatJourneyClock(current.start)}")
        }
        next == null -> "Arrive $end $inTime"
        current.transit != null -> "Get off at $end $inTime"
        else -> buildString {
            append(next.transit?.let { "Board ${it.lineName} → ${it.direction}" } ?: "Continue on foot")
            next.platform?.let { append(" · Pl. $it") }
            append(" at ${formatJourneyClock(next.start)} · in ${formatJourneyMinutes(timeToNextChange)}")
        }
    }
}

/** One line per segment for compact lists, e.g. "S12 → Zürich HB · Pl. 3 · arr 08:29 (+2)". */
fun JourneySegment.summary(finalDestination: String? = null): String {
    val ride = transit
    if (ride == null) {
        val to = if (!isTransferPoint && finalDestination != null) finalDestination else destinationName
        return "Walk ${formatJourneyMinutes(Duration.between(start, end))} to $to"
    }
    return buildString {
        append("${ride.lineName} → ${ride.arrival.stationName}")
        ride.departure.platform?.let { append(" · Pl. $it") }
        append(" · arr ${formatJourneyClock(end)}")
        if (ride.arrival.isDelayed) append(" (+${ride.arrival.delayMinutes})")
        if (ride.departure.isCancelled) append(" · CANCELLED")
    }
}
