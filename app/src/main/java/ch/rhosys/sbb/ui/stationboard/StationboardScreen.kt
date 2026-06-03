package ch.rhosys.sbb.ui.stationboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.R
import ch.rhosys.sbb.data.remote.dto.JourneyEntryDto

@Composable
fun StationboardScreen(viewModel: StationboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.station,
            onValueChange = viewModel::onStationChanged,
            label = { Text(stringResource(R.string.stationboard_station_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = viewModel::load,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.stationboard_action))
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Text(
                text = state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            state.entries.isEmpty() -> Text(
                text = stringResource(R.string.stationboard_empty),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.entries) { entry ->
                    JourneyEntryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun JourneyEntryCard(entry: JourneyEntryDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "${entry.category ?: ""} ${entry.number ?: ""}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "→ ${entry.to ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = entry.stop?.departure ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                val delay = entry.stop?.delay
                if (delay != null && delay > 0) {
                    Text(
                        text = "+$delay min",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
