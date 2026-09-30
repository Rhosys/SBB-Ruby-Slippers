package ch.rhosys.sbb.ui.map

// Switchable groups of the OpenFreeMap "liberty" base style's own layers, matched by layer
// id (the style follows the OpenMapTiles layer naming). Same grouping as the hide-and-seek
// web app's MapLibre map. Layers in no group — named lakes/rivers, borders, background —
// always stay on as orientation landmarks.
enum class MapLayerGroup(val label: String, private val pattern: Regex) {
    ROADS(
        "Roads",
        Regex(
            "^(tunnel_(motorway|service|link|street|minor|secondary|tertiary|trunk|primary|path)" +
                "|road_(area|motorway|service|link|minor|secondary|tertiary|trunk|primary|path|one_way)" +
                "|bridge_(motorway|service|link|street|path|secondary|tertiary|trunk|primary)" +
                "|highway-shield|road_shield)",
        ),
    ),
    RAIL("Railways", Regex("^(tunnel|road|bridge)_(major_rail|transit_rail)")),
    LABELS("Street & place names", Regex("^(label_|highway-name)")),
    BUILDINGS("Buildings", Regex("^building")),
    POI("Points of interest", Regex("^(poi_|airport)")),
    WATER("Water", Regex("^(?!water_name_|waterway_line_label)(water|waterway)")),
    LAND("Parks & land use", Regex("^(landuse_|landcover_|park)")),
    ;

    companion object {
        // The style's own transit-stop icons would duplicate (and fight for label space
        // with) our stop layer, so they're always off regardless of the POI toggle.
        const val STYLE_TRANSIT_LAYER = "poi_transit"

        fun of(layerId: String): MapLayerGroup? = entries.firstOrNull { it.pattern.containsMatchIn(layerId) }
    }
}
