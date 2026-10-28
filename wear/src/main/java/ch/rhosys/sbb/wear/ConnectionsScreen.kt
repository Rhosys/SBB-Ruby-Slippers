package ch.rhosys.sbb.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onSave: (WearConnection) -> Unit,
    onRetry: () -> Unit,
    onSaved: () -> Unit,
) {
    LaunchedEffect(state.saved) {
        if (state.saved) {
            delay(1_200)
            onSaved()
        }
    }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        item {
            Text(
                text = state.placeName.ifEmpty { "Next connections" },
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
        when {
            state.saved -> item {
                Text(
                    text = "Journey saved",
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.isLoading -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
            else -> {
                items(state.connections, key = { it.key }) { connection ->
                    ConnectionCard(
                        connection = connection,
                        isSaving = state.savingKey == connection.key,
                        onSave = { onSave(connection) },
                    )
                }
                state.error?.let { error ->
                    item {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    CompactChip(
                        onClick = onRetry,
                        label = { Text("Refresh") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: WearConnection, isSaving: Boolean, onSave: () -> Unit) {
    // Only the "Save journey" chip acts — a stray tap on the card must not lock a journey in.
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildString {
                append(connection.departureTime)
                minutesUntil(connection.departureEpochSeconds)?.let { append(" · in $it min") }
            },
            fontWeight = FontWeight.Bold,
        )
        if (connection.delayMinutes > 0) {
            Text(
                text = "+${connection.delayMinutes} min",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.error,
            )
        }
        Text(
            text = listOfNotNull(
                connection.lines.firstOrNull(),
                connection.platform?.takeIf { it.isNotBlank() }?.let { "Pl. $it" },
                connection.boardingStop,
            ).joinToString(" · "),
            style = MaterialTheme.typography.caption2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Arr. ${connection.arrivalTime}" +
                if (connection.transfers > 0) " · ${connection.transfers} change${if (connection.transfers > 1) "s" else ""}" else "",
            style = MaterialTheme.typography.caption2,
        )
        Chip(
            onClick = onSave,
            enabled = !isSaving,
            label = { Text(if (isSaving) "Saving…" else "Save journey") },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private fun minutesUntil(epochSeconds: Long?): Long? {
    epochSeconds ?: return null
    val minutes = (epochSeconds - System.currentTimeMillis() / 1000) / 60
    return minutes.coerceAtLeast(0)
}
