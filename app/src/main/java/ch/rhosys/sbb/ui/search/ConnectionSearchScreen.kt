package ch.rhosys.sbb.ui.search

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.SwapVert
import ch.rhosys.sbb.R
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.TripHistoryItem
import ch.rhosys.sbb.ui.common.AppAlertDialog
import ch.rhosys.sbb.ui.common.RunningManBadge
import ch.rhosys.sbb.ui.common.StationAutocompleteField
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSearchScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToFares: () -> Unit,
    viewModel: ConnectionSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) { viewModel.applyPendingRequests() }

    var showDateTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StationAutocompleteField(
                    value = state.fromText,
                    onValueChange = viewModel::onFromChanged,
                    label = stringResource(R.string.search_from_hint),
                    suggestions = state.fromSuggestions,
                    onSuggestionSelected = viewModel::selectFromSuggestion,
                    onGpsClick = viewModel::fillFromWithNearestStop,
                    isLocating = state.isFromLocating,
                    isSearching = state.isFromSuggesting,
                    isCurrentLocation = state.fromIsCurrentLocation,
                    currentLocationStationName = state.fromBadgeStationName,
                    modifier = Modifier.fillMaxWidth(),
                )

                StationAutocompleteField(
                    value = state.toText,
                    onValueChange = viewModel::onToChanged,
                    label = stringResource(R.string.search_to_hint),
                    suggestions = state.toSuggestions,
                    onSuggestionSelected = viewModel::selectToSuggestion,
                    onGpsClick = viewModel::fillToWithNearestStop,
                    isLocating = state.isToLocating,
                    isSearching = state.isToSuggesting,
                    isCurrentLocation = state.toIsCurrentLocation,
                    currentLocationStationName = state.toBadgeStationName,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            IconButton(onClick = viewModel::swapFromTo) {
                Icon(
                    Icons.Default.SwapVert,
                    contentDescription = "Swap from and to",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !state.isArriveBy,
                onClick = { if (state.isArriveBy) viewModel.onToggleArriveBy() },
                label = { Text("Depart after") },
            )
            FilterChip(
                selected = state.isArriveBy,
                onClick = { if (!state.isArriveBy) viewModel.onToggleArriveBy() },
                label = { Text("Arrive by") },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::toggleRecentSearches) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "Recent searches",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = { showDateTimePicker = true }) {
                Text(state.timeMode.label())
            }
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

            else -> ConnectionList(
                state = state,
                onLoadEarlier = viewModel::loadEarlier,
                onLoadLater = viewModel::loadLater,
                onOpen = { connection ->
                    viewModel.openTripReview(connection)
                    onNavigateToReview()
                },
                onFaresTap = onNavigateToFares,
            )
        }
    }

    if (showDateTimePicker) {
        // Opens on the picked time, or the current time when the search is on "Now".
        val initial = remember {
            (state.timeMode as? SearchTimeMode.Fixed)?.dateTime ?: LocalDateTime.now(ZoneId.of("Europe/Zurich"))
        }
        // DatePicker works in UTC-midnight millis.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val timePickerState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = true,
        )
        AppAlertDialog(
            onDismissRequest = { showDateTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = datePickerState.selectedDateMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: initial.toLocalDate()
                    viewModel.onDateTimeSelected(
                        LocalDateTime.of(date, LocalTime.of(timePickerState.hour, timePickerState.minute)),
                    )
                    showDateTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDateTimePicker = false }) { Text("Cancel") }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TimePicker(state = timePickerState)
                    DatePicker(state = datePickerState)
                }
            },
        )
    }

    if (state.showRecentSearches) {
        AppAlertDialog(
            onDismissRequest = viewModel::dismissRecentSearches,
            confirmButton = {
                TextButton(onClick = viewModel::dismissRecentSearches) { Text("Close") }
            },
            title = { Text("Recent searches") },
            text = {
                if (state.recentSearches.isEmpty()) {
                    Text(
                        "No recent searches yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(state.recentSearches) { _, item ->
                            RecentSearchRow(item = item, onClick = { viewModel.selectRecentSearch(item) })
                        }
                    }
                }
            },
        )
    }
}

