package ch.rhosys.sbb.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// Fixed regardless of how many suggestions come back, so the field below never
// jumps around as the user types — the popup overlay only appears/disappears,
// it never resizes the surrounding layout.
private val SUGGESTIONS_BOX_HEIGHT = 224.dp

/**
 * A station/place text field with debounced autocomplete suggestions and an
 * optional "use nearest stop" GPS button.
 *
 * The suggestion list renders in a [Popup] anchored below the field so it
 * overlays other content instead of pushing it down as the result count changes.
 */
@Composable
fun StationAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    onSuggestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onGpsClick: (() -> Unit)? = null,
    isLocating: Boolean = false,
    // Shows a small spinner (search in progress) or a checkmark (search finished, with
    // results to show) next to the field, so a slow lookup never looks like it's hung.
    isSearching: Boolean = false,
    // When true, `value` is the internal "current location" placeholder — instead of
    // showing that raw text, the field renders a colored badge (GPS icon + the resolved
    // nearest station, once known) in place of it. Tapping the badge or the field reveals
    // an empty, editable field to type over it; tapping away without typing anything
    // restores the badge instead of leaving the field blank.
    isCurrentLocation: Boolean = false,
    currentLocationStationName: String? = null,
) {
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    // Resets whenever isCurrentLocation flips (e.g. a fresh GPS fill), so a new
    // "Current location" value always starts out showing the badge again.
    var isRevealed by remember(isCurrentLocation) { mutableStateOf(false) }
    val showBadge = isCurrentLocation && !isRevealed

    Box(modifier = modifier) {
        OutlinedTextField(
            // Blank whenever the value is still the unedited "current location"
            // placeholder — both before reveal (badge shown) and right after (revealed
            // but the user hasn't typed a replacement character yet).
            value = if (isCurrentLocation) "" else value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { fieldSize = it.size }
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        if (isCurrentLocation) isRevealed = true
                    } else if (isCurrentLocation && isRevealed) {
                        // Lost focus without typing anything over the placeholder —
                        // restore the badge instead of leaving the field blank.
                        isRevealed = false
                    }
                },
            singleLine = true,
            readOnly = showBadge,
            leadingIcon = if (showBadge) {
                {
                    CurrentLocationBadge(
                        stationName = currentLocationStationName,
                        onClick = {
                            isRevealed = true
                            focusRequester.requestFocus()
                        },
                    )
                }
            } else null,
            trailingIcon = if (onGpsClick != null || isSearching || (!isCurrentLocation && value.trim().length >= 2)) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            isSearching -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            !isCurrentLocation && value.trim().length >= 2 -> Icon(
                                Icons.Default.Check,
                                contentDescription = "Search finished",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (onGpsClick != null) {
                            IconButton(onClick = onGpsClick, enabled = !isLocating) {
                                if (isLocating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(Icons.Default.GpsFixed, contentDescription = "Use nearest stop")
                                }
                            }
                        }
                    }
                }
            } else null,
        )

        if (suggestions.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldSize.height),
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    modifier = Modifier
                        .width(with(density) { fieldSize.width.toDp() })
                        .height(SUGGESTIONS_BOX_HEIGHT),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    LazyColumn(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                        itemsIndexed(suggestions) { index, suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { onSuggestionSelected(suggestion) },
                            )
                            if (index < suggestions.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// A small colored chip standing in for the raw "Current location" placeholder text,
// showing the GPS target icon plus the nearest resolved station (once known) so the
// user can see location resolution is actually working. Tapping it reveals an editable
// field to type over it.
@Composable
private fun CurrentLocationBadge(stationName: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Default.GpsFixed,
                contentDescription = "Current location — tap to edit",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stationName ?: "Current location",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}
