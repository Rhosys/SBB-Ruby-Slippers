package ch.rhosys.sbb.ui.stationboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import java.time.OffsetDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationboardScreen(
    onNavigateToDetails: () -> Unit,
    viewModel: StationboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val isFavourite = state.station.trim() in state.favouriteStations

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.favouriteStations.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.favouriteStations.forEach { fav ->
                    SuggestionChip(
                        onClick = { viewModel.selectFavourite(fav) },
                        label = { Text(fav) },
                    )
                }
            }
        }

        Column {
            OutlinedTextField(
                value = state.station,
                onValueChange = viewModel::onStationChanged,
                label = { Text(stringResource(R.string.stationboard_station_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleFavourite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = if (isFavourite) "Remove favourite" else "Add favourite",
                            tint = if (isFavourite) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                },
            )
            if (state.stationSuggestions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    state.stationSuggestions.forEachIndexed { index, suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion, style = MaterialTheme.typography.bodyMedium) },
                            onClick = { viewModel.selectSuggestion(suggestion) },
                        )
                        if (index < state.stationSuggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

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
                    JourneyEntryCard(
                        entry = entry,
                        onClick = {
                            viewModel.selectEntry(entry)
                            onNavigateToDetails()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun JourneyEntryCard(entry: JourneyEntryDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
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
                if (entry.passList.isNotEmpty()) {
                    Text(
                        text = "${entry.passList.size} stops",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = entry.stop?.departure.toDisplayTime(),
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
                entry.stop?.platform?.let { platform ->
                    Text(
                        text = "Pl. $platform",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun String?.toDisplayTime(): String {
    if (this == null) return "—"
    return try {
        OffsetDateTime.parse(this).run { "%02d:%02d".format(hour, minute) }
    } catch (_: Exception) { this }
}