/**
 * The results, one row per connection, keyed by [Connection.stableKey] so connections
 * merged in above don't move what's on screen. Getting within [PREFETCH_ROWS] of either
 * end fetches more. The earlier/later status and the NOW line are drawn inside connection
 * rows rather than as rows of their own, so the row the list keeps its place by is always
 * a connection.
 */
@Composable
private fun ConnectionList(
    state: ConnectionSearchUiState,
    onLoadEarlier: () -> Unit,
    onLoadLater: () -> Unit,
    onOpen: (Connection) -> Unit,
    onFaresTap: () -> Unit,
) {
    val connections = state.connections
    val listState = rememberSaveable(state.searchId, saver = LazyListState.Saver) { LazyListState() }
    val now = remember(connections) { Instant.now() }
    val nowIndex = remember(connections, now) { nowIndex(connections, now) }
    val shortestDuration = remember(connections) { connections.mapNotNull { it.transitDuration }.minOrNull() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState, connections) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.let { it.firstOrNull()?.index to it.lastOrNull()?.index }
        }.collect { (firstVisible, lastVisible) ->
            if (firstVisible != null && firstVisible <= PREFETCH_ROWS) onLoadEarlier()
            if (lastVisible != null && lastVisible >= connections.lastIndex - PREFETCH_ROWS) onLoadLater()
        }
    }

    val nowPinnedEdge by remember(nowIndex) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val firstVisible = visible.firstOrNull()?.index
            val lastVisible = visible.lastOrNull()?.index
            when {
                firstVisible == null || lastVisible == null -> null
                nowIndex < firstVisible -> Alignment.TopCenter
                nowIndex > lastVisible -> Alignment.BottomCenter
                else -> null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            itemsIndexed(connections, key = { _, connection -> connection.stableKey }) { index, connection ->
                Column {
                    if (index == 0) {
                        EdgeRow(
                            label = when {
                                state.noMoreEarlier -> "No earlier connections"
                                state.isLoadingEarlier -> "Loading earlier connections…"
                                else -> "Earlier connections"
                            },
                            isLoading = state.isLoadingEarlier,
                        )
                    }
                    if (index == nowIndex) NowDividerRow()
                    ConnectionCard(
                        connection = connection,
                        order = index + 1,
                        isHero = index == 0,
                        isRecommended = shortestDuration != null && connection.transitDuration == shortestDuration,
                        isActiveJourney = connection.stableKey == state.activeConnectionKey,
                        walkingPaceKmh = state.walkingPaceKmh,
                        runningPaceKmh = state.runningPaceKmh,
                        now = now,
                        onClick = { onOpen(connection) },
                        onFaresTap = onFaresTap,
                    )
                    if (index == connections.lastIndex) {
                        if (nowIndex == connections.size) NowDividerRow()
                        EdgeRow(
                            label = when {
                                state.noMoreLater -> "No later connections"
                                state.isLoadingLater -> "Loading later connections…"
                                else -> "Later connections"
                            },
                            isLoading = state.isLoadingLater,
                        )
                    }
                }
            }
        }

        nowPinnedEdge?.let { edge ->
            StickyNowMarker(
                modifier = Modifier
                    .align(edge)
                    .fillMaxWidth(),
                pointsUp = edge == Alignment.TopCenter,
                onClick = {
                    scope.launch { listState.animateScrollToItem(nowIndex.coerceAtMost(connections.lastIndex)) }
                },
            )
        }
    }
}

