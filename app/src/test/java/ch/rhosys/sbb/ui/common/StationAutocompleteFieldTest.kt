package ch.rhosys.sbb.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.ui.theme.SbbRubySlippersTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Covers the search screen's from/to input, including the "Current location" badge
// interaction fixed in this change: tapping it must reveal an editable field, and
// tapping away without typing must restore the badge/value rather than clearing it.
//
// Runs as a JVM unit test via Robolectric rather than an instrumented androidTest, so
// it executes in the existing "Unit Tests" CI job without needing an emulator. Pinned
// below API 31 so SbbRubySlippersTheme takes its static (non-dynamic) color branch.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class StationAutocompleteFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typingUpdatesValue() {
        composeRule.setContent {
            SbbRubySlippersTheme {
                var value by remember { mutableStateOf("") }
                StationAutocompleteField(
                    value = value,
                    onValueChange = { value = it },
                    label = "From",
                    suggestions = emptyList(),
                    onSuggestionSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("From").performTextInput("Zurich")
        composeRule.onNodeWithText("Zurich").assertIsDisplayed()
    }

    @Test
    fun tappingSuggestionSelectsIt() {
        composeRule.setContent {
            SbbRubySlippersTheme {
                var value by remember { mutableStateOf("Zur") }
                var selected by remember { mutableStateOf<String?>(null) }
                StationAutocompleteField(
                    value = value,
                    onValueChange = { value = it },
                    label = "From",
                    suggestions = listOf("Zurich HB", "Zug"),
                    onSuggestionSelected = { selected = it; value = it },
                )
                Text("Selected: ${selected ?: "none"}")
            }
        }

        composeRule.onNodeWithText("Zurich HB").performClick()
        composeRule.onNodeWithText("Selected: Zurich HB").assertIsDisplayed()
    }

    @Test
    fun currentLocationShowsBadgeInsteadOfRawPlaceholder() {
        composeRule.setContent {
            SbbRubySlippersTheme {
                StationAutocompleteField(
                    value = SearchEndpoint.CURRENT_LOCATION_LABEL,
                    onValueChange = {},
                    label = "From",
                    suggestions = emptyList(),
                    onSuggestionSelected = {},
                    isCurrentLocation = true,
                    currentLocationStationName = "Bern, Bahnhof",
                )
            }
        }

        composeRule.onNodeWithText("Bern, Bahnhof").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Current location — tap to edit").assertIsDisplayed()
    }

    @Test
    fun tappingBadgeRevealsEditableFieldForTyping() {
        composeRule.setContent {
            SbbRubySlippersTheme {
                var value by remember { mutableStateOf(SearchEndpoint.CURRENT_LOCATION_LABEL) }
                var isCurrentLocation by remember { mutableStateOf(true) }
                StationAutocompleteField(
                    value = value,
                    onValueChange = {
                        value = it
                        isCurrentLocation = it == SearchEndpoint.CURRENT_LOCATION_LABEL
                    },
                    label = "From",
                    suggestions = emptyList(),
                    onSuggestionSelected = {},
                    isCurrentLocation = isCurrentLocation,
                    currentLocationStationName = "Bern, Bahnhof",
                )
            }
        }

        composeRule.onNodeWithText("Bern, Bahnhof").performClick()
        composeRule.onAllNodesWithText("Bern, Bahnhof").assertCountEquals(0)

        composeRule.onNodeWithText("From").performTextInput("Basel")
        composeRule.onNodeWithText("Basel").assertIsDisplayed()
    }

    @Test
    fun tappingAwayWithoutTypingRestoresCurrentLocationBadge() {
        composeRule.setContent {
            SbbRubySlippersTheme {
                var value by remember { mutableStateOf(SearchEndpoint.CURRENT_LOCATION_LABEL) }
                var isCurrentLocation by remember { mutableStateOf(true) }
                Column {
                    StationAutocompleteField(
                        value = value,
                        onValueChange = {
                            value = it
                            isCurrentLocation = it == SearchEndpoint.CURRENT_LOCATION_LABEL
                        },
                        label = "From",
                        suggestions = emptyList(),
                        onSuggestionSelected = {},
                        isCurrentLocation = isCurrentLocation,
                        currentLocationStationName = "Bern, Bahnhof",
                    )
                    // Stand-in for "tapping out" elsewhere on the screen — moving focus
                    // away without ever typing into the revealed field.
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("Elsewhere") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Bern, Bahnhof").performClick()
        composeRule.onAllNodesWithText("Bern, Bahnhof").assertCountEquals(0)

        composeRule.onNodeWithText("Elsewhere").performClick()

        composeRule.onNodeWithText("Bern, Bahnhof").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Current location — tap to edit").assertIsDisplayed()
    }
}
