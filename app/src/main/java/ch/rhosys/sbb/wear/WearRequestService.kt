package ch.rhosys.sbb.wear

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

// Receives the watch's requests (MessageClient.sendRequest) and replies with JSON.
@AndroidEntryPoint
class WearRequestService : WearableListenerService() {

    @Inject lateinit var provider: WearConnectionsProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        val body = request.decodeToString()
        val result = TaskCompletionSource<ByteArray>()
        scope.launch {
            runCatching {
                when (path) {
                    WEAR_CONNECTIONS_PATH -> json.encodeToString(
                        provider.connections(json.decodeFromString<WearConnectionsRequest>(body))
                    )
                    WEAR_SAVE_JOURNEY_PATH -> json.encodeToString(
                        provider.saveJourney(json.decodeFromString<WearSaveJourneyRequest>(body))
                    )
                    else -> error("Unknown path $path")
                }
            }.fold(
                onSuccess = { result.setResult(it.encodeToByteArray()) },
                onFailure = { result.setException(Exception(it)) },
            )
        }
        return result.task
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
