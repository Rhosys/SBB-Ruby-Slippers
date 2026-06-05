package ch.rhosys.sbb.ui.places

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Place

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlacesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSearch: (from: String, to: String) -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOverTrash by remember { mutableStateOf(false) }
    val trashBoundsState = remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(state.pendingNavigateTo) {
        val nav = state.pendingNavigateTo ?: return@LaunchedEffect
        onNavigateToSearch(nav.from, nav.to)
        viewModel.onNavigationHandled()
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    "Tap a tile to find connections. Long-press and drag to the trash to remove.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.places.forEach { place ->
                        DraggablePlaceEditTile(
                            place = place,
                            isDragging = draggingId == place.id,
                            onTap = { viewModel.onTileTap(place) },
                            onDragStart = {
                                draggingId = place.id
                                dragOverTrash = false
                            },
                            onDragMove = { overTrash -> dragOverTrash = overTrash },
                            onDragEnd = { hitTrash ->
                                if (hitTrash) viewModel.deletePlace(place.id)
                                draggingId = null
                                dragOverTrash = false
                            },
                            trashBoundsProvider = { trashBoundsState.value },
                        )
                    }

                    AddTile(onClick = viewModel::openAddDialog)
                }
            }
        }

        // Trash drop zone — overlays the screen while any tile is being dragged
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        AnimatedVisibility(
            visible = draggingId != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusBarTop + 64.dp, start = 16.dp, end = 16.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { trashBoundsState.value = it.boundsInRoot() },
                shape = MaterialTheme.shapes.large,
                color = if (dragOverTrash) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Trash",
                        tint = if (dragOverTrash) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Drag here to remove",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dragOverTrash) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
private fun DraggablePlaceEditTile(
    place: Place,
    isDragging: Boolean,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (overTrash: Boolean) -> Unit,
    onDragEnd: (hitTrash: Boolean) -> Unit,
    trashBoundsProvider: () -> Rect?,
) {
    // Mutable fields captured by gesture lambdas — not state, so no recomposition on update.
    val dragCoords = remember {
        object {
            var rootOffset = Offset.Zero
            var startInTile = Offset.Zero
            var delta = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { dragCoords.rootOffset = it.positionInRoot() }
            .pointerInput(place.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { localPos ->
                        dragCoords.startInTile = localPos
                        dragCoords.delta = Offset.Zero
                        onDragStart()
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        dragCoords.delta += delta
                        val pointer = dragCoords.rootOffset + dragCoords.startInTile + dragCoords.delta
                        onDragMove(trashBoundsProvider()?.contains(pointer) == true)
                    },
                    onDragEnd = {
                        val pointer = dragCoords.rootOffset + dragCoords.startInTile + dragCoords.delta
                        onDragEnd(trashBoundsProvider()?.contains(pointer) == true)
                    },
                    onDragCancel = { onDragEnd(false) },
                )
            },
    ) {
        FilledTonalButton(
            onClick = onTap,
            contentPadding = PaddingValues(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            colors = if (isDragging) ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) else ButtonDefaults.filledTonalButtonColors(),
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = "Place",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(place.name, style = MaterialTheme.typography.labelLarge)
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
