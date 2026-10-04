package ch.rhosys.sbb.wear

import kotlinx.serialization.Serializable

// Phone ↔ watch protocol for the "Go to" tile. Kept identical in :app and :wear — both
// modules declare it in this same package, like WearJourneyData.

// Data item: the phone's home-screen places, in home-screen reading order.
const val WEAR_PLACES_PATH = "/sbb/places"
const val WEAR_PLACES_KEY = "json"

// Requests (MessageClient.sendRequest) the watch sends the phone; JSON in, JSON out.
const val WEAR_CONNECTIONS_PATH = "/sbb/connections"
const val WEAR_SAVE_JOURNEY_PATH = "/sbb/save-journey"

// How many upcoming connections the watch shows per place.
const val WEAR_CONNECTION_COUNT = 3

@Serializable
data class WearPlace(val id: Long, val name: String)

@Serializable
data class WearPlaces(val places: List<WearPlace> = emptyList())

// lat/lng are the watch's own fix when it has one; otherwise the phone uses its location.
@Serializable
data class WearConnectionsRequest(val placeId: Long, val lat: Double? = null, val lng: Double? = null)

@Serializable
data class WearConnection(
    // Connection.stableKey — how a save request names the connection to lock in.
    val key: String,
    val departureEpochSeconds: Long? = null,
    val departureTime: String,
    val arrivalTime: String,
    // Where the first vehicle is boarded, e.g. "Zürich HB".
    val boardingStop: String,
    val platform: String? = null,
    val lines: List<String> = emptyList(),
    val transfers: Int = 0,
    val delayMinutes: Int = 0,
)

@Serializable
data class WearConnectionsResponse(
    val placeName: String = "",
    val connections: List<WearConnection> = emptyList(),
    val error: String? = null,
)

@Serializable
data class WearSaveJourneyRequest(
    val placeId: Long,
    val key: String,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class WearSaveJourneyResponse(val ok: Boolean, val error: String? = null)
