package ch.rhosys.sbb.ui.common

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import ch.rhosys.sbb.ui.map.StopMapPicker

/**
 * A station field that doesn't edit in place: tapping it opens [StationSearchPopup],
 * a near-full-screen search with large "Current location" / "Choose on map" buttons.
 * The field itself just shows the chosen value (or the current-location badge).
 */
@Composable
fun StationSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    onSuggestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Null hides the popup's "Current location" button and the field's GPS shortcut.
    onCurrentLocation: (() -> Unit)? = null,
    isLocating: Boolean = false,
    isSearching: Boolean = false,
    isCurrentLocation: Boolean = false,
    currentLocationStationName: String? = null,
) {
    var isPopupOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                focusManager.clearFocus()
                isPopupOpen = true
            }
        }
    }

    OutlinedTextField(
        value = if (isCurrentLocation) "" else value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        interactionSource = interactionSource,
        modifier = modifier,
        leadingIcon = if (isCurrentLocation) {
            {
                CurrentLocationBadge(
                    stationName = currentLocationStationName,
                    onClick = { isPopupOpen = true },
                )
            }
        } else {
            { Icon(Icons.Default.Search, contentDescription = null) }
        },
        trailingIcon = if (onCurrentLocation != null) {
            {
                IconButton(onClick = onCurrentLocation, enabled = !isLocating) {
                    if (isLocating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.GpsFixed, contentDescription = "Use nearest stop")
                    }
                }
            }
        } else null,
    )

    if (isPopupOpen) {
        StationSearchPopup(
            initialText = if (isCurrentLocation) "" else value,
            title = label,
            suggestions = suggestions,
            isSearching = isSearching,
            onValueChange = onValueChange,
            onSuggestionSelected = { name ->
                isPopupOpen = false
                onSuggestionSelected(name)
            },
            onCurrentLocation = onCurrentLocation?.let { onGps ->
                {
                    isPopupOpen = false
                    onGps()
                }
            },
            onDismiss = { isPopupOpen = false },
        )
    }
}

/**
 * Near-full-screen station search: a large search field with live suggestions, then
 * big square "Current location" and "Choose on map" buttons above a "Back" button.
 * Every way of choosing (suggestion, keyboard search, current location, map stop)
 * closes the popup through its own callback; [onDismiss] only means "go back".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationSearchPopup(
    initialText: String,
    title: String,
    suggestions: List<String>,
    isSearching: Boolean,
    onValueChange: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onCurrentLocation: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var showMap by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Resize with the keyboard instead of letting it cover the bottom buttons.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        var fieldValue by remember {
            mutableStateOf(TextFieldValue(initialText, selection = TextRange(0, initialText.length)))
        }
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        // While typing, the square buttons shrink to a slim row so suggestions stay visible.
        val isCompact = WindowInsets.isImeVisible || fieldValue.text.isNotEmpty()

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.96f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)

                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { updated ->
                        val changed = updated.text != fieldValue.text
                        fieldValue = updated
                        if (changed) onValueChange(updated.text)
                    },
                    placeholder = { Text("Station, stop or address") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    shape = MaterialTheme.shapes.large,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = when {
                        isSearching -> {
                            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                        }
                        fieldValue.text.isNotEmpty() -> {
                            {
                                IconButton(onClick = {
                                    fieldValue = TextFieldValue("")
                                    onValueChange("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val typed = fieldValue.text.trim()
                        if (typed.isNotEmpty()) onSuggestionSelected(suggestions.firstOrNull() ?: typed)
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (suggestions.isEmpty()) {
                        Text(
                            text = if (fieldValue.text.trim().length >= 2 && !isSearching) {
                                "No matching stops yet — keep typing, or pick one on the map."
                            } else {
                                "Search for a station or stop, or use the buttons below."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(suggestions) { suggestion ->
                                ListItem(
                                    headlineContent = {
                                        Text(suggestion, style = MaterialTheme.typography.titleMedium)
                                    },
                                    leadingContent = {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Place,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSuggestionSelected(suggestion) },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    if (onCurrentLocation != null) {
                        SquareActionButton(
                            icon = Icons.Default.MyLocation,
                            text = "Current location",
                            isCompact = isCompact,
                            onClick = onCurrentLocation,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SquareActionButton(
                        icon = Icons.Default.Map,
                        text = "Choose on map",
                        isCompact = isCompact,
                        onClick = { showMap = true },
                        // Alone, it keeps the same square size rather than stretching wide.
                        modifier = if (onCurrentLocation != null) Modifier.weight(1f) else Modifier.fillMaxWidth(0.5f),
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back")
                }
            }
        }
    }

    if (showMap) {
        StopMapPicker(
            onStopChosen = { name ->
                showMap = false
                onSuggestionSelected(name)
            },
            onDismiss = { showMap = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SquareActionButton(
    icon: ImageVector,
    text: String,
    isCompact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = if (isCompact) modifier.height(64.dp) else modifier.aspectRatio(1f),
    ) {
        val iconBadge = @Composable {
            Box(
                modifier = Modifier
                    .size(if (isCompact) 36.dp else 64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(if (isCompact) 20.dp else 32.dp),
                )
            }
        }
        if (isCompact) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                iconBadge()
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                iconBadge()
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
