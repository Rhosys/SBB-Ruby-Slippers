package ch.rhosys.sbb.ui.stationboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import ch.rhosys.sbb.data.remote.dto.StopDto
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartureDetailsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DepartureDetailsViewModel = hiltViewModel(),
) {
    val entry by viewModel.entry.collectAsState()

    val title = entry?.let {
        listOfNotNull(it.category, it.number).joinToString(" ").ifBlank { it.name ?: "" } +
                (it.to?.let { to -> " → $to" } ?: "")
    } ?: "Departure"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No departure selected")
            }
            return@Scaffold
        }

        val stops = entry!!.passList
        if (stops.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No stop information available", style = MaterialTheme.typography.bodyMedium)
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
            itemsIndexed(stops) { index, stop ->
                PassStopRow(stop = stop, isFirst = index == 0, isLast = index == stops.lastIndex)
                if (index < stops.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
            }
        }
    }
}

@Composable
private fun PassStopRow(stop: StopDto, isFirst: Boolean, isLast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val timeText = when {
            isFirst -> stop.departure.toDisplayTime()
            isLast  -> stop.arrival.toDisplayTime()
            else    -> stop.arrival.toDisplayTime()
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(52.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stop.station?.name ?: "—",
                style = MaterialTheme.typography.bodyLarge,
            )
            val delay = stop.delay
            if (delay != null && delay > 0) {
                Text(
                    text = "+$delay min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        stop.platform?.let { platform ->
            Text(
                text = platform,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun String?.toDisplayTime(): String {
    if (this == null) return "—"
    return try {
        OffsetDateTime.parse(this).run { "%02d:%02d".format(hour, minute) }
    } catch (_: Exception) { this }
}
