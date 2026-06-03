package ch.rhosys.sbb.ui.journey

import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JourneyStateHolder @Inject constructor() {
    private val _activeJourney = MutableStateFlow<ActiveJourney?>(null)
    val activeJourney: StateFlow<ActiveJourney?> = _activeJourney

    fun lockIn(connection: Connection, from: SearchEndpoint, to: SearchEndpoint) {
        _activeJourney.value = ActiveJourney(connection, from, to)
    }

    fun clear() {
        _activeJourney.value = null
    }

    data class ActiveJourney(
        val connection: Connection,
        val from: SearchEndpoint,
        val to: SearchEndpoint,
    )
}
