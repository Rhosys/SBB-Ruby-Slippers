package ch.rhosys.sbb.data.local.routing.algorithm

import ch.rhosys.sbb.data.local.routing.gtfs.GtfsTrip

// How late (in seconds) we expect [trip] to arrive at position [stopPos] of route
// [routeIdx]. The router plans every transfer around this: a leg's expected arrival is its
// scheduled arrival plus this delay, and the next leg must leave after that plus the
// transfer's required slack. It also ends up on each leg as FoundLeg.Transit.expectedDelaySeconds
// → Leg.Transit.expectedDelayMinutes, which the connection ranking (JourneyCriteria) uses.
//
// Only NONE exists today. Live delays are already fetched into GtfsRtStore, but keyed by the
// feed's own trip_id / stop_id strings, which GtfsParser drops when it builds the compact
// network (GtfsTrip.id and stop ids are dense ints). TODO: keep the source trip_id and
// stop_id on the network and back this with GtfsRtStore.delaySecondsForStop — falling back
// to a historical per-line delay model when no live update exists for the trip.
fun interface ExpectedDelayProvider {
    fun arrivalDelaySeconds(routeIdx: Int, trip: GtfsTrip, stopPos: Int): Int

    companion object {
        val NONE = ExpectedDelayProvider { _, _, _ -> 0 }
    }
}
