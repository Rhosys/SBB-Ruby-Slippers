package ch.rhosys.sbb.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sqrt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.R
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.common.PLACE_GRID_COLUMNS
import ch.rhosys.sbb.ui.common.StationAutocompleteField

@Composable
fun HomeScreen(
    onNavigateToSearch: (from: String, to: String) -> Unit,
    onNavigateToJourneys: () -> Unit,
    onNavigateToHomeEdit: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) viewModel.onLocationPermissionGranted()
    }
    LaunchedEffect(Unit) {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter { p ->
            ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermissionLauncher.launch(missing.toTypedArray())
    }

    // Active journey top sheet takes precedence over scorer
    val hasOverlayContent = state.activeJourney != null || state.scorerResult != null
    val topSheetVisible = hasOverlayContent && !state.overlayHidden
    val peekHandleVisible = hasOverlayContent && state.overlayHidden

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Title header — full-width, primary theme color, with the edit pencil at the end.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                IconButton(onClick = onNavigateToHomeEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Manage places",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // Main content: tiles / empty state
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                // Place tiles or giant + button — fills available space
                Box(Modifier.weight(1f)) {
                    if (state.places.isEmpty() && !state.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            FloatingActionButton(
                                onClick = onNavigateToHomeEdit,
                                modifier = Modifier.size(120.dp),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add place",
                                    modifier = Modifier.size(56.dp),
                                )
                            }
                        }
                    } else {
                        PlaceTileGrid(
                            places = state.places,
                            onTileClick = { place ->
                                onNavigateToSearch(SearchEndpoint.CURRENT_LOCATION_LABEL, place.name)
                            },
                            onDragRoute = { from, to -> onNavigateToSearch(from, to) },
                            onCurrentLocationFromClick = viewModel::fillFromWithNearestStop,
                            onCurrentLocationToClick = viewModel::fillToWithNearestStop,
                        )
                    }
                }

                // Persistent bottom search form
                SearchForm(
                    fromText = state.fromText,
                    toText = state.toText,
                    quickSearchText = state.quickSearchText,
                    quickSearchSuggestions = state.quickSearchSuggestions,
                    isQuickSearchSuggesting = state.isQuickSearchSuggesting,
                    onEditFrom = { onNavigateToSearch(state.fromText, state.toText) },
                    onEditTo = { onNavigateToSearch(state.fromText, state.toText) },
                    onQuickSearchChanged = viewModel::onQuickSearchTextChanged,
                    onSelectQuickSearchSuggestion = viewModel::selectQuickSearchSuggestion,
                    onSearch = {
                        onNavigateToSearch(SearchEndpoint.CURRENT_LOCATION_LABEL, state.quickSearchText)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // Scrim behind the top sheet
        AnimatedVisibility(
            visible = topSheetVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0f)),
            )
        }
        if (topSheetVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable {
                        viewModel.hideOverlay()
                    },
            )
        }

        // Top sheet
        AnimatedVisibility(
            visible = topSheetVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            var dragOffsetY by remember { mutableStateOf(0f) }
            val swipeUpThreshold = -80f

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 560.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffsetY < swipeUpThreshold) {
                                    viewModel.hideOverlay()
                                }
                                dragOffsetY = 0f
                            },
                            onDragCancel = { dragOffsetY = 0f },
                        ) { _, dragAmount -> dragOffsetY += dragAmount }
                    },
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Drag handle
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 40.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                MaterialTheme.shapes.small,
                            ),
                    )
                    Spacer(Modifier.height(12.dp))

                    val activeJourney = state.activeJourney
                    val scorerResult = state.scorerResult

                    if (activeJourney != null) {
                        ActiveJourneySheetContent(
                            banner = activeJourney,
                            onTap = onNavigateToJourneys,
                        )
                    } else {
                        Text(
                            "No active journey",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (scorerResult != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        ScorerSheetContent(
                            result = scorerResult,
                            onCardTap = {
                                onNavigateToSearch(
                                    scorerResult.from.displayName(),
                                    scorerResult.to.displayName(),
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Swipe up to hide",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        // Peek handle: reappears when the overlay was hidden but there's still content to show.
        // Pulling down on it restores the full sheet.
        if (peekHandleVisible) {
            var pullDownOffsetY by remember { mutableStateOf(0f) }
            val pullDownThreshold = 40f

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (pullDownOffsetY > pullDownThreshold) {
                                    viewModel.showOverlay()
                                }
                                pullDownOffsetY = 0f
                            },
                            onDragCancel = { pullDownOffsetY = 0f },
                        ) { _, dragAmount -> pullDownOffsetY += dragAmount }
                    }
                    .clickable { viewModel.showOverlay() },
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                MaterialTheme.shapes.small,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveJourneySheetContent(
    banner: ActiveJourneyBanner,
    onTap: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Text(
            "Active journey",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    banner.connection.departure.stationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    banner.connection.departure.displayTime(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    banner.connection.arrival.stationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    banner.connection.arrival.displayTime(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap for details →",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScorerSheetContent(
    result: ScorerResult,
    onCardTap: (Connection) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "→ ${result.destination}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            items(result.connections.take(3)) { connection ->
                ConnectionSummaryCard(
                    connection = connection,
                    isHero = connection == result.connections.firstOrNull(),
                    onClick = { onCardTap(connection) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionSummaryCard(
    connection: Connection,
    isHero: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (isHero) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
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
                    "+${connection.departure.delayMinutes} min delay",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// "From" and "To" are read-only here — tapping their pencil hands off to the search
// screen, where the station is actually typed in. The one field left editable on
// HomeScreen is the quick search below, always "current location → this place".
@Composable
private fun SearchForm(
    fromText: String,
    toText: String,
    quickSearchText: String,
    quickSearchSuggestions: List<String>,
    isQuickSearchSuggesting: Boolean,
    onEditFrom: () -> Unit,
    onEditTo: () -> Unit,
    onQuickSearchChanged: (String) -> Unit,
    onSelectQuickSearchSuggestion: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EndpointRow(
                label = "From",
                value = fromText.ifBlank { "Current location" },
                onEdit = onEditFrom,
            )
            EndpointRow(
                label = "To",
                value = toText.ifBlank { "Choose a destination" },
                onEdit = onEditTo,
            )
            StationAutocompleteField(
                value = quickSearchText,
                onValueChange = onQuickSearchChanged,
                label = "Quick search — from here to…",
                suggestions = quickSearchSuggestions,
                onSuggestionSelected = onSelectQuickSearchSuggestion,
                isSearching = isQuickSearchSuggesting,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                enabled = quickSearchText.isNotBlank(),
            ) {
                Text("Search connections")
            }
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit $label",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// Sentinel indices for the two pinned "current location" tiles, sharing the same
// tap/drag machinery as the saved-place tiles below (tileWindowBounds is keyed by
// these same ints). NO_TILE marks "nothing under the pointer" — distinct from both,
// since -1/-2 are now real tiles.
private const val NO_TILE = Int.MIN_VALUE
private const val CURRENT_LOCATION_FROM = -1
private const val CURRENT_LOCATION_TO = -2

// Tile grid with two gestures:
//   Tap  → onTileClick(place) / onCurrentLocationFromClick() / onCurrentLocationToClick()
//   Drag → draws a directed line between tiles; on release triggers onDragRoute(from, to)
// Tiles are positioned by each place's saved grid rect (see ui/common/PlaceGrid.kt) —
// this screen only reads that layout; moving/resizing tiles happens on the edit screen.
@Composable
fun PlaceTileGrid(
    places: List<Place>,
    onTileClick: (Place) -> Unit,
    onDragRoute: (from: String, to: String) -> Unit,
    onCurrentLocationFromClick: () -> Unit,
    onCurrentLocationToClick: () -> Unit,
) {
    val tileWindowBounds = remember(places) { mutableStateMapOf<Int, Rect>() }
    var boxWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragSourceIdx by remember { mutableStateOf(NO_TILE) }
    var dragTargetIdx by remember { mutableStateOf(NO_TILE) }
    var dragCurrentWindowPos by remember { mutableStateOf(Offset.Zero) }

    fun labelFor(idx: Int): String = when (idx) {
        CURRENT_LOCATION_FROM, CURRENT_LOCATION_TO -> SearchEndpoint.CURRENT_LOCATION_LABEL
        else -> places.getOrNull(idx)?.name ?: ""
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.secondary

    val infiniteTransition = rememberInfiniteTransition(label = "arrow")
    val dashPhase by infiniteTransition.animateFloat(
        initialValue = 27f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dashPhase",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                boxWindowOrigin = Offset(b.left, b.top)
            }
            .pointerInput(places) {
                detectDragGestures(
                    onDragStart = { localOffset ->
                        val wp = localOffset + boxWindowOrigin
                        dragCurrentWindowPos = wp
                        dragSourceIdx = tileWindowBounds.entries
                            .firstOrNull { (_, rect) -> rect.contains(wp) }?.key ?: NO_TILE
                        dragTargetIdx = NO_TILE
                    },
                    onDragEnd = {
                        if (dragSourceIdx != NO_TILE && dragTargetIdx != NO_TILE) {
                            onDragRoute(labelFor(dragSourceIdx), labelFor(dragTargetIdx))
                        }
                        dragSourceIdx = NO_TILE
                        dragTargetIdx = NO_TILE
                    },
                    onDragCancel = {
                        dragSourceIdx = NO_TILE
                        dragTargetIdx = NO_TILE
                    },
                ) { change, _ ->
                    dragCurrentWindowPos = change.position + boxWindowOrigin
                    dragTargetIdx = tileWindowBounds.entries
                        .firstOrNull { (k, rect) ->
                            k != dragSourceIdx && rect.contains(dragCurrentWindowPos)
                        }?.key ?: NO_TILE
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 16.dp),
            ) {
                val cellSizeDp = maxWidth / PLACE_GRID_COLUMNS
                places.forEachIndexed { idx, place ->
                    Box(
                        modifier = Modifier
                            .offset(x = cellSizeDp * place.gridX, y = cellSizeDp * place.gridY)
                            .size(width = cellSizeDp * place.gridWidth, height = cellSizeDp * place.gridHeight)
                            .padding(4.dp),
                    ) {
                        PlaceTile(
                            label = place.displayName,
                            icon = Icons.Default.LocationOn,
                            onClick = { onTileClick(place) },
                            isSource = dragSourceIdx == idx,
                            isTarget = dragTargetIdx == idx,
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { coords ->
                                    tileWindowBounds[idx] = coords.boundsInWindow()
                                },
                        )
                    }
                }
            }

            // Pinned "current location" tiles, right above the search form below —
            // tap fills that field instantly (already tracked continuously), drag
            // routes just like any saved-place tile.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaceTile(
                    label = "From here",
                    icon = Icons.Default.GpsFixed,
                    onClick = onCurrentLocationFromClick,
                    isSource = dragSourceIdx == CURRENT_LOCATION_FROM,
                    isTarget = dragTargetIdx == CURRENT_LOCATION_FROM,
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            tileWindowBounds[CURRENT_LOCATION_FROM] = coords.boundsInWindow()
                        },
                )
                PlaceTile(
                    label = "To here",
                    icon = Icons.Default.GpsFixed,
                    onClick = onCurrentLocationToClick,
                    isSource = dragSourceIdx == CURRENT_LOCATION_TO,
                    isTarget = dragTargetIdx == CURRENT_LOCATION_TO,
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            tileWindowBounds[CURRENT_LOCATION_TO] = coords.boundsInWindow()
                        },
                )
            }
        }

        if (dragSourceIdx != NO_TILE) {
            val sourceBounds = tileWindowBounds[dragSourceIdx]
            if (sourceBounds != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val sourceCenter = Offset(
                        sourceBounds.center.x - boxWindowOrigin.x,
                        sourceBounds.center.y - boxWindowOrigin.y,
                    )
                    val snapTarget = if (dragTargetIdx != NO_TILE) {
                        tileWindowBounds[dragTargetIdx]?.let { tb ->
                            Offset(tb.center.x - boxWindowOrigin.x, tb.center.y - boxWindowOrigin.y)
                        }
                    } else null
                    val tipPos = snapTarget ?: Offset(
                        dragCurrentWindowPos.x - boxWindowOrigin.x,
                        dragCurrentWindowPos.y - boxWindowOrigin.y,
                    )

                    // Animated dashed shaft flowing source → target
                    drawLine(
                        color = lineColor,
                        start = sourceCenter,
                        end = tipPos,
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 9f), phase = dashPhase),
                    )
                    // Source dot
                    drawCircle(color = lineColor, radius = 6.dp.toPx(), center = sourceCenter)

                    // Arrowhead at tip
                    val dx = tipPos.x - sourceCenter.x
                    val dy = tipPos.y - sourceCenter.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > 0f) {
                        val ndx = dx / len
                        val ndy = dy / len
                        val arrowLen = 20f
                        val arrowWing = 10f
                        val base = androidx.compose.ui.geometry.Offset(
                            tipPos.x - ndx * arrowLen,
                            tipPos.y - ndy * arrowLen,
                        )
                        val w1 = androidx.compose.ui.geometry.Offset(
                            base.x - ndy * arrowWing,
                            base.y + ndx * arrowWing,
                        )
                        val w2 = androidx.compose.ui.geometry.Offset(
                            base.x + ndy * arrowWing,
                            base.y - ndx * arrowWing,
                        )
                        drawPath(
                            path = Path().apply {
                                moveTo(tipPos.x, tipPos.y)
                                lineTo(w1.x, w1.y)
                                lineTo(w2.x, w2.y)
                                close()
                            },
                            color = if (snapTarget != null) targetColor else lineColor,
                        )
                    }

                    if (snapTarget != null) {
                        drawCircle(
                            color = targetColor,
                            radius = 8.dp.toPx(),
                            center = snapTarget,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isSource: Boolean = false,
    isTarget: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        colors = when {
            isSource -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            isTarget -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )
            else -> ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
