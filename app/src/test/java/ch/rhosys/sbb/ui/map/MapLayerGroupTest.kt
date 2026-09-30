package ch.rhosys.sbb.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Layer ids from OpenFreeMap's "liberty" style — the toggles must hit the right ones.
class MapLayerGroupTest {

    @Test
    fun `liberty layers fall into the expected groups`() {
        mapOf(
            "road_motorway" to MapLayerGroup.ROADS,
            "tunnel_street_casing" to MapLayerGroup.ROADS,
            "bridge_trunk_primary" to MapLayerGroup.ROADS,
            "highway-shield-non-us" to MapLayerGroup.ROADS,
            "road_major_rail" to MapLayerGroup.RAIL,
            "tunnel_transit_rail_hatching" to MapLayerGroup.RAIL,
            "label_city" to MapLayerGroup.LABELS,
            "highway-name-minor" to MapLayerGroup.LABELS,
            "building-3d" to MapLayerGroup.BUILDINGS,
            "poi_r20" to MapLayerGroup.POI,
            "airport" to MapLayerGroup.POI,
            "water" to MapLayerGroup.WATER,
            "waterway_river" to MapLayerGroup.WATER,
            "landcover_wood" to MapLayerGroup.LAND,
            "park_outline" to MapLayerGroup.LAND,
        ).forEach { (id, group) -> assertEquals(id, group, MapLayerGroup.of(id)) }
    }

    @Test
    fun `landmark and own layers stay out of every group`() {
        listOf("water_name_point_label", "waterway_line_label", "boundary_2", "background", "stops-TRAIN", "selected-stop")
            .forEach { assertNull(it, MapLayerGroup.of(it)) }
    }
}