@Composable
private fun NowDividerRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text(
            "NOW",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

// Shown pinned to the top or bottom edge of the list whenever the in-list "Now" divider
// has scrolled out of view, so it's always obvious which direction "now" is in — tapping
// it scrolls the divider back into view.
@Composable
private fun StickyNowMarker(
    modifier: Modifier = Modifier,
    pointsUp: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pointsUp) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                "NOW",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            if (!pointsUp) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun RecentSearchRow(item: TripHistoryItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${item.fromName} → ${item.toName}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

// Drawn above the first / below the last connection: whether there's more, or that it's
// being fetched.
@Composable
private fun EdgeRow(label: String, isLoading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// How close to either end of the list more connections start loading.
private const val PREFETCH_ROWS = 3

private val RECOMMENDED_GREEN = androidx.compose.ui.graphics.Color(0xFF2E7D32)
private val ACTIVE_JOURNEY_GREEN = androidx.compose.ui.graphics.Color(0xFF1B5E20)
private val ACTIVE_JOURNEY_GREEN_CONTAINER = androidx.compose.ui.graphics.Color(0xFFA5D6A7)

@Composable
private fun ConnectionCard(
    connection: Connection,
    order: Int,
    isHero: Boolean,
    isRecommended: Boolean,
    isActiveJourney: Boolean,
    walkingPaceKmh: Float,
    runningPaceKmh: Float,
    now: Instant,
    onClick: () -> Unit,
    onFaresTap: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = when {
            isActiveJourney -> CardDefaults.cardColors(
                containerColor = ACTIVE_JOURNEY_GREEN_CONTAINER,
            )
            isHero -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
            else -> CardDefaults.cardColors()
        },
        border = when {
            isActiveJourney -> BorderStroke(2.dp, ACTIVE_JOURNEY_GREEN)
            isRecommended -> BorderStroke(2.dp, RECOMMENDED_GREEN)
            else -> null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val runToFirstStop = connection.requiresRunningToFirstStop(now, walkingPaceKmh, runningPaceKmh)
                val longestRun = connection.longestRequiredRun(now, walkingPaceKmh, runningPaceKmh)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(connection.departure.displayTime(),
                            style = MaterialTheme.typography.titleMedium)
                        if (longestRun != null) {
                            Spacer(Modifier.width(4.dp))
                            RunningManBadge()
                        }
                    }
                    Text(connection.arrival.displayTime(),
                        style = MaterialTheme.typography.titleMedium)
                }
                // Times above are the trip itself (first boarding → last alighting); the
                // walk to/from it is shown separately underneath each end.
                val walkTo = connection.walkToFirstStop.toMinutes()
                val walkFrom = connection.walkFromLastStop.toMinutes()
                if (walkTo > 0 || walkFrom > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when {
                                walkTo <= 0 -> ""
                                runToFirstStop -> "Walk $walkTo min before · run now"
                                else -> "Walk $walkTo min before"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (runToFirstStop) FontWeight.Bold else FontWeight.Normal,
                            color = if (runToFirstStop) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (walkFrom > 0) {
                            Text(
                                "Walk $walkFrom min after",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                connection.transferInfos.forEach { transfer ->
                    val requiresRunning = transfer.requiresRunning(walkingPaceKmh, runningPaceKmh)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Change at ${transfer.stationName}: ${transfer.effectiveBufferMinutes} min",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (transfer.isAtRisk || requiresRunning) FontWeight.Bold else FontWeight.Normal,
                            color = if (transfer.isAtRisk) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (requiresRunning) {
                            Spacer(Modifier.width(4.dp))
                            RunningManBadge(iconSize = 14.dp)
                        }
                    }
                }
                if (longestRun != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Longest run: ${longestRun.runMinutes} min to ${longestRun.stationName}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        RunningManBadge(iconSize = 14.dp)
                    }
                }
                if (isActiveJourney) {
                    Text(
                        "Journey started · #$order",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ACTIVE_JOURNEY_GREEN,
                    )
                } else if (isRecommended) {
                    Text(
                        "Shortest connection",
                        style = MaterialTheme.typography.labelSmall,
                        color = RECOMMENDED_GREEN,
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
