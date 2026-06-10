package ch.rhosys.sbb.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

class WearDataReceiver : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path == WEAR_JOURNEY_PATH) {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WEAR_JOURNEY_KEY) ?: ""
                latestJourney.value = if (json.isEmpty()) WearJourneyData()
                    else runCatching { Json.decodeFromString<WearJourneyData>(json) }.getOrDefault(WearJourneyData())
            }
        }
        dataEvents.release()
    }

    companion object {
        val latestJourney = MutableStateFlow(WearJourneyData())
    }
}
