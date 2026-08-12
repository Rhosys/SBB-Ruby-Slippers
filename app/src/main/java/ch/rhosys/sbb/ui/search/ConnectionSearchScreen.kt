package ch.rhosys.sbb.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MonetizationOn
import ch.rhosys.sbb.R
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.ui.common.StationAutocompleteField

@Composable
fun ConnectionSearchScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToFares: () -> Unit,
    viewModel: ConnectionSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StationAutocompleteField(
            value = state.fromText,
            onValueChange = viewModel::onFromChanged,
            label = stringResource(R.string.search_from_hint),
            suggestions = state.fromSuggestions,
            onSuggestionSelected = viewModel::selectFromSuggestion,
            onGpsClick = viewModel::fillFromWithNearestStop,
            isLocating = state.isLocatingFrom,
            modifier = Modifier.fillMaxWidth(),
        )

        StationAutocompleteField(
            value = state.toText,
            onValueChange = viewModel::onToChanged,
            label = stringResource(R.string.search_to_hint),
            suggestions = state.toSuggestions,
            onSuggestionSelected = viewModel::selectToSuggestion,
            onGpsClick = viewModel::fillToWithNearestStop,
            isLocating = state.isLocatingTo,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = viewModel::search,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.search_action))
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

            state.connections.isEmpty() -> Text(
                text = stringResource(R.string.search_empty),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.connections) { connection ->
                    ConnectionCard(
                        connection = connection,
                        isHero = connection == state.connections.firstOrNull(),
                        onClick = {
                            viewModel.openTripReview(connection)
                            onNavigateToReview()
                        },
                        onFaresTap = onNavigateToFares,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: Connection,
    isHero: Boolean,
    onClick: () -> Unit,
    onFaresTap: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (isHero) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(connection.departure.displayTime(),
                        style = MaterialTheme.typography.titleMedium)
                    Text(connection.arrival.displayTime(),
                        style = MaterialTheme.typography.titleMedium)
                }
                val lines = connection.lineNames.joinToString(" → ")
                val transfers = connection.transfers
                Text(
                    text = buildString {
                        if (lines.isNotBlank()) append(lines)
                        if (transfers > 0) append(" · $transfers transfer${if (transfers > 1) "s" else ""}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (connection.departure.isDelayed) {
                    Text(
                        "+${connection.departure.delayMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isHero) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to review →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                IconButton(onClick = onFaresTap) {
                    Icon(
                        Icons.Default.MonetizationOn,
                        contentDescription = "See fares",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "CHF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
