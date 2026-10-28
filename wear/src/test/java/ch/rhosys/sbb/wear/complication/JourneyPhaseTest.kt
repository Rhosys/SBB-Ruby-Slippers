package ch.rhosys.sbb.wear.complication

import ch.rhosys.sbb.wear.WearJourneyData
import ch.rhosys.sbb.wear.WearTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPhaseTest {

    private val journey = WearJourneyData(
        from = "Zürich HB",
        to = "Lausanne",
        isActive = true,
        departureEpochSeconds = 1_000,
        transfers = listOf(WearTransfer("Bern", 4_000)),
        arrivalEpochSeconds = 8_000,
    )

    @Test
    fun noActiveJourneyHasNoPhases() {
        assertTrue(journeyPhases(WearJourneyData()).isEmpty())
    }

    @Test
    fun departureThenEachTransferThenArrival() {
        assertEquals(
            listOf(
                JourneyPhase.BeforeDeparture(Long.MIN_VALUE, 1_000, "Zürich HB"),
                JourneyPhase.ToTransfer(1_000, 4_000, "Bern"),
                JourneyPhase.ToArrival(4_000, 8_000, "Lausanne"),
            ),
            journeyPhases(journey),
        )
    }

    @Test
    fun directJourneyCountsDownToArrivalAfterDeparture() {
        val phases = journeyPhases(journey.copy(transfers = emptyList()))
        assertEquals(JourneyPhase.ToArrival(1_000, 8_000, "Lausanne"), phases.last())
        assertEquals(2, phases.size)
    }

    @Test
    fun transfersWithoutATimeAreSkipped() {
        val phases = journeyPhases(journey.copy(transfers = listOf(WearTransfer("Bern", null))))
        assertTrue(phases.none { it is JourneyPhase.ToTransfer })
    }
}
