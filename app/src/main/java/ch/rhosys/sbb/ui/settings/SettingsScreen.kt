package ch.rhosys.sbb.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Instant
import kotlin.math.roundToInt

// Free API-token signup/management portal for opentransportdata.swiss (see todo.md, Todo 5).
private const val RT_TOKEN_SIGNUP_URL = "https://api-manager.opentransportdata.swiss/"

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Permission state for notification reminders — must be called unconditionally.
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    // Calendar sync needs READ_CALENDAR before either enabling the toggle or running a
    // manual sync; the pending action runs only if the permission prompt is granted.
    var pendingCalendarAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingCalendarAction
        pendingCalendarAction = null
        if (granted) action?.invoke() else viewModel.onCalendarPermissionDenied()
    }
    fun requestCalendarPermissionThen(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCalendarAction = action
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

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
                enabled = !state.calendarSyncing,
                onCheckedChange = { checked ->
                    if (checked) {
                        requestCalendarPermissionThen { viewModel.enableCalendarSync() }
                    } else {
                        viewModel.disableCalendarSync()
                    }
                },
            )
        }
        Text(
            "Reads events with a location from your device's calendars (Google, Exchange, etc.) " +
                "and turns them into saved routes — nothing is written back to your calendar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { requestCalendarPermissionThen { viewModel.syncCalendarNow() } },
                enabled = !state.calendarSyncing,
            ) {
                Text(if (state.calendarSyncing) "Syncing…" else "Sync now")
            }
        }

        val calendarSyncError = state.calendarSyncError
        if (calendarSyncError != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                calendarSyncError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionLabel("Real-time data")

        OutlinedTextField(
            value = state.rtToken,
            onValueChange = viewModel::setRtToken,
            label = { Text("opentransportdata.swiss token") },
            placeholder = { Text("Paste your API token here") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RtTokenStatus(
                token = state.rtToken,
                lastSuccessEpoch = state.rtLastSuccessEpoch,
                lastErrorEpoch = state.rtLastErrorEpoch,
                lastErrorMessage = state.rtLastErrorMessage,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Get a token →",
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RT_TOKEN_SIGNUP_URL)))
                    },
            )
        }
        Text(
            "Required for live delays and service alerts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionLabel("Departure reminders")

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Push notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (notifGranted) "Granted — recurring reminders active"
                        else "Not granted — recurring reminders won't fire",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notifGranted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!notifGranted) {
                    OutlinedButton(
                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    ) {
                        Text("Enable")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionLabel("Arrival detection")

            val bgLocationGranted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Background location", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (bgLocationGranted) "Granted — geofence clears journey on arrival"
                        else "Not granted — arrival time fallback active",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bgLocationGranted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!bgLocationGranted) {
                    Spacer(Modifier.height(8.dp))
                    // On API 30+, the system only allows granting "Always Allow" via app settings.
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    }) {
                        Text("Open settings")
                    }
                }
            }
            Text(
                "\"Always allow\" location lets the app detect arrival at your destination and clear the active journey automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RtTokenStatus(
    token: String,
    lastSuccessEpoch: Long?,
    lastErrorEpoch: Long?,
    lastErrorMessage: String?,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when {
        token.isBlank() -> "Not configured" to MaterialTheme.colorScheme.onSurfaceVariant
        lastErrorEpoch != null && (lastSuccessEpoch == null || lastErrorEpoch > lastSuccessEpoch) ->
            "Failed ${formatRelativeTime(lastErrorEpoch)} — ${lastErrorMessage ?: "unknown error"}" to
                MaterialTheme.colorScheme.error
        lastSuccessEpoch != null ->
            "Live · updated ${formatRelativeTime(lastSuccessEpoch)}" to MaterialTheme.colorScheme.primary
        else -> "Waiting for first sync…" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

private fun formatRelativeTime(epochSecond: Long): String {
    val deltaSeconds = Instant.now().epochSecond - epochSecond
    return when {
        deltaSeconds < 60 -> "just now"
        deltaSeconds < 3600 -> "${deltaSeconds / 60}m ago"
        deltaSeconds < 86400 -> "${deltaSeconds / 3600}h ago"
        else -> "${deltaSeconds / 86400}d ago"
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
