package ch.rhosys.sbb.ui.places

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.ui.common.AppAlertDialog
import ch.rhosys.sbb.ui.common.PLACE_GRID_COLUMNS
import ch.rhosys.sbb.ui.common.rectOverlapsAnyPlace
import ch.rhosys.sbb.ui.common.StationAutocompleteField
import coil.compose.AsyncImage
import kotlin.math.roundToInt

private enum class DragMode { NONE, MOVE, RESIZE }

private data class GridRect(val x: Int, val y: Int, val width: Int, val height: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current

    // Drag state for the grid: which place is being dragged, in which mode, and the
    // candidate rect (grid units) it would land on if released right now.
    var dragPlaceId by remember { mutableStateOf<Long?>(null) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var dragStartRect by remember { mutableStateOf(GridRect(0, 0, 0, 0)) }
    var dragAccumPx by remember { mutableStateOf(Offset.Zero) }
    var candidateRect by remember { mutableStateOf<GridRect?>(null) }
    var candidateValid by remember { mutableStateOf(true) }
    var dragOverTrash by remember { mutableStateOf(false) }
    val trashBoundsState = remember { mutableStateOf<Rect?>(null) }
    val tileWindowBounds = remember { mutableStateMapOf<Long, Rect>() }
    val handleWindowBounds = remember { mutableStateMapOf<Long, Rect>() }
    var boxWindowOrigin by remember { mutableStateOf(Offset.Zero) }

    // The photo picker's own grant is enough to read the image once here — the
    // ViewModel immediately copies the bytes into app storage (PlacePhotoStore) so
    // there's no need to retain a persistable permission on the picker's Uri.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onPhotoSelected(uri) }

    val editPhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onEditPhotoSelected(uri) }

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
                    "Press and hold a tile's center to move it, its corner to resize it, or onto trash to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInWindow()
                            boxWindowOrigin = Offset(b.left, b.top)
                        },
                ) {
                    val cellSizeDp = maxWidth / PLACE_GRID_COLUMNS
                    val cellSizePx = with(density) { cellSizeDp.toPx() }
                    val maxRows = (maxHeight / cellSizeDp).toInt().coerceAtLeast(1)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.places, cellSizePx, maxRows) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { localOffset ->
                                        val wp = localOffset + boxWindowOrigin
                                        val handleHit = handleWindowBounds.entries
                                            .firstOrNull { (_, r) -> r.contains(wp) }?.key
                                        val tileHit = tileWindowBounds.entries
                                            .firstOrNull { (_, r) -> r.contains(wp) }?.key
                                        val id = handleHit ?: tileHit
                                        dragMode = when {
                                            id == null -> DragMode.NONE
                                            handleHit != null -> DragMode.RESIZE
                                            else -> DragMode.MOVE
                                        }
                                        dragPlaceId = id
                                        val place = state.places.firstOrNull { it.id == id }
                                        dragStartRect = place?.let {
                                            GridRect(it.gridX, it.gridY, it.gridWidth, it.gridHeight)
                                        } ?: GridRect(0, 0, 0, 0)
                                        dragAccumPx = Offset.Zero
                                        candidateRect = if (id != null) dragStartRect else null
                                        candidateValid = true
                                        dragOverTrash = false
                                    },
                                    onDragEnd = {
                                        val id = dragPlaceId
                                        val rect = candidateRect
                                        if (id != null && dragMode == DragMode.MOVE && dragOverTrash) {
                                            viewModel.deletePlace(id)
                                        } else if (id != null && rect != null && candidateValid) {
                                            viewModel.updateTileRect(id, rect.x, rect.y, rect.width, rect.height)
                                        }
                                        dragPlaceId = null
                                        dragMode = DragMode.NONE
                                        candidateRect = null
                                        dragOverTrash = false
                                    },
                                    onDragCancel = {
                                        dragPlaceId = null
                                        dragMode = DragMode.NONE
                                        candidateRect = null
                                        dragOverTrash = false
                                    },
                                ) { change, dragAmount ->
                                    val id = dragPlaceId
                                    if (id == null || dragMode == DragMode.NONE) return@detectDragGesturesAfterLongPress
                                    dragAccumPx += dragAmount
                                    val dGridX = (dragAccumPx.x / cellSizePx).roundToInt()
                                    val dGridY = (dragAccumPx.y / cellSizePx).roundToInt()

                                    val wp = change.position + boxWindowOrigin
                                    val trashRect = trashBoundsState.value
                                    dragOverTrash = dragMode == DragMode.MOVE &&
                                        trashRect != null && trashRect.contains(wp)

                                    val newRect = when (dragMode) {
                                        DragMode.MOVE -> GridRect(
                                            x = (dragStartRect.x + dGridX)
                                                .coerceIn(0, PLACE_GRID_COLUMNS - dragStartRect.width),
                                            y = (dragStartRect.y + dGridY)
                                                .coerceIn(0, maxRows - dragStartRect.height),
                                            width = dragStartRect.width,
                                            height = dragStartRect.height,
                                        )
                                        DragMode.RESIZE -> GridRect(
                                            x = dragStartRect.x,
                                            y = dragStartRect.y,
                                            width = (dragStartRect.width + dGridX)
                                                .coerceIn(1, PLACE_GRID_COLUMNS - dragStartRect.x),
                                            height = (dragStartRect.height + dGridY)
                                                .coerceIn(1, maxRows - dragStartRect.y),
                                        )
                                        DragMode.NONE -> dragStartRect
                                    }
                                    candidateRect = newRect
                                    candidateValid = !dragOverTrash && !rectOverlapsAnyPlace(
                                        newRect.x, newRect.y, newRect.width, newRect.height,
                                        state.places, excludingId = id,
                                    )
                                }
                            },
                    ) {
                        state.places.forEach { place ->
                            val isDragging = dragPlaceId == place.id
                            val rect = if (isDragging && candidateRect != null) {
                                candidateRect!!
                            } else {
                                GridRect(place.gridX, place.gridY, place.gridWidth, place.gridHeight)
                            }
                            PlaceGridTile(
                                place = place,
                                rect = rect,
                                cellSizeDp = cellSizeDp,
                                isDragging = isDragging,
                                isInvalid = isDragging && !candidateValid,
                                onTap = { viewModel.onTileTap(place) },
                                onTileBoundsChanged = { tileWindowBounds[place.id] = it },
                                onHandleBoundsChanged = { handleWindowBounds[place.id] = it },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = viewModel::openAddDialog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add place", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(Modifier.height(8.dp))
            }
        }

        // Trash drop zone — appears while any tile is being dragged (moved, not resized)
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        AnimatedVisibility(
            visible = dragPlaceId != null && dragMode == DragMode.MOVE,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusBarTop + 64.dp, start = 16.dp, end = 16.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { trashBoundsState.value = it.boundsInWindow() },
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
            label = state.addLabel,
            photoUri = state.addPhotoUri,
            suggestions = state.addSuggestions,
            onQueryChange = viewModel::onQueryChanged,
            onLabelChange = viewModel::onLabelChanged,
            onSuggestionSelect = viewModel::selectSuggestion,
            onPickPhoto = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRemovePhoto = { viewModel.onPhotoSelected(null) },
            onConfirm = viewModel::confirmAdd,
            onDismiss = viewModel::dismissAddDialog,
            canConfirm = state.addQuery.isNotBlank(),
        )
    }

    val editingPlace = state.editingPlace
    if (editingPlace != null) {
        EditPlaceDialog(
            placeName = editingPlace.name,
            label = state.editLabel,
            photoUri = state.editPhotoUri,
            onLabelChange = viewModel::onEditLabelChanged,
            onPickPhoto = {
                editPhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRemovePhoto = { viewModel.onEditPhotoSelected(null) },
            onConfirm = viewModel::confirmEdit,
            onDismiss = viewModel::dismissEditDialog,
        )
    }
}

