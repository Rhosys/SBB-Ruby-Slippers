package ch.rhosys.sbb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        SectionLabel("Pace")

        LabelledSlider(
            label = "Walking pace",
            value = state.walkingPaceKmh,
            valueLabel = "${state.walkingPaceKmh.roundToInt()} km/h",
            range = 2f..12f,
            steps = 9,
            onValueChange = viewModel::setWalkingPace,
        )

        LabelledSlider(
            label = "Running pace",
            value = state.runningPaceKmh,
            valueLabel = "${state.runningPaceKmh.roundToInt()} km/h",
            range = 6f..20f,
            steps = 13,
            onValueChange = viewModel::setRunningPace,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionLabel("Trip monitoring")

        LabelledSlider(
            label = "Switch prompt threshold",
            value = state.switchThresholdMinutes.toFloat(),
            valueLabel = "${state.switchThresholdMinutes} min",
            range = 1f..15f,
            steps = 13,
            onValueChange = { viewModel.setSwitchThreshold(it.roundToInt()) },
        )
        Text(
            "Minimum arrival improvement that triggers a Switch prompt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionLabel("Calendar")

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sync calendar events", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.calendarSyncEnabled,
                onCheckedChange = viewModel::setCalendarSync,
            )
        }

        if (state.calendarSyncEnabled) {
            Spacer(Modifier.height(8.dp))
            LabelledSlider(
                label = "Sync interval",
                value = state.calendarSyncIntervalHours.toFloat(),
                valueLabel = "${state.calendarSyncIntervalHours}h",
                range = 1f..12f,
                steps = 10,
                onValueChange = { viewModel.setCalendarSyncInterval(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
