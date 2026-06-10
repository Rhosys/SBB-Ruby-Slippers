package ch.rhosys.sbb.wear

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

class WearDataViewModel(application: Application) : AndroidViewModel(application) {

    private val dataClient = Wearable.getDataClient(application)

    private val _journeyData = MutableStateFlow(WearJourneyData())
    val journeyData: StateFlow<WearJourneyData> = _journeyData

    private val listener = com.google.android.gms.wearable.DataClient.OnDataChangedListener { events ->
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WEAR_JOURNEY_PATH) {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WEAR_JOURNEY_KEY) ?: ""
                _journeyData.value = if (json.isEmpty()) WearJourneyData()
                    else runCatching { Json.decodeFromString<WearJourneyData>(json) }.getOrDefault(WearJourneyData())
            }
        }
    }

    init {
        dataClient.addListener(listener)
        fetchFromDataLayer()
    }

    private fun fetchFromDataLayer() {
        viewModelScope.launch {
            runCatching {
                val items = dataClient.getDataItems(Uri.parse("wear://*$WEAR_JOURNEY_PATH")).await()
                items.forEach { item ->
                    val json = DataMapItem.fromDataItem(item).dataMap.getString(WEAR_JOURNEY_KEY) ?: ""
                    if (json.isNotEmpty()) {
                        _journeyData.value = runCatching { Json.decodeFromString<WearJourneyData>(json) }
                            .getOrDefault(WearJourneyData())
                    }
                }
                items.release()
            }
        }
    }

    override fun onCleared() {
        dataClient.removeListener(listener)
    }
}
