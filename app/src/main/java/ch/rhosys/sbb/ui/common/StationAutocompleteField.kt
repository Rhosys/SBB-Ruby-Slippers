package ch.rhosys.sbb.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
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
) {
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { fieldSize = it.size },
            singleLine = true,
            trailingIcon = if (onGpsClick != null) {
                {
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
