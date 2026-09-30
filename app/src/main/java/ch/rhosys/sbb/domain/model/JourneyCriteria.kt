package ch.rhosys.sbb.domain.model

// Transfer slack beyond this no longer makes one connection better than another — five
// spare minutes absorbs a typical delay, and rewarding more would keep connections that
// just wait around. It's also the slack the local router's second pass insists on (see
// RoutingEngine), so a comfortable alternative is found even when a tight one arrives earlier.
const val COMFORTABLE_TRANSFER_SLACK_SECONDS: Long = 5 * 60L

// The single rule deciding which connections are worth showing, applied to every result
// list whether it came from the local router or the API (see paretoOptimal). A connection
// is only dropped when another one is at least as good on EVERY criterion below and
// strictly better on one — so "ride one stop, then walk back" (leaves earlier, arrives no
// sooner) disappears, but a slower connection with fewer changes or a safer change stays.
data class JourneyCriteria(
    // Leaving the door — later is better.
    val departureEpochSeconds: Long,
    // Reaching the destination, with the last leg's expected delay — earlier is better.
    val arrivalEpochSeconds: Long,
    // Fewer is better.
    val transfers: Int,
    // Spare time at the tightest transfer once walking and the incoming leg's expected
    // delay are taken out, capped at COMFORTABLE_TRANSFER_SLACK_SECONDS (a connection with
    // no transfer counts as fully comfortable) — more is better.
    val transferSlackSeconds: Long,
) {
    fun dominates(other: JourneyCriteria): Boolean {
        val noWorse = departureEpochSeconds >= other.departureEpochSeconds &&
            arrivalEpochSeconds <= other.arrivalEpochSeconds &&
            transfers <= other.transfers &&
            transferSlackSeconds >= other.transferSlackSeconds
        return noWorse && this != other
    }

    companion object {
        fun cappedSlack(tightestTransferSlackSeconds: Long?): Long =
            minOf(tightestTransferSlackSeconds ?: COMFORTABLE_TRANSFER_SLACK_SECONDS, COMFORTABLE_TRANSFER_SLACK_SECONDS)
    }
}

// Keeps every item no other item dominates. Items without criteria (missing times) are
// always kept and never knock anything else out.
fun <T> List<T>.paretoOptimal(criteria: (T) -> JourneyCriteria?): List<T> {
    val all = map(criteria)
    return filterIndexed { i, _ ->
        val mine = all[i] ?: return@filterIndexed true
        all.none { other -> other != null && other.dominates(mine) }
    }
}
