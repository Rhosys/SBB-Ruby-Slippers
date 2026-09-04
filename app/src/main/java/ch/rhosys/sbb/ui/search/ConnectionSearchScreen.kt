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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.SwapVert
import ch.rhosys.sbb.R
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.TripHistoryItem
import ch.rhosys.sbb.ui.common.AppAlertDialog
import ch.rhosys.sbb.ui.common.StationAutocompleteField
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
                    onClearCurrentLocation = { viewModel.onFromChanged("") },
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
                    onClearCurrentLocation = { viewModel.onToChanged("") },
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

                LaunchedEffect(listState, state.connections) {
                    snapshotFlow { listState.layoutInfo }
                        .collect { layoutInfo ->
                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                            val totalItems = layoutInfo.totalItemsCount
                            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return@collect
                            // Index 0 is the "load earlier" sentinel, the last index is
                            // the "load later" sentinel — reaching either loads more.
                            if (firstVisible == 0) viewModel.loadEarlier()
                            if (lastVisible == totalItems - 1) viewModel.loadLater()
                        }
                }

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    item {
                        LoadMoreRow(isLoading = state.isLoadingEarlier, label = "Loading earlier connections…")
                    }
                    itemsIndexed(
                        items = state.connections,
                        key = { _, connection -> connectionKey(connection) },
                    ) { index, connection ->
                        ConnectionCard(
                            connection = connection,
                            isHero = index == 0,
                            isRecommended = shortestDuration != null && connection.transitDuration == shortestDuration,
                            onClick = {
                                viewModel.openTripReview(connection)
                                onNavigateToReview()
                            },
                            onFaresTap = onNavigateToFares,
                        )
                    }
                    item {
                        LoadMoreRow(isLoading = state.isLoadingLater, label = "Loading later connections…")
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

// Stable key so LazyColumn anchors scroll position to the connection itself
// (not its index) when earlier/later pages are prepended/appended.
private fun connectionKey(connection: Connection): String =
    "${connection.departure.scheduledTime}-${connection.arrival.scheduledTime}-${connection.lineNames.joinToString()}"

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

@Composable
private fun ConnectionCard(
    connection: Connection,
    isHero: Boolean,
    isRecommended: Boolean,
    onClick: () -> Unit,
    onFaresTap: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (isHero) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.cardColors(),
        border = if (isRecommended) BorderStroke(2.dp, RECOMMENDED_GREEN) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        "+${connection.departure.delayMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isRecommended) {
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
