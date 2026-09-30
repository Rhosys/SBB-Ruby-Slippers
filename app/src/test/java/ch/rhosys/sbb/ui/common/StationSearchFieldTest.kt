package ch.rhosys.sbb.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.rhosys.sbb.ui.theme.SbbRubySlippersTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Tapping a station field opens the near-full-screen search popup instead of editing
// in place; every way of choosing closes it again. Robolectric, like the other UI tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class StationSearchFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selected: String? = null
    private var currentLocationTapped = false

    private fun setField(withCurrentLocation: Boolean) {
        composeRule.setContent {
            SbbRubySlippersTheme {
                var value by remember { mutableStateOf("") }
                StationSearchField(
                    value = value,
                    onValueChange = { value = it },
                    label = "From",
                    suggestions = listOf("Zürich HB", "Zürich Oerlikon"),
                    onSuggestionSelected = { selected = it; value = it },
                    onCurrentLocation = if (withCurrentLocation) ({ currentLocationTapped = true }) else null,
                )
            }
        }
    }

    @Test
    fun tappingFieldOpensPopupWithBigButtons() {
        setField(withCurrentLocation = true)
        composeRule.onNodeWithText("Choose on map").assertDoesNotExist()

        composeRule.onNodeWithText("From").performClick()

        composeRule.onNodeWithText("Choose on map").assertIsDisplayed()
        composeRule.onNodeWithText("Current location").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test
    fun choosingSuggestionClosesPopup() {
        setField(withCurrentLocation = false)
        composeRule.onNodeWithText("From").performClick()

        composeRule.onNodeWithText("Zürich HB").performClick()

        assertEquals("Zürich HB", selected)
        composeRule.onNodeWithText("Choose on map").assertDoesNotExist()
    }

    @Test
    fun currentLocationButtonFiresAndCloses() {
        setField(withCurrentLocation = true)
        composeRule.onNodeWithText("From").performClick()

        composeRule.onNodeWithText("Current location").performClick()

        assertTrue(currentLocationTapped)
        composeRule.onNodeWithText("Choose on map").assertDoesNotExist()
    }

    @Test
    fun currentLocationButtonHiddenWithoutCallback() {
        setField(withCurrentLocation = false)
        composeRule.onNodeWithText("From").performClick()

        composeRule.onNodeWithText("Current location").assertDoesNotExist()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Choose on map").assertDoesNotExist()
    }
}
