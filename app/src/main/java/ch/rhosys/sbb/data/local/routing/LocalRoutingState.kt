package ch.rhosys.sbb.data.local.routing

import ch.rhosys.sbb.domain.model.Connection

sealed class LocalRoutingState {
    object NoData : LocalRoutingState()
    object Loading : LocalRoutingState()
    data class Results(
        val connections: List<Connection>,
        val isComplete: Boolean,
    ) : LocalRoutingState()
    data class NoResults(val reason: String = "") : LocalRoutingState()
}
