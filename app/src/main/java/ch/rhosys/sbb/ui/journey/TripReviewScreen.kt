package ch.rhosys.sbb.ui.journey

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.domain.model.Leg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripReviewScreen(
    onNavigateBack: () -> Unit,
    onJourneyStarted: () -> Unit,
    onNavigateToFares: () -> Unit,
    viewModel: TripReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val connection = state.connection

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(connection?.arrival?.stationName ?: "Trip details") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.onLeave()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToFares) {
                        Icon(
                            Icons.Default.MonetizationOn,
                            contentDescription = "See fares",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (connection != null) {
                Button(
                    onClick = {
                        if (viewModel.lockIn()) onJourneyStarted()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Start journey")
                }
            }
        },
    ) { innerPadding ->
        if (connection == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No trip selected")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Departs", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(connection.departure.displayTime(),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(connection.departure.stationName,
                            style = MaterialTheme.typography.bodyMedium)
                        if (connection.departure.platform != null) {
                            Text(
                                "Platform ${connection.departure.platform}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Arrives", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(connection.arrival.displayTime(),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(connection.arrival.stationName,
                            style = MaterialTheme.typography.bodyMedium)
                        if (connection.arrival.platform != null) {
                            Text(
                                "Platform ${connection.arrival.platform}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
internal fun LegRow(leg: Leg) {
    when (leg) {
        is Leg.Transit -> TransitLegRow(leg)
        is Leg.Walk -> WalkLegRow(leg)
    }
}

@Composable
private fun TransitLegRow(leg: Leg.Transit) {
    var stopsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                leg.departure.displayTime(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(52.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(leg.departure.stationName, style = MaterialTheme.typography.bodyLarge)
                    if (leg.departure.platform != null) {
                        Text(
                            "Pl. ${leg.departure.platform}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    buildString {
                        append("${leg.lineName} → ${leg.direction}")
                        if (leg.operator != null) append(" · ${leg.operator}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (leg.departure.isDelayed) {
                    Text(
                        "+${leg.departure.delayMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (leg.intermediateStops.isNotEmpty()) {
            Text(
                if (stopsExpanded) "▲ Hide stops" else "▼ ${leg.intermediateStops.size} stops",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 60.dp, top = 2.dp, bottom = 2.dp)
                    .clickable { stopsExpanded = !stopsExpanded },
            )
            if (stopsExpanded) {
                Column(Modifier.padding(start = 60.dp, bottom = 4.dp)) {
                    leg.intermediateStops.forEach { stop ->
                        Text(
                            stop.stationName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)) {
            Text(
                leg.arrival.displayTime(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(52.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(leg.arrival.stationName, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun WalkLegRow(leg: Leg.Walk) {
    Row(
        Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Walk",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${leg.durationMinutes} min · ${leg.toName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
