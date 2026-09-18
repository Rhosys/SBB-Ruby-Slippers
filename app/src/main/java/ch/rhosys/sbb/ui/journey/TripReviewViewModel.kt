package ch.rhosys.sbb.ui.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripReviewUiState(
    val connection: Connection?,
    val from: SearchEndpoint?,
    val to: SearchEndpoint?,
    // Used to tell whether a tight transfer still covers a normal walk or only a run.
    val walkingPaceKmh: Float = 6f,
    val runningPaceKmh: Float = 10f,
)

@HiltViewModel
class TripReviewViewModel @Inject constructor(
    private val holder: TripReviewHolder,
    private val journeyStateHolder: JourneyStateHolder,
    private val routeRepository: RouteRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<TripReviewUiState> = combine(
        holder.candidate,
        userPreferencesRepository.walkingPaceKmh,
        userPreferencesRepository.runningPaceKmh,
    ) { candidate, walkingPaceKmh, runningPaceKmh ->
        TripReviewUiState(candidate?.connection, candidate?.from, candidate?.to, walkingPaceKmh, runningPaceKmh)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TripReviewUiState(null, null, null),
    )

    init {
        recordView()
    }

    private fun recordView() {
        val candidate = holder.candidate.value ?: return
        viewModelScope.launch {
            routeRepository.recordSearch(
                fromName = candidate.from.displayName(),
                toName = candidate.to.displayName(),
                toLat = candidate.to.latOrNull() ?: 0.0,
                toLng = candidate.to.lngOrNull() ?: 0.0,
                wasLockedIn = false,
                departureEpoch = candidate.connection.departure.effectiveTime?.epochSecond,
                arrivalEpoch = candidate.connection.arrival.effectiveTime?.epochSecond,
            )
        }
    }

    fun lockIn(): Boolean {
        val candidate = holder.candidate.value ?: return false
        // Delegated to JourneyStateHolder's own scope rather than viewModelScope: this
        // ViewModel is cleared as soon as navigation pops TripReview off the back stack,
        // which would cancel the record+lock-in work before it finished.
        journeyStateHolder.startJourney(candidate.connection, candidate.from, candidate.to)
        holder.clear()
        return true
    }

    fun onLeave() {
        holder.clear()
    }
}
