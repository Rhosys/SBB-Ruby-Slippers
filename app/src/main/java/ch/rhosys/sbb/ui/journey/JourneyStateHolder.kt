package ch.rhosys.sbb.ui.journey

import ch.rhosys.sbb.data.local.preferences.PersistedJourney
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JourneyStateHolder @Inject constructor(
    private val prefs: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeJourney = MutableStateFlow<ActiveJourney?>(null)
    val activeJourney: StateFlow<ActiveJourney?> = _activeJourney

    fun lockIn(connection: Connection, from: SearchEndpoint, to: SearchEndpoint) {
        _activeJourney.value = ActiveJourney(connection, from, to)
        val arrivalEpoch = connection.arrival.effectiveTime?.epochSecond ?: return
        scope.launch {
            prefs.persistActiveJourney(
                PersistedJourney(
                    fromName = from.displayName(),
                    toName = to.displayName(),
                    arrivalEpoch = arrivalEpoch,
                )
            )
        }
    }

    fun clear() {
        _activeJourney.value = null
        scope.launch { prefs.clearActiveJourney() }
    }

    data class ActiveJourney(
        val connection: Connection,
        val from: SearchEndpoint,
        val to: SearchEndpoint,
    )
}
