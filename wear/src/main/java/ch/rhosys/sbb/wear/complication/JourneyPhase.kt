package ch.rhosys.sbb.wear.complication

import ch.rhosys.sbb.wear.WearJourneyData

// What the journey complication shows, from `start` until the next phase begins.
sealed class JourneyPhase {
    abstract val start: Long

    // Not boarded yet: show when the first vehicle leaves.
    data class BeforeDeparture(override val start: Long, val departureEpochSeconds: Long, val stationName: String) :
        JourneyPhase()

    // On board with a change ahead: count down to arriving at the change station.
    data class ToTransfer(override val start: Long, val arrivalEpochSeconds: Long, val stationName: String) :
        JourneyPhase()

    // On the last leg: count down to the final stop.
    data class ToArrival(override val start: Long, val arrivalEpochSeconds: Long, val stationName: String) :
        JourneyPhase()
}

// The journey's phases in order, each starting when the previous one's target is reached.
// Empty without an active journey; nothing is shown once the last phase's target passes.
fun journeyPhases(data: WearJourneyData): List<JourneyPhase> {
    if (!data.isActive) return emptyList()
    val arrival = data.arrivalEpochSeconds ?: return emptyList()
    val phases = mutableListOf<JourneyPhase>()
    var start = Long.MIN_VALUE
    data.departureEpochSeconds?.let { departure ->
        phases += JourneyPhase.BeforeDeparture(start, departure, data.from)
        start = departure
    }
    data.transfers.forEach { transfer ->
        val at = transfer.arrivalEpochSeconds ?: return@forEach
        if (at <= start) return@forEach
        phases += JourneyPhase.ToTransfer(start, at, transfer.stationName)
        start = at
    }
    if (arrival > start) phases += JourneyPhase.ToArrival(start, arrival, data.to)
    return phases
}

// When the last phase's target is reached and the complication goes back to empty.
fun JourneyPhase.end(): Long = when (this) {
    is JourneyPhase.BeforeDeparture -> departureEpochSeconds
    is JourneyPhase.ToTransfer -> arrivalEpochSeconds
    is JourneyPhase.ToArrival -> arrivalEpochSeconds
}
