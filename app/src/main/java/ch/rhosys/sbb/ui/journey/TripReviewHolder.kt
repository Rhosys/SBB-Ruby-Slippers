package ch.rhosys.sbb.ui.journey

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripReviewHolder @Inject constructor() {

    data class Candidate(
        val connection: Connection,
        val from: SearchEndpoint,
        val to: SearchEndpoint,
    )

    private val _candidate = MutableStateFlow<Candidate?>(null)
    val candidate: StateFlow<Candidate?> = _candidate

    fun set(connection: Connection, from: SearchEndpoint, to: SearchEndpoint) {
        _candidate.value = Candidate(connection, from, to)
    }

    fun clear() {
        _candidate.value = null
    }
}
