package ch.rhosys.sbb.ui.stationboard

import ch.rhosys.sbb.data.remote.dto.JourneyEntryDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DepartureDetailHolder @Inject constructor() {
    private val _entry = MutableStateFlow<JourneyEntryDto?>(null)
    val entry: StateFlow<JourneyEntryDto?> = _entry.asStateFlow()

    fun select(entry: JourneyEntryDto) { _entry.value = entry }
    fun clear() { _entry.value = null }
}
