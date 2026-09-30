package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.buildMapStops
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import ch.rhosys.sbb.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MapStopsTest {

    @Test
    fun `platforms merge into one pin shown as the most prominent mode`() {
        val network = GtfsNetworkBuilder()
            .addStop(0, "Bern", 46.9490, 7.4390)        // parent station, no route of its own
            .addStop(1, "Bern", 46.9491, 7.4391)        // bus platform
            .addStop(2, "Bern", 46.9492, 7.4392)        // train platform
            .addStop(3, "Bern, Bärenplatz", 46.9470, 7.4440)
            .addStop(4, "Nowhere", 46.0, 7.0)           // served by no route at all
            .addRoute(0, "10", listOf(1, 3), TransportMode.BUS)
            .addRoute(1, "IC1", listOf(2, 3), TransportMode.TRAIN)
            .build()

        val stops = buildMapStops(network).associateBy { it.name }

        assertEquals(setOf("Bern", "Bern, Bärenplatz"), stops.keys)
        assertEquals(TransportMode.TRAIN, stops.getValue("Bern").mode)
        assertEquals(46.9490, stops.getValue("Bern").lat, 1e-9)
        assertEquals(TransportMode.TRAIN, stops.getValue("Bern, Bärenplatz").mode)
    }
}
