package ch.rhosys.sbb.ui.home

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.SearchEndpoint
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
                            // undo handled by Journey screen's back navigation
                        }
                    }
                },
                onTileClick = { place ->
                    onNavigateToSearch("", place.name)
                },
                onFromTileClick = { onNavigateToSearch("", "") },
                onToTileClick = { onNavigateToSearch("", "") },
                onRefresh = viewModel::refresh,
            )

            is HomeUiState.TileGrid -> TileGridContent(
                places = s.places,
                onTileClick = { place -> onNavigateToSearch("", place.name) },
                onFromTileClick = { onNavigateToSearch("", "") },
                onToTileClick = { onNavigateToSearch("", "") },
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
    onFromTileClick: () -> Unit,
    onToTileClick: () -> Unit,
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
            Text("Places", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            PlaceTileGrid(
                places = state.places,
                onTileClick = onTileClick,
                onFromTileClick = onFromTileClick,
                onToTileClick = onToTileClick,
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
    onFromTileClick: () -> Unit,
    onToTileClick: () -> Unit,
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
            onFromTileClick = onFromTileClick,
            onToTileClick = onToTileClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceTileGrid(
    places: List<Place>,
    onTileClick: (Place) -> Unit,
    onFromTileClick: () -> Unit,
    onToTileClick: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Saved place tiles
        places.forEach { place ->
            PlaceTile(
                label = place.name,
                icon = if (place.isHome) Icons.Default.Place else Icons.Default.LocationOn,
                onClick = { onTileClick(place) },
            )
        }

        // Special control tiles
        PlaceTile(
            label = "From",
            icon = Icons.Default.ArrowForward,
            onClick = onFromTileClick,
            isSpecial = true,
            connectorSuffix = " ●—",
        )
        PlaceTile(
            label = "To",
            icon = Icons.Default.ArrowForward,
            onClick = onToTileClick,
            isSpecial = true,
            connectorSuffix = " —→",
        )
    }
}

@Composable
private fun PlaceTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isSpecial: Boolean = false,
    connectorSuffix: String = "",
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(
            text = "$label$connectorSuffix",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
