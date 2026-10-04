package ch.rhosys.sbb.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PhoneUnreachableException : Exception("Phone not connected")

// Talks to the phone app over the Wearable Data Layer.
class PhoneClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun connections(request: WearConnectionsRequest): WearConnectionsResponse =
        json.decodeFromString(send(WEAR_CONNECTIONS_PATH, json.encodeToString(request)))

    suspend fun saveJourney(request: WearSaveJourneyRequest): WearSaveJourneyResponse =
        json.decodeFromString(send(WEAR_SAVE_JOURNEY_PATH, json.encodeToString(request)))

    // The phone's home-screen places, as last synced — available even while it's out of range.
    suspend fun places(): List<WearPlace> = runCatching {
        val items = Wearable.getDataClient(appContext).getDataItems(Uri.parse("wear://*$WEAR_PLACES_PATH")).await()
        val places = items.firstNotNullOfOrNull { item ->
            DataMapItem.fromDataItem(item).dataMap.getString(WEAR_PLACES_KEY)?.let(::decodePlaces)
        }
        items.release()
        places.orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun send(path: String, body: String): String = withTimeout(REQUEST_TIMEOUT_MS) {
        val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: throw PhoneUnreachableException()
        Wearable.getMessageClient(appContext)
            .sendRequest(phone.id, path, body.encodeToByteArray())
            .await()
            .decodeToString()
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun decodePlaces(raw: String): List<WearPlace> =
            runCatching { json.decodeFromString<WearPlaces>(raw).places }.getOrDefault(emptyList())
    }
}
