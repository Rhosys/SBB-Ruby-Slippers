package ch.rhosys.sbb.ui.search

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
import ch.rhosys.sbb.data.remote.dto.ConnectionDto

@Composable
fun ConnectionSearchScreen(viewModel: ConnectionSearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.from,
            onValueChange = viewModel::onFromChanged,
            label = { Text(stringResource(R.string.search_from_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = state.to,
            onValueChange = viewModel::onToChanged,
            label = { Text(stringResource(R.string.search_to_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
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
                    ConnectionCard(connection)
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = connection.from?.departure ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = connection.to?.arrival ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            val duration = connection.duration ?: ""
            val transfers = connection.transfers ?: 0
            val products = connection.products.joinToString(", ")
            Text(
                text = buildString {
                    if (duration.isNotBlank()) append(duration)
                    if (transfers > 0) append(" · $transfers transfer${if (transfers > 1) "s" else ""}")
                    if (products.isNotBlank()) append(" · $products")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
