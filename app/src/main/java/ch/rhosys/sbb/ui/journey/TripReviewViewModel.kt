package ch.rhosys.sbb.ui.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.rhosys.sbb.domain.RouteRepository
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.SearchEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripReviewUiState(
    val connection: Connection?,
    val from: SearchEndpoint?,
    val to: SearchEndpoint?,
)

@HiltViewModel
class TripReviewViewModel @Inject constructor(
    private val holder: TripReviewHolder,
    private val journeyStateHolder: JourneyStateHolder,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    val uiState: StateFlow<TripReviewUiState> = holder.candidate.map { candidate ->
        TripReviewUiState(candidate?.connection, candidate?.from, candidate?.to)
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
                toLat = 0.0,
                toLng = 0.0,
                wasLockedIn = false,
                departureEpoch = candidate.connection.departure.effectiveTime?.epochSecond,
                arrivalEpoch = candidate.connection.arrival.effectiveTime?.epochSecond,
            )
        }
    }

    fun lockIn(): Boolean {
        val candidate = holder.candidate.value ?: return false
        journeyStateHolder.lockIn(candidate.connection, candidate.from, candidate.to)
        viewModelScope.launch {
            routeRepository.recordSearch(
                fromName = candidate.from.displayName(),
                toName = candidate.to.displayName(),
                toLat = 0.0,
                toLng = 0.0,
                wasLockedIn = true,
                departureEpoch = candidate.connection.departure.effectiveTime?.epochSecond,
                arrivalEpoch = candidate.connection.arrival.effectiveTime?.epochSecond,
            )
        }
        holder.clear()
        return true
    }

    fun onLeave() {
        holder.clear()
    }
}
