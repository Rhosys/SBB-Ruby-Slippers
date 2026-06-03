package ch.rhosys.sbb.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

data class Stop(
    val stationName: String,
    val stationId: String? = null,
    val scheduledTime: Instant? = null,
    val delayMinutes: Int = 0,
    val platform: String? = null,
    val isCancelled: Boolean = false,
) {
    val effectiveTime: Instant?
        get() = scheduledTime?.plusSeconds(delayMinutes * 60L)

    val isDelayed: Boolean get() = delayMinutes > 0

    fun displayTime(): String =
        (effectiveTime ?: scheduledTime)
            ?.atZone(ZoneId.systemDefault())
            ?.format(TIME_FMT)
            ?: "—"
}
