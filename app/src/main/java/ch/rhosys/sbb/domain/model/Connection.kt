package ch.rhosys.sbb.domain.model

import java.time.Duration

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
    // reflect any walking between platforms, rather than from the (undetailed) Walk leg.
    val transferInfos: List<TransferInfo>
        get() = legs.filterIsInstance<Leg.Transit>().zipWithNext().mapNotNull { (prev, next) ->
            val scheduledArr = prev.arrival.scheduledTime ?: return@mapNotNull null
            val scheduledDep = next.departure.scheduledTime ?: return@mapNotNull null
            val effectiveArr = prev.arrival.effectiveTime ?: scheduledArr
            val effectiveDep = next.departure.effectiveTime ?: scheduledDep
            TransferInfo(
                stationName = prev.arrival.stationName,
                fromLine = prev.lineName,
                toLine = next.lineName,
                scheduledBufferMinutes = Duration.between(scheduledArr, scheduledDep).toMinutes().toInt(),
                effectiveBufferMinutes = Duration.between(effectiveArr, effectiveDep).toMinutes().toInt(),
            )
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
) {
    // Two minutes or less of actual slack — including already missed (negative) — isn't
    // enough to trust the connection, so the UI must never show this in a "safe" color.
    val isAtRisk: Boolean get() = effectiveBufferMinutes <= 2
}
