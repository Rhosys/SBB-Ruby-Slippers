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
import ch.rhosys.sbb.domain.PlaceRepository
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneWearDataPusher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journeyStateHolder: JourneyStateHolder,
    private val placeRepository: PlaceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            journeyStateHolder.activeJourney.collect { journey ->
                val payload = journey?.let {
                    val transits = it.connection.legs.filterIsInstance<Leg.Transit>()
                    WearJourneyData(
                        from = it.connection.departure.stationName,
                        to = it.connection.arrival.stationName,
                        departureTime = it.connection.departure.displayTime(),
                        arrivalTime = it.connection.arrival.displayTime(),
                        isActive = true,
                        departureEpochSeconds = (transits.firstOrNull()?.departure ?: it.connection.departure)
                            .effectiveTime?.epochSecond,
                        // Every transit leg but the last ends where the rider changes.
                        transfers = transits.dropLast(1).map { leg ->
                            WearTransfer(leg.arrival.stationName, leg.arrival.effectiveTime?.epochSecond)
                        },
                        arrivalEpochSeconds = it.connection.arrival.effectiveTime?.epochSecond,
                    )
                } ?: WearJourneyData()

                put(WEAR_JOURNEY_PATH, WEAR_JOURNEY_KEY, Json.encodeToString(payload))
            }
        }
        // The watch's "Go to" tile lists these, in the home screen's reading order.
        scope.launch {
            placeRepository.getPlaces().collect { places ->
                val payload = WearPlaces(
                    places.sortedWith(compareBy({ it.gridY }, { it.gridX }))
                        .map { WearPlace(it.id, it.displayName) }
                )
                put(WEAR_PLACES_PATH, WEAR_PLACES_KEY, Json.encodeToString(payload))
            }
        }
    }

    private suspend fun put(path: String, key: String, json: String) {
        runCatching {
            val request = PutDataMapRequest.create(path).apply {
                dataMap.putString(key, json)
                dataMap.putLong("ts", System.currentTimeMillis())
            }
            Wearable.getDataClient(context)
                .putDataItem(request.asPutDataRequest().setUrgent())
                .await()
        }
    }
}
