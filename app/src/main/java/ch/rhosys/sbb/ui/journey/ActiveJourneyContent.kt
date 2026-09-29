package ch.rhosys.sbb.ui.journey

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Stop
import java.time.Duration
import java.time.Instant

// Where a segment of the trip stands relative to now.
private enum class StepState { DONE, CURRENT, UPCOMING }

/** Headline card: what's happening now, what's next, and the whole trip at a glance. */
@Composable
internal fun JourneyStatusCard(connection: Connection, segments: List<JourneySegment>, now: Instant) {
    val progress = journeyProgress(segments, now) ?: return
    val current = progress.current
    val arrived = !now.isBefore(progress.tripEnd)

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepIcon(
                    icon = if (current.isWalk) Icons.AutoMirrored.Filled.DirectionsWalk else Icons.Default.Train,
                    background = MaterialTheme.colorScheme.primary,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = 44,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        progress.headline(now),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        progress.nextStep(now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            JourneyTrack(segments = segments, now = now)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue("Start", formatClock(progress.tripStart))
                LabeledValue(
                    "Remaining",
                    if (arrived) "—" else formatMinutes(Duration.between(maxOf(now, progress.tripStart), progress.tripEnd)),
                    alignment = Alignment.CenterHorizontally,
                )
                LabeledValue(
                    "Arrive",
                    formatClock(progress.tripEnd) +
                        if (connection.arrival.isDelayed) " (+${connection.arrival.delayMinutes})" else "",
                    alignment = Alignment.End,
                    isWarning = connection.arrival.isDelayed,
                )
            }
        }
    }
}

/** Duration, changes, walking and delay as a row of chips. */
@Composable
internal fun JourneyStatsRow(connection: Connection, segments: List<JourneySegment>) {
    val total = segments.firstOrNull()?.let { Duration.between(it.start, segments.last().end) }
    val walking = segments.filter { it.isWalk }.fold(Duration.ZERO) { sum, s -> sum + Duration.between(s.start, s.end) }
    val maxDelay = segments.mapNotNull { it.transit }
        .maxOfOrNull { maxOf(it.departure.delayMinutes, it.arrival.delayMinutes) } ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        total?.let { StatChip("Total", formatMinutes(it)) }
        StatChip("Changes", connection.transfers.toString())
        if (walking > Duration.ZERO) StatChip("Walking", formatMinutes(walking))
        if (maxDelay > 0) StatChip("Delay", "+$maxDelay min", isWarning = true)
    }
}

