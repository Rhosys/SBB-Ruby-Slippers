package ch.rhosys.sbb.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ConnectionsUiState(
    val placeName: String = "",
    val connections: List<WearConnection> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    // Key of the connection being saved, so its chip shows progress and taps are ignored.
    val savingKey: String? = null,
    val saved: Boolean = false,
)

class ConnectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val phone = PhoneClient(application)

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState

    private var placeId: Long? = null
    // Kept so a save searches from the same spot if the phone has to search again.
    private var location: Pair<Double, Double>? = null

    fun load(placeId: Long) {
        if (this.placeId == placeId) return
        this.placeId = placeId
        refresh()
    }

    fun refresh() {
        val placeId = placeId ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            location = watchLocationOrNull(getApplication())
            runCatching {
                phone.connections(WearConnectionsRequest(placeId, location?.first, location?.second))
            }.onSuccess { response ->
                _uiState.value = ConnectionsUiState(
                    placeName = response.placeName,
                    connections = response.connections,
                    isLoading = false,
                    error = response.error ?: "No upcoming connections".takeIf { response.connections.isEmpty() },
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, error = errorText(it))
            }
        }
    }

    fun saveJourney(connection: WearConnection) {
        val placeId = placeId ?: return
        if (_uiState.value.savingKey != null) return
        _uiState.value = _uiState.value.copy(savingKey = connection.key, error = null)
        viewModelScope.launch {
            runCatching {
                phone.saveJourney(WearSaveJourneyRequest(placeId, connection.key, location?.first, location?.second))
            }.onSuccess { response ->
                _uiState.value = _uiState.value.copy(savingKey = null, saved = response.ok, error = response.error)
            }.onFailure {
                _uiState.value = _uiState.value.copy(savingKey = null, error = errorText(it))
            }
        }
    }

    private fun errorText(t: Throwable) =
        if (t is PhoneUnreachableException) "Phone not connected" else "Couldn't reach phone"
}
