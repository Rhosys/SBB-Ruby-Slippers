package ch.rhosys.sbb.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Place
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun HomeScreen(
    onNavigateToSearch: (from: String, to: String) -> Unit,
    onNavigateToJourney: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Request location permission on first launch; refresh scorer if granted.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) viewModel.refresh()
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

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is HomeUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is HomeUiState.Hero -> HeroContent(
                state = s,
                onLockIn = { connection ->
                    viewModel.lockIn(connection, s.from, s.to)
                    onNavigateToJourney()
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Trip locked in",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            // undo: journey screen's back navigation clears the state
                        }
                    }
                },
                onTileClick = { place -> viewModel.routeFromCurrentLocationTo(place) },
                onDragRoute = { from, to -> onNavigateToSearch(from, to) },
                onRefresh = viewModel::refresh,
            )

            is HomeUiState.TileGrid -> TileGridContent(
                places = s.places,
                onTileClick = { place -> viewModel.routeFromCurrentLocationTo(place) },
                onDragRoute = { from, to -> onNavigateToSearch(from, to) },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun HeroContent(
    state: HomeUiState.Hero,
    onLockIn: (Connection) -> Unit,
    onTileClick: (Place) -> Unit,
    onDragRoute: (from: String, to: String) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "→ ${state.destination}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        items(state.connections) { connection ->
            SwipeToLockCard(
                connection = connection,
                isHero = connection == state.connections.firstOrNull(),
                onLockIn = { onLockIn(connection) },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Places",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            PlaceTileGrid(
                places = state.places,
                onTileClick = onTileClick,
                onDragRoute = onDragRoute,
            )
        }
    }
}

@Composable
private fun SwipeToLockCard(
    connection: Connection,
    isHero: Boolean,
    onLockIn: () -> Unit,
) {
    var offsetX by remember { mutableStateOf(0f) }
    val swipeThreshold = 120f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(offsetX) > swipeThreshold) onLockIn()
                        offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                ) { _, dragAmount -> offsetX += dragAmount }
            },
        colors = if (isHero) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(connection.departure.displayTime(), style = MaterialTheme.typography.titleLarge)
                Text(connection.arrival.displayTime(), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
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
                    text = "+${connection.departure.delayMinutes} min delay",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (isHero) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Swipe to lock in →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun TileGridContent(
    places: List<Place>,
    onTileClick: (Place) -> Unit,
    onDragRoute: (from: String, to: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Where to?", style = MaterialTheme.typography.headlineMedium)
        PlaceTileGrid(
            places = places,
            onTileClick = onTileClick,
            onDragRoute = onDragRoute,
        )
    }
}

// Tile grid with two gestures:
//   Tap  → onTileClick(place)  — routes from current GPS location to this place
//   Drag → draws a directed line between tiles; on release triggers onDragRoute(from, to)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceTileGrid(
    places: List<Place>,
    onTileClick: (Place) -> Unit,
    onDragRoute: (from: String, to: String) -> Unit,
) {
    val tileWindowBounds = remember(places) { mutableStateMapOf<Int, Rect>() }
    var boxWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragSourceIdx by remember { mutableStateOf(-1) }
    var dragTargetIdx by remember { mutableStateOf(-1) }
    var dragCurrentWindowPos by remember { mutableStateOf(Offset.Zero) }

    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
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
                            .firstOrNull { (_, rect) -> rect.contains(wp) }?.key ?: -1
                        dragTargetIdx = -1
                    },
                    onDragEnd = {
                        if (dragSourceIdx >= 0 && dragTargetIdx >= 0) {
                            onDragRoute(
                                places.getOrNull(dragSourceIdx)?.name ?: "",
                                places.getOrNull(dragTargetIdx)?.name ?: "",
                            )
                        }
                        dragSourceIdx = -1
                        dragTargetIdx = -1
                    },
                    onDragCancel = {
                        dragSourceIdx = -1
                        dragTargetIdx = -1
                    },
                ) { change, _ ->
                    dragCurrentWindowPos = change.position + boxWindowOrigin
                    dragTargetIdx = tileWindowBounds.entries
                        .firstOrNull { (k, rect) ->
                            k != dragSourceIdx && rect.contains(dragCurrentWindowPos)
                        }?.key ?: -1
                }
            },
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            places.forEachIndexed { idx, place ->
                PlaceTile(
                    label = place.name,
                    icon = if (place.isHome) Icons.Default.Place else Icons.Default.LocationOn,
                    onClick = { onTileClick(place) },
                    isSource = dragSourceIdx == idx,
                    isTarget = dragTargetIdx == idx,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        tileWindowBounds[idx] = coords.boundsInWindow()
                    },
                )
            }
        }

        // Drag-line overlay: drawn on a Canvas sitting on top of the FlowRow.
        if (dragSourceIdx >= 0) {
            val sourceBounds = tileWindowBounds[dragSourceIdx]
            if (sourceBounds != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val sourceCenter = Offset(
                        sourceBounds.center.x - boxWindowOrigin.x,
                        sourceBounds.center.y - boxWindowOrigin.y,
                    )
                    val snapTarget = if (dragTargetIdx >= 0) {
                        tileWindowBounds[dragTargetIdx]?.let { tb ->
                            Offset(tb.center.x - boxWindowOrigin.x, tb.center.y - boxWindowOrigin.y)
                        }
                    } else null
                    val tipPos = snapTarget ?: Offset(
                        dragCurrentWindowPos.x - boxWindowOrigin.x,
                        dragCurrentWindowPos.y - boxWindowOrigin.y,
                    )

                    // Dashed directed line
                    drawLine(
                        color = lineColor,
                        start = sourceCenter,
                        end = tipPos,
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 9f)),
                    )

                    // Origin dot
                    drawCircle(color = lineColor, radius = 6.dp.toPx(), center = sourceCenter)

                    // Target ring when hovering over a destination tile
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