@Composable
private fun PlaceGridTile(
    place: Place,
    rect: GridRect,
    cellSizeDp: Dp,
    isDragging: Boolean,
    isInvalid: Boolean,
    onTap: () -> Unit,
    onTileBoundsChanged: (Rect) -> Unit,
    onHandleBoundsChanged: (Rect) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(x = cellSizeDp * rect.x, y = cellSizeDp * rect.y)
            .size(width = cellSizeDp * rect.width, height = cellSizeDp * rect.height)
            .onGloballyPositioned { onTileBoundsChanged(it.boundsInWindow()) }
            .padding(4.dp),
    ) {
        FilledTonalButton(
            onClick = onTap,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            border = if (isInvalid) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null,
            colors = when {
                isInvalid -> ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                isDragging -> ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                else -> ButtonDefaults.filledTonalButtonColors()
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (place.photoUri != null) {
                    AsyncImage(
                        model = place.photoUri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    place.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Resize handle, bottom-end corner — dragging it changes gridWidth/gridHeight
        // instead of moving the tile (see the top-level drag detector's hit-testing).
        // The hit target (44dp) is deliberately much larger than the 24dp visible circle —
        // the corner is fiddly to grab precisely, so the extra invisible margin around the
        // icon still counts as a hit.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(44.dp)
                .onGloballyPositioned { onHandleBoundsChanged(it.boundsInWindow()) },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    Icons.Default.SouthEast,
                    contentDescription = "Resize ${place.displayName}",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun AddPlaceDialog(
    query: String,
    label: String,
    photoUri: String?,
    suggestions: List<SuggestionItem>,
    onQueryChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onSuggestionSelect: (SuggestionItem) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    canConfirm: Boolean,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add place") },
        text = {
            Column {
                StationAutocompleteField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = "Station or place name",
                    suggestions = suggestions.map { it.name },
                    onSuggestionSelected = { name ->
                        suggestions.firstOrNull { it.name == name }?.let(onSuggestionSelect)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = onLabelChange,
                    label = { Text("Label (optional)") },
                    placeholder = { Text(query.ifBlank { "Custom name" }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                if (photoUri != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = photoUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                        TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                    }
                } else {
                    TextButton(onClick = onPickPhoto) { Text("Add photo") }
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

@Composable
private fun EditPlaceDialog(
    placeName: String,
    label: String,
    photoUri: String?,
    onLabelChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit place") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = onLabelChange,
                    label = { Text("Label (optional)") },
                    placeholder = { Text(placeName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                if (photoUri != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = photoUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                        TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                    }
                } else {
                    TextButton(onClick = onPickPhoto) { Text("Add photo") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
