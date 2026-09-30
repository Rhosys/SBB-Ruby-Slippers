package ch.rhosys.sbb.domain

import ch.rhosys.sbb.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportModeTest {

    @Test
    fun `basic and extended GTFS route types map to the right mode`() {
        assertEquals(TransportMode.TRAIN, TransportMode.fromGtfsRouteType(2))
        assertEquals(TransportMode.TRAIN, TransportMode.fromGtfsRouteType(106)) // regional rail
        assertEquals(TransportMode.TRAM, TransportMode.fromGtfsRouteType(0))
        assertEquals(TransportMode.TRAM, TransportMode.fromGtfsRouteType(900))
        assertEquals(TransportMode.BUS, TransportMode.fromGtfsRouteType(3))
        assertEquals(TransportMode.BUS, TransportMode.fromGtfsRouteType(700))
        assertEquals(TransportMode.FERRY, TransportMode.fromGtfsRouteType(1000))
        assertEquals(TransportMode.CABLEWAY, TransportMode.fromGtfsRouteType(1300))
        assertEquals(TransportMode.CABLEWAY, TransportMode.fromGtfsRouteType(1400))
        assertEquals(TransportMode.OTHER, TransportMode.fromGtfsRouteType(1500))
    }

    @Test
    fun `API icons map to the right mode`() {
        assertEquals(TransportMode.TRAIN, TransportMode.fromApiIcon("train"))
        assertEquals(TransportMode.FERRY, TransportMode.fromApiIcon("ship"))
        assertEquals(TransportMode.OTHER, TransportMode.fromApiIcon(null))
    }
}
