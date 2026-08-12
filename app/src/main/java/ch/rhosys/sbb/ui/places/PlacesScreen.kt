package ch.rhosys.sbb.ui.places

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.ui.common.StationAutocompleteField
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var draggingId by remember { mutableStateOf<Long?>(null) }
    var reorderTargetId by remember { mutableStateOf<Long?>(null) }
    var dragOverTrash by remember { mutableStateOf(false) }
    val trashBoundsState = remember { mutableStateOf<Rect?>(null) }
    val tileWindowBounds = remember { mutableStateMapOf<Long, Rect>() }
    var boxWindowOrigin by remember { mutableStateOf(Offset.Zero) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onPhotoSelected(uri.toString())
        }
    }

    val editPhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onEditPhotoSelected(uri.toString())
        }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInWindow()
                        boxWindowOrigin = Offset(b.left, b.top)
                    }
                    .pointerInput(state.places) {
                        detectDragGestures(
                            onDragStart = { localOffset ->
                                val wp = localOffset + boxWindowOrigin
                                draggingId = tileWindowBounds.entries
                                    .firstOrNull { (_, rect) -> rect.contains(wp) }?.key
                                reorderTargetId = null
                                dragOverTrash = false
                            },
                            onDragEnd = {
                                val from = draggingId
                                if (from != null) {
                                    if (dragOverTrash) {
                                        viewModel.deletePlace(from)
                                    } else {
                                        val to = reorderTargetId
                                        if (to != null) viewModel.reorderTiles(from, to)
                                    }
                                }
                                draggingId = null
                                reorderTargetId = null
                                dragOverTrash = false
                            },
                            onDragCancel = {
                                draggingId = null
                                reorderTargetId = null
                                dragOverTrash = false
                            },
                        ) { change, _ ->
                            val wp = change.position + boxWindowOrigin
                            val trashRect = trashBoundsState.value
                            dragOverTrash = trashRect != null && trashRect.contains(wp)
                            reorderTargetId = if (!dragOverTrash) {
                                tileWindowBounds.entries
                                    .firstOrNull { (id, rect) -> id != draggingId && rect.contains(wp) }?.key
                            } else null
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Drag a tile to reorder or drop on trash to remove.",
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
                                isDragging = draggingId == place.id,
                                isReorderTarget = reorderTargetId == place.id,
                                onTap = { viewModel.onTileTap(place) },
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    tileWindowBounds[place.id] = coords.boundsInWindow()
                                },
                            )
                        }

                        AddTile(onClick = viewModel::openAddDialog)
                    }
                }
            }
        }

        // Trash drop zone — appears while any tile is being dragged
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
private fun PlaceEditTile(
    place: Place,
    isDragging: Boolean,
    isReorderTarget: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onTap,
        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        modifier = modifier,
        colors = when {
            isDragging -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            isReorderTarget -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            else -> ButtonDefaults.filledTonalButtonColors()
        },
    ) {
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
        Spacer(Modifier.size(6.dp))
        Text(place.displayName, style = MaterialTheme.typography.labelLarge)
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
    AlertDialog(
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
    AlertDialog(
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
