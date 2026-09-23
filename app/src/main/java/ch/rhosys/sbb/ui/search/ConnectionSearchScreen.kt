package ch.rhosys.sbb.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_LABEL_FMT = DateTimeFormatter.ofPattern("EEE, d MMM")
private val TIME_LABEL_FMT = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSearchScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToFares: () -> Unit,
    viewModel: ConnectionSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

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
                Text("${state.searchDate.format(DATE_LABEL_FMT)}, ${state.searchTime.format(TIME_LABEL_FMT)}")
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

            else -> {
                val listState = rememberLazyListState()
                val shortestDuration = state.connections.mapNotNull { it.transitDuration }.minOrNull()
                val now = remember(state.connections) { Instant.now() }
                val rows = remember(state.connections, now) { buildRowsWithNowDivider(state.connections, now) }
                // Index of the "Now" row within the LazyColumn's own item indices — offset
                // by 1 for the leading "load earlier" sentinel — used to tell whether the
                // divider is currently on-screen or has scrolled past the visible range.
                val nowLazyIndex = remember(rows) { 1 + rows.indexOfFirst { it is ConnectionListRow.NowDivider } }
                val scope = rememberCoroutineScope()

                // Only a real finger-driven scroll should trigger a fetch — reaching an edge
                // because of layout settling, the "now" button's animateScrollToItem, or a
                // fresh result set landing at the top must NOT page in more results on their
                // own. We arm on DragInteraction.Start and disarm the instant we consume it.
                var userDragInitiatedScroll by remember { mutableStateOf(false) }
                LaunchedEffect(listState) {
                    listState.interactionSource.interactions.collect { interaction ->
                        if (interaction is DragInteraction.Start) userDragInitiatedScroll = true
                    }
                }

                LaunchedEffect(listState, state.connections) {
                    snapshotFlow { listState.layoutInfo }
                        .collect { layoutInfo ->
                            if (!userDragInitiatedScroll) return@collect
                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                            val totalItems = layoutInfo.totalItemsCount
                            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return@collect
                            // Index 0 is the "load earlier" sentinel, the last index is
                            // the "load later" sentinel — reaching either loads more.
                            if (firstVisible == 0) {
                                userDragInitiatedScroll = false
                                viewModel.loadEarlier()
                            }
                            if (lastVisible == totalItems - 1) {
                                userDragInitiatedScroll = false
                                viewModel.loadLater()
                            }
                        }
                }

                val nowPinnedEdge by remember(nowLazyIndex) {
                    derivedStateOf {
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val firstVisible = visible.firstOrNull()?.index
                        val lastVisible = visible.lastOrNull()?.index
                        when {
                            firstVisible == null || lastVisible == null -> null
                            nowLazyIndex < firstVisible -> Alignment.TopCenter
                            nowLazyIndex > lastVisible -> Alignment.BottomCenter
                            else -> null
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        item {
                            LoadMoreRow(isLoading = state.isLoadingEarlier, label = "Loading earlier connections…")
                        }
                        itemsIndexed(
                            items = rows,
                            key = { _, row -> row.key },
                        ) { _, row ->
                            when (row) {
                                is ConnectionListRow.NowDivider -> NowDividerRow()
                                is ConnectionListRow.ConnectionRow -> ConnectionCard(
                                    connection = row.connection,
                                    order = row.order,
                                    isHero = row.order == 1,
                                    isRecommended = shortestDuration != null && row.connection.transitDuration == shortestDuration,
                                    isActiveJourney = row.connection.stableKey == state.activeConnectionKey,
                                    walkingPaceKmh = state.walkingPaceKmh,
                                    runningPaceKmh = state.runningPaceKmh,
                                    now = now,
                                    onClick = {
                                        viewModel.openTripReview(row.connection)
                                        onNavigateToReview()
                                    },
                                    onFaresTap = onNavigateToFares,
                                )
                            }
                        }
                        item {
                            LoadMoreRow(isLoading = state.isLoadingLater, label = "Loading later connections…")
                        }
                    }

                    nowPinnedEdge?.let { edge ->
                        StickyNowMarker(
                            modifier = Modifier
                                .align(edge)
                                .fillMaxWidth(),
                            pointsUp = edge == Alignment.TopCenter,
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(nowLazyIndex)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDateTimePicker) {
        val zone = ZoneId.of("Europe/Zurich")
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.searchDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        val timePickerState = rememberTimePickerState(
            initialHour = state.searchTime.hour,
            initialMinute = state.searchTime.minute,
            is24Hour = true,
        )
        AppAlertDialog(
            onDismissRequest = { showDateTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onTimeSelected(LocalTime.of(timePickerState.hour, timePickerState.minute))
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.onDateSelected(date)
                    }
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

// One row per connection, plus a single "Now" divider row inserted at the boundary
// between past and future departures — connections are always shown sorted ascending.
private sealed class ConnectionListRow {
    abstract val key: String

    data class ConnectionRow(val connection: Connection, val order: Int) : ConnectionListRow() {
        override val key: String = connection.stableKey
    }

    object NowDivider : ConnectionListRow() {
        override val key: String = "now-divider"
    }
}

private fun buildRowsWithNowDivider(connections: List<Connection>, now: Instant): List<ConnectionListRow> {
    val rows = mutableListOf<ConnectionListRow>()
    var dividerInserted = false
    connections.forEachIndexed { index, connection ->
        val departure = connection.departure.effectiveTime ?: connection.departure.scheduledTime
        if (!dividerInserted && departure != null && !departure.isBefore(now)) {
            rows += ConnectionListRow.NowDivider
            dividerInserted = true
        }
        rows += ConnectionListRow.ConnectionRow(connection, index + 1)
    }
    // All connections are in the past (or times are unknown) — the divider still needs
    // to exist so the sticky marker has somewhere to point.
    if (!dividerInserted) rows += ConnectionListRow.NowDivider
    return rows
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

@Composable
private fun LoadMoreRow(isLoading: Boolean, label: String) {
    if (!isLoading) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

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
