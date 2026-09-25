package ch.rhosys.sbb.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import ch.rhosys.sbb.domain.model.Connection
import ch.rhosys.sbb.domain.model.Leg
import ch.rhosys.sbb.domain.model.Place
import ch.rhosys.sbb.domain.model.SearchEndpoint
import ch.rhosys.sbb.domain.model.Stop
import ch.rhosys.sbb.ui.theme.SbbRubySlippersTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

// Part 1 of the Home → Search reset work: which Home callback fires for each Home action.
// Deliberately says nothing about what the Search tab then shows — that's covered on its
// own in AppNavigatorTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class HomeActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val tripSearches = mutableListOf<Pair<String, String>>()

    private val actions = HomeActions(
        startTripSearch = { from, to -> tripSearches += from to to; calls += "startTripSearch" },
        openJourneys = { calls += "openJourneys" },
        openHomeEdit = { calls += "openHomeEdit" },
        fillFromWithNearestStop = { calls += "fillFrom" },
        fillToWithNearestStop = { calls += "fillTo" },
        onQuickSearchChanged = {},
        selectQuickSearchSuggestion = {},
        hideOverlay = {},
        showOverlay = {},
    )

    private val work = Place(id = 1, name = "Work", lat = 47.0, lng = 8.0, gridX = 0, gridY = 0)
    private val gym = Place(id = 2, name = "Gym", lat = 47.1, lng = 8.1, gridX = 6, gridY = 0)

    private fun render(state: HomeUiState) {
        composeRule.setContent {
            SbbRubySlippersTheme { HomeContent(state = state, actions = actions) }
        }
    }

    private fun connection(from: String, to: String): Connection {
        val dep = Instant.parse("2026-09-25T08:00:00Z")
        val leg = Leg.Transit(
            departure = Stop(stationName = from, scheduledTime = dep),
            arrival = Stop(stationName = to, scheduledTime = dep.plusSeconds(600)),
            lineName = "S12",
            lineCategory = "S",
            direction = to,
        )
        return Connection(
            departure = leg.departure,
            arrival = leg.arrival,
            legs = listOf(leg),
            transfers = 0,
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )
    }

    // Drags from the centre of one node to the centre of another, in small steps so the
    // gesture passes touch slop like a real finger.
    private fun dragBetween(fromText: String, toText: String) {
        val fromBounds = composeRule.onNodeWithText(fromText).getBoundsInRoot()
        val toBounds = composeRule.onNodeWithText(toText).getBoundsInRoot()
        val delta = with(composeRule.density) { centerPx(toBounds) - centerPx(fromBounds) }
        composeRule.onNodeWithText(fromText).performTouchInput {
            down(center)
            repeat(10) { moveBy(delta / 10f) }
            up()
        }
    }

    private fun androidx.compose.ui.unit.Density.centerPx(r: DpRect) = Offset(
        ((r.left + r.right) / 2).toPx(),
        ((r.top + r.bottom) / 2).toPx(),
    )

    @Test
    fun `tapping a place tile starts a trip search from current location to that place`() {
        render(HomeUiState(isLoading = false, places = listOf(work, gym)))
        composeRule.onNodeWithText("Work").performClick()
        assertEquals(listOf(SearchEndpoint.CURRENT_LOCATION_LABEL to "Work"), tripSearches)
    }

    @Test
    fun `dragging tile A onto tile B starts a trip search from A to B`() {
        render(HomeUiState(isLoading = false, places = listOf(work, gym)))
        dragBetween("Work", "Gym")
        composeRule.waitForIdle()
        assertEquals(listOf("Work" to "Gym"), tripSearches)
    }

    @Test
    fun `submitting the quick search starts a trip search from current location to the typed text`() {
        render(HomeUiState(isLoading = false, places = listOf(work), quickSearchText = "Bern"))
        composeRule.onNodeWithText("Search connections").performClick()
        assertEquals(listOf(SearchEndpoint.CURRENT_LOCATION_LABEL to "Bern"), tripSearches)
    }

    @Test
    fun `tapping the scorer card starts a trip search for the scorer's from and to`() {
        val scorer = ScorerResult(
            destination = "Gym",
            connections = listOf(connection("Winterthur", "Gym stop")),
            from = SearchEndpoint.NamedPlace("Winterthur"),
            to = SearchEndpoint.NamedPlace("Gym"),
        )
        render(HomeUiState(isLoading = false, places = listOf(work), scorerResult = scorer))
        composeRule.onNodeWithText("S12").performClick()
        assertEquals(listOf("Winterthur" to "Gym"), tripSearches)
    }

    @Test
    fun `tapping the From field starts a trip search with the form's from and to`() {
        render(HomeUiState(isLoading = false, places = listOf(work), fromText = "Winterthur", toText = "Bern"))
        composeRule.onNodeWithContentDescription("Edit From").performClick()
        assertEquals(listOf("Winterthur" to "Bern"), tripSearches)
    }

    @Test
    fun `tapping the To field starts a trip search with the form's from and to`() {
        render(HomeUiState(isLoading = false, places = listOf(work), fromText = "Winterthur", toText = "Bern"))
        composeRule.onNodeWithContentDescription("Edit To").performClick()
        assertEquals(listOf("Winterthur" to "Bern"), tripSearches)
    }

    @Test
    fun `tapping the active journey card opens Journeys and does not start a trip search`() {
        val banner = ActiveJourneyBanner(
            connection = connection("Winterthur", "Bern"),
            from = SearchEndpoint.NamedPlace("Winterthur"),
            to = SearchEndpoint.NamedPlace("Bern"),
        )
        render(HomeUiState(isLoading = false, places = listOf(work), activeJourney = banner))
        composeRule.onNodeWithText("Tap for details →").performClick()
        assertEquals(listOf("openJourneys"), calls)
    }

    @Test
    fun `tapping manage places opens Home edit and does not start a trip search`() {
        render(HomeUiState(isLoading = false, places = listOf(work)))
        composeRule.onNodeWithContentDescription("Manage places").performClick()
        assertEquals(listOf("openHomeEdit"), calls)
    }

    @Test
    fun `tapping the add button with no places opens Home edit and does not start a trip search`() {
        render(HomeUiState(isLoading = false, places = emptyList()))
        composeRule.onNodeWithContentDescription("Add place").performClick()
        assertEquals(listOf("openHomeEdit"), calls)
    }

    @Test
    fun `tapping From here and To here fills the form and calls no navigation`() {
        render(HomeUiState(isLoading = false, places = listOf(work)))
        composeRule.onNodeWithText("From here").performClick()
        composeRule.onNodeWithText("To here").performClick()
        assertEquals(listOf("fillFrom", "fillTo"), calls)
    }

    @Test
    fun `a drag that ends back on the same tile calls nothing`() {
        render(HomeUiState(isLoading = false, places = listOf(work, gym)))
        composeRule.onNodeWithText("Work").performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(10f, 0f)) }
            repeat(5) { moveBy(Offset(-10f, 0f)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }
}
