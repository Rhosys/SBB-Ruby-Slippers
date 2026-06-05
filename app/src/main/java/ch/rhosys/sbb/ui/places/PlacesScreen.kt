package ch.rhosys.sbb.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Place

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlacesScreen(
    onNavigateBack: () -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My places") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Tap a tile to set it as your home place. Tap × to remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.places.forEach { place ->
                    PlaceEditTile(
                        place = place,
                        onSetHome = { viewModel.setHome(place.id) },
                        onDelete = { viewModel.deletePlace(place.id) },
                    )
                }

                AddTile(onClick = viewModel::openAddDialog)
            }
        }
    }

    if (state.showAddDialog) {
        AddPlaceDialog(
            query = state.addQuery,
            suggestions = state.addSuggestions,
            onQueryChange = viewModel::onQueryChanged,
            onSuggestionSelect = viewModel::selectSuggestion,
            onConfirm = viewModel::confirmAdd,
            onDismiss = viewModel::dismissAddDialog,
            canConfirm = state.addQuery.isNotBlank(),
        )
    }
}

@Composable
private fun PlaceEditTile(
    place: Place,
    onSetHome: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        FilledTonalButton(
            onClick = onSetHome,
            contentPadding = PaddingValues(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            colors = if (place.isHome) ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) else ButtonDefaults.filledTonalButtonColors(),
        ) {
            Icon(
                if (place.isHome) Icons.Default.Home else Icons.Default.LocationOn,
                contentDescription = if (place.isHome) "Home" else "Place",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(place.name, style = MaterialTheme.typography.labelLarge)
        }

        SmallFloatingActionButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(20.dp),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text("Add place", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AddPlaceDialog(
    query: String,
    suggestions: List<SuggestionItem>,
    onQueryChange: (String) -> Unit,
    onSuggestionSelect: (SuggestionItem) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    canConfirm: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add place") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Station or place name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (suggestions.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        suggestions.forEachIndexed { index, item ->
                            DropdownMenuItem(
                                text = { Text(item.name, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { onSuggestionSelect(item) },
                            )
                            if (index < suggestions.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
