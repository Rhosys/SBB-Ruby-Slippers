package ch.rhosys.sbb.domain.model

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

data class Connection(
    val departure: Stop,
    val arrival: Stop,
    val legs: List<Leg>,
    val transfers: Int,
    // Walk from user's actual origin to the first boarding stop
    val walkToFirstStop: Duration,
    // Walk from the last alighting stop to the user's actual destination
    val walkFromLastStop: Duration,
    // null until the fares feature is built
    val fare: Fare? = null,
) {
    val lineNames: List<String>
        get() = legs.filterIsInstance<Leg.Transit>().map { it.lineName }

    // One entry per change between transit legs — the station where it happens and how
    // much time is actually available once real-time delays on the incoming leg are
    // applied. Computed from consecutive transit legs' own scheduled times, which already
    // reflect any walking between platforms. Also carries the modeled Walk leg's own
    // duration (if any — a same-stop transfer has none) so the UI can tell whether the
    // remaining buffer still covers a walk at that pace, or only a run.
    val transferInfos: List<TransferInfo>
        get() {
            val result = mutableListOf<TransferInfo>()
            val transitIndices = legs.indices.filter { legs[it] is Leg.Transit }
            for (k in 0 until transitIndices.size - 1) {
                val prevIdx = transitIndices[k]
                val nextIdx = transitIndices[k + 1]
                val prev = legs[prevIdx] as Leg.Transit
                val next = legs[nextIdx] as Leg.Transit
                val scheduledArr = prev.arrival.scheduledTime ?: continue
                val scheduledDep = next.departure.scheduledTime ?: continue
                val effectiveArr = prev.arrival.effectiveTime ?: scheduledArr
                val effectiveDep = next.departure.effectiveTime ?: scheduledDep
                val walkLeg = (legs.getOrNull(prevIdx + 1) as? Leg.Walk)?.takeIf { nextIdx == prevIdx + 2 }
                result += TransferInfo(
                    stationName = prev.arrival.stationName,
                    fromLine = prev.lineName,
                    toLine = next.lineName,
                    scheduledBufferMinutes = Duration.between(scheduledArr, scheduledDep).toMinutes().toInt(),
                    effectiveBufferMinutes = Duration.between(effectiveArr, effectiveDep).toMinutes().toInt(),
                    walkLegMinutes = walkLeg?.durationMinutes,
                )
            }
            return result
        }

    // Whether, leaving right now, the walk to the first stop no longer fits at a normal
    // pace but still does at a run. Same rule as TransferInfo.requiresRunning, with "now"
    // standing in for the incoming leg's arrival.
    fun requiresRunningToFirstStop(now: Instant, walkingPaceKmh: Float, runningPaceKmh: Float): Boolean {
        val dep = departure.effectiveTime ?: return false
        val walkSeconds = walkToFirstStop.seconds
        if (walkSeconds <= 0 || walkingPaceKmh <= 0f || runningPaceKmh <= walkingPaceKmh) return false
        val slackSeconds = Duration.between(now, dep).seconds
        val runSeconds = walkSeconds * (walkingPaceKmh / runningPaceKmh)
        return slackSeconds < walkSeconds && slackSeconds >= runSeconds
    }

    // The longest run the rider must make to keep this connection — across the walk to
    // the first stop and every transfer — or null when every leg can still be walked.
    fun longestRequiredRun(now: Instant, walkingPaceKmh: Float, runningPaceKmh: Float): RequiredRun? {
        val runs = mutableListOf<RequiredRun>()
        if (requiresRunningToFirstStop(now, walkingPaceKmh, runningPaceKmh)) {
            runs += RequiredRun(departure.stationName, runMinutes(walkToFirstStop.toMinutes().toInt(), walkingPaceKmh, runningPaceKmh))
        }
        transferInfos.filter { it.requiresRunning(walkingPaceKmh, runningPaceKmh) }.forEach {
            runs += RequiredRun(it.stationName, runMinutes(it.walkLegMinutes ?: 0, walkingPaceKmh, runningPaceKmh))
        }
        return runs.maxByOrNull { it.runMinutes }
    }

    // Displayed as the middle value on the connection card (node 0 → node N).
    val transitDuration: Duration?
        get() {
            val dep = departure.scheduledTime ?: return null
            val arr = arrival.scheduledTime ?: return null
            return Duration.between(dep, arr)
        }

    // Used only for Pareto optimisation — never displayed.
    // Display uses walkToFirstStop, transitDuration, and walkFromLastStop separately.
    val optimisationDuration: Duration?
        get() = transitDuration?.let { walkToFirstStop + it + walkFromLastStop }

    // Identifies "the same physical connection" across separate API responses — stable
    // even as real-time delay fields on Stop change, unlike full structural equality.
    val stableKey: String
        get() = "${departure.scheduledTime}-${arrival.scheduledTime}-${lineNames.joinToString()}"
}

data class TransferInfo(
    val stationName: String,
    val fromLine: String,
    val toLine: String,
    // Buffer as planned, ignoring any real-time delay.
    val scheduledBufferMinutes: Int,
    // Buffer once the incoming leg's actual delay is applied — can be negative if the
    // connecting leg is already missed.
    val effectiveBufferMinutes: Int,
    // Duration of the modeled Walk leg between the two transit legs, at whatever
    // walking pace was in effect when the connection was generated. Null when the
    // transfer is at the same stop (nothing to walk).
    val walkLegMinutes: Int?,
) {
    // Two minutes or less of actual slack — including already missed (negative) — isn't
    // enough to trust the connection, so the UI must never show this in a "safe" color.
    val isAtRisk: Boolean get() = effectiveBufferMinutes <= 2

    // True when the delay-adjusted buffer no longer covers walking the transfer at a
    // normal pace, but still covers it at a run — i.e. the connection is only still
    // makeable if the rider actually runs, not just walks briskly. Running is faster
    // than walking, so the run time is the walk time scaled by the pace ratio.
    fun requiresRunning(walkingPaceKmh: Float, runningPaceKmh: Float): Boolean {
        val walkMinutes = walkLegMinutes ?: return false
        if (walkMinutes <= 0 || walkingPaceKmh <= 0f || runningPaceKmh <= walkingPaceKmh) return false
        val runMinutes = walkMinutes * (walkingPaceKmh / runningPaceKmh)
        return effectiveBufferMinutes < walkMinutes && effectiveBufferMinutes >= runMinutes
    }
}

data class RequiredRun(
    // Where the run ends — the stop to board at.
    val stationName: String,
    val runMinutes: Int,
)

// Walk time scaled to running pace, rounded up so a displayed run is never optimistic.
private fun runMinutes(walkMinutes: Int, walkingPaceKmh: Float, runningPaceKmh: Float): Int =
    maxOf(1, ceil(walkMinutes * walkingPaceKmh.toDouble() / runningPaceKmh).toInt())
