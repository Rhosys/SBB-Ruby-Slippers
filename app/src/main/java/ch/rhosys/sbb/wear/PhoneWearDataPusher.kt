package ch.rhosys.sbb.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneWearDataPusher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journeyStateHolder: JourneyStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            journeyStateHolder.activeJourney.collect { journey ->
                val payload = journey?.let {
                    WearJourneyData(
                        from = it.connection.departure.stationName,
                        to = it.connection.arrival.stationName,
                        departureTime = it.connection.departure.displayTime(),
                        arrivalTime = it.connection.arrival.displayTime(),
                        isActive = true,
                    )
                } ?: WearJourneyData()

                runCatching {
                    val request = PutDataMapRequest.create(WEAR_JOURNEY_PATH).apply {
                        dataMap.putString(WEAR_JOURNEY_KEY, Json.encodeToString(payload))
                        dataMap.putLong("ts", System.currentTimeMillis())
                    }
                    Wearable.getDataClient(context)
                        .putDataItem(request.asPutDataRequest().setUrgent())
                        .await()
                }
            }
        }
    }
}