/** One row of the vertical trip timeline. */
@Composable
internal fun JourneyTimelineRow(
    connection: Connection,
    segments: List<JourneySegment>,
    index: Int,
    now: Instant,
) {
    val segment = segments[index]
    val state = when {
        !now.isBefore(segment.end) -> StepState.DONE
        !now.isBefore(segment.start) -> StepState.CURRENT
        else -> StepState.UPCOMING
    }
    val fromName = segments.getOrNull(index - 1)?.destinationName
        ?: segment.transit?.departure?.stationName
        ?: "Start"
    val railColor = when {
        state == StepState.DONE -> MaterialTheme.colorScheme.outlineVariant
        segment.isWalk -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val contentAlpha = if (state == StepState.DONE) 0.55f else 1f
    // Buffer for the change at the end of this ride, if another ride follows.
    val transfer = segment.transit?.let { ride ->
        connection.transferInfos.firstOrNull { it.fromLine == ride.lineName && it.stationName == ride.arrival.stationName }
    }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Time column
        Column(Modifier.width(56.dp).padding(top = 2.dp), horizontalAlignment = Alignment.End) {
            val departure = segment.transit?.departure
            if (departure != null && departure.isDelayed) {
                Text(
                    departure.scheduledTime?.let(::formatClock) ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatClock(segment.start),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (departure?.isDelayed == true) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
        }

        // Rail: node + line down to the next step
        Box(Modifier.width(32.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .width(if (segment.isWalk) 2.dp else 4.dp)
                    .fillMaxHeight()
                    .background(railColor),
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(if (state == StepState.CURRENT) 18.dp else 14.dp)
                    .background(
                        if (state == StepState.CURRENT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        CircleShape,
                    )
                    .padding(3.dp)
                    .background(railColor, CircleShape),
            )
        }

        // Content
        Column(
            Modifier.weight(1f).padding(start = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val ride = segment.transit
            if (ride != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineBadge(ride.lineName, dimmed = state == StepState.DONE)
                    Text(
                        "→ ${ride.direction}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    )
                }
                Text(
                    "${ride.departure.stationName} → ${ride.arrival.stationName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ride.departure.platform?.let { InfoChip("Pl. $it") }
                    InfoChip(formatMinutes(Duration.between(segment.start, segment.end)))
                    ride.operator?.let { InfoChip(it) }
                    if (ride.departure.isDelayed) InfoChip("+${ride.departure.delayMinutes} min", isWarning = true)
                    if (ride.departure.isCancelled) InfoChip("Cancelled", isWarning = true)
                }
                if (ride.intermediateStops.isNotEmpty()) {
                    IntermediateStops(ride.intermediateStops, now, contentAlpha)
                }
                Text(
                    buildString {
                        append("Arrive ${ride.arrival.stationName} ${formatClock(segment.end)}")
                        ride.arrival.platform?.let { append(" · Pl. $it") }
                        if (ride.arrival.isDelayed) append(" (+${ride.arrival.delayMinutes})")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ride.arrival.isDelayed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
                transfer?.let { info ->
                    val tight = info.effectiveBufferMinutes < 2
                    InfoChip(
                        "Change: ${info.effectiveBufferMinutes} min to ${info.toLine}" +
                            (info.walkLegMinutes?.let { " (walk $it min)" } ?: ""),
                        isWarning = tight,
                    )
                }
            } else {
                Text(
                    "Walk ${formatMinutes(Duration.between(segment.start, segment.end))}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Text(
                    "$fromName → ${segment.destinationName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
                segment.platform?.let { InfoChip("Pl. $it") }
            }
        }
    }
}

// Collapsed "N stops" line that expands into each stop with its time; passed stops fade.
@Composable
private fun IntermediateStops(stops: List<Stop>, now: Instant, contentAlpha: Float) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Text(
            if (expanded) "▲ Hide stops" else "▼ ${stops.size} stops",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp),
        )
        if (expanded) {
            stops.forEach { stop ->
                val passed = stop.effectiveTime?.isBefore(now) == true
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        stop.displayTime(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(44.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (passed) 0.5f else contentAlpha),
                    )
                    Text(
                        stop.stationName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (passed) 0.5f else contentAlpha),
                    )
                }
            }
        }
    }
}

/**
 * The whole trip as one bar: each segment's share of the trip, rides solid and walks thin,
 * a tick at every change, and a dot where you are now.
 */
@Composable
private fun JourneyTrack(segments: List<JourneySegment>, now: Instant) {
    val rideColor = MaterialTheme.colorScheme.primary
    val walkColor = MaterialTheme.colorScheme.outline
    val doneColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.onPrimaryContainer
    val nowColor = MaterialTheme.colorScheme.tertiary
    val start = segments.first().start.toEpochMilli()
    val total = (segments.last().end.toEpochMilli() - start).coerceAtLeast(1L).toFloat()
    val nowFraction = ((now.toEpochMilli() - start) / total).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val centerY = size.height / 2
        segments.forEach { segment ->
            val x0 = (segment.start.toEpochMilli() - start) / total * size.width
            val x1 = (segment.end.toEpochMilli() - start) / total * size.width
            val barHeight = if (segment.isWalk) 4.dp.toPx() else 8.dp.toPx()
            val done = !now.isBefore(segment.end)
            drawRoundRect(
                color = if (done) doneColor else if (segment.isWalk) walkColor else rideColor,
                topLeft = Offset(x0, centerY - barHeight / 2),
                size = Size((x1 - x0).coerceAtLeast(1f), barHeight),
                cornerRadius = CornerRadius(barHeight / 2),
            )
        }
        segments.filter { it.isTransferPoint }.forEach { segment ->
            val x = (segment.end.toEpochMilli() - start) / total * size.width
            drawCircle(color = markerColor, radius = 5.dp.toPx(), center = Offset(x, centerY))
            drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(x, centerY))
        }
        drawCircle(color = nowColor, radius = 7.dp.toPx(), center = Offset(nowFraction * size.width, centerY))
        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(nowFraction * size.width, centerY))
    }
}

@Composable
private fun StepIcon(icon: ImageVector, background: Color, tint: Color, size: Int) {
    Box(
        Modifier.size(size.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun LineBadge(lineName: String, dimmed: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (dimmed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
    ) {
        Text(
            lineName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun InfoChip(text: String, isWarning: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, isWarning: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    alignment: Alignment.Horizontal = Alignment.Start,
    isWarning: Boolean = false,
) {
    Column(horizontalAlignment = alignment) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun formatClock(instant: Instant): String = formatJourneyClock(instant)

private fun formatMinutes(duration: Duration): String = formatJourneyMinutes(duration)
