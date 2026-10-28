package ch.rhosys.sbb.wear

import androidx.wear.tiles.TileService
import ch.rhosys.sbb.wear.complication.JourneyComplicationService
import ch.rhosys.sbb.wear.tile.PlacesTileService
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
                JourneyComplicationService.requestUpdate(this)
            }
            if (event.dataItem.uri.path == WEAR_PLACES_PATH) {
                TileService.getUpdater(this).requestUpdate(PlacesTileService::class.java)
            }
        }
        dataEvents.release()
    }

    companion object {
        val latestJourney = MutableStateFlow(WearJourneyData())
    }
}
