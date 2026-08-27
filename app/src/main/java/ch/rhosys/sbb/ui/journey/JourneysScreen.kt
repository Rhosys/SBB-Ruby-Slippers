package ch.rhosys.sbb.ui.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.TripHistoryItem
import ch.rhosys.sbb.ui.common.AppAlertDialog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JourneysScreen(
    viewModel: JourneysViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (state.selectedTab) {
                JourneysTab.ACTIVE -> ActiveTab(state, viewModel)
                JourneysTab.PAST -> PastTab(state.lockedInHistory)
                JourneysTab.PLANNED -> PlannedTab(state)
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            JourneysTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index, JourneysTab.entries.size),
                    label = { Text(tab.label) },
                )
            }
        }
    }

    state.switchPrompt?.let { prompt ->
        AppAlertDialog(
            onDismissRequest = viewModel::dismissSwitch,
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Better option found") },
            text = {
                Text(
                    "${prompt.reason} ${prompt.betterConnection.lineNames.firstOrNull() ?: "Next departure"} " +
                            "arrives ${prompt.minutesSaved} min earlier."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSwitch) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSwitch) { Text("Dismiss") }
            },
        )
    }
}

@Composable
private fun ActiveTab(state: JourneysUiState, viewModel: JourneysViewModel) {
    val connection = state.activeConnection
    var showCancelDialog by remember { mutableStateOf(false) }

    if (state.isRestoring) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (connection == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No active journey", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lock in a connection from the search screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (state.rtAlerts.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                "Service alert",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        state.rtAlerts.forEach { alert ->
                            if (alert.headerText.isNotBlank()) {
                                Text(
                                    alert.headerText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        items(connection.legs) { leg -> LegRow(leg) }

        item {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Arrive", style = MaterialTheme.typography.labelMedium)
                    Text(connection.arrival.stationName,
                        style = MaterialTheme.typography.titleMedium)
                    Text(connection.arrival.displayTime(),
                        style = MaterialTheme.typography.headlineSmall)
                    if (connection.arrival.isDelayed) {
                        Text(
                            "+${connection.arrival.delayMinutes} min",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showCancelDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Cancel journey")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCancelDialog) {
        AppAlertDialog(
            onDismissRequest = { showCancelDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Cancel this journey?") },
            text = { Text("You'll stop tracking this trip. It'll still show up in Past with a cancelled badge.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancelActiveJourney()
                }) { Text("Cancel journey") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Keep journey") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PastTab(history: List<TripHistoryItem>) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No past trips yet", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val now = remember { Instant.now().epochSecond }
    val grouped = remember(history) { groupByDate(history) }
    val firstFutureIndex = remember(grouped) { findFirstFutureGroupIndex(grouped, now) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showFab by remember { derivedStateOf { firstFutureIndex >= 0 } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            grouped.forEach { (label, items) ->
                stickyHeader {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(items, key = { it.id }) { item ->
                    TripHistoryCard(item)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        if (showFab && firstFutureIndex >= 0) {
            FloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(firstFutureIndex) } },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("Future", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Icon(Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to future",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

@Composable
private fun TripHistoryCard(item: TripHistoryItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${item.fromName} → ${item.toName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (item.wasCancelled) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "Cancelled",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (item.departureEpoch != null) {
                    Text(
                        formatEpoch(item.departureEpoch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlannedTab(state: JourneysUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.savedRoutes.isEmpty() && state.recurringRoutes.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 64.dp),
                    contentAlignment = Alignment.Center) {
                    Text("No planned routes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.savedRoutes.isNotEmpty()) {
            item {
                Text("Saved routes", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(state.savedRoutes, key = { "saved-${it.id}" }) { route ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(route.label ?: route.destinationName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            if (route.isCalendarLinked) {
                                Text("Calendar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("→ ${route.destinationName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (state.recurringRoutes.isNotEmpty()) {
            item {
                Text("Recurring routes", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(state.recurringRoutes, key = { "recur-${it.id}" }) { route ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(route.label ?: route.destinationName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Text(
                            "→ ${route.destinationName} · " +
                                    "%02d:%02d".format(route.departureHour, route.departureMinute),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

// --- helpers ---

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

private fun formatEpoch(epochSecond: Long): String {
    val zdt = Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault())
    return "${TIME_FORMATTER.format(zdt)} · ${DATE_FORMATTER.format(zdt)}"
}

private fun groupByDate(history: List<TripHistoryItem>): List<Pair<String, List<TripHistoryItem>>> {
    val today = LocalDate.now(ZoneId.systemDefault())
    val tomorrow = today.plusDays(1)

    return history
        .groupBy { item ->
            if (item.departureEpoch != null) {
                Instant.ofEpochSecond(item.departureEpoch).atZone(ZoneId.systemDefault()).toLocalDate()
            } else {
                Instant.ofEpochMilli(item.searchedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            }
        }
        .entries
        .sortedByDescending { it.key }
        .map { (date, items) ->
            val label = when (date) {
                today -> "TODAY"
                tomorrow -> "TOMORROW"
                else -> DATE_FORMATTER.format(date)
            }
            label to items.sortedByDescending { it.departureEpoch ?: it.searchedAtMillis }
        }
}

private fun findFirstFutureGroupIndex(
    grouped: List<Pair<String, List<TripHistoryItem>>>,
    nowEpoch: Long,
): Int {
    var itemIndex = 0
    grouped.forEachIndexed { _, (_, items) ->
        itemIndex++ // sticky header
        val hasFuture = items.any { (it.departureEpoch ?: 0L) > nowEpoch }
        if (hasFuture) return itemIndex - 1
        itemIndex += items.size
    }
    return -1
}

private val JourneysTab.label: String get() = when (this) {
    JourneysTab.ACTIVE -> "Active"
    JourneysTab.PAST -> "Past"
    JourneysTab.PLANNED -> "Planned"
}
