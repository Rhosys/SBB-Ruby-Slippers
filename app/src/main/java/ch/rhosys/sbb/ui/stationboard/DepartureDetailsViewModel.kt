package ch.rhosys.sbb.ui.stationboard

import androidx.lifecycle.ViewModel
import ch.rhosys.sbb.data.remote.dto.JourneyEntryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DepartureDetailsViewModel @Inject constructor(
    private val holder: DepartureDetailHolder,
) : ViewModel() {
    val entry: StateFlow<JourneyEntryDto?> = holder.entry

    override fun onCleared() {
        super.onCleared()
        holder.clear()
    }
}
