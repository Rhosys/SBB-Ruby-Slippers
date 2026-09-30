package ch.rhosys.sbb.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ch.rhosys.sbb.domain.model.MapStop
import ch.rhosys.sbb.domain.model.TransportMode
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

// Free, key-less OpenStreetMap vector tiles (same style as the hide-and-seek web app).
internal const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

internal const val STOPS_SOURCE = "stops"
internal const val SELECTED_SOURCE = "selected-stop"
internal const val USER_LOCATION_SOURCE = "user-location"
private const val SELECTED_LAYER = "selected-stop"

internal const val PROP_NAME = "name"
internal const val PROP_MODE = "mode"
private const val PROP_ICON = "icon"

// Only the fonts the liberty style's glyph server actually hosts will render.
private val FONT_REGULAR = arrayOf("Noto Sans Regular")
private val FONT_BOLD = arrayOf("Noto Sans Bold")

// Which stops are worth drawing at a zoom level: zoomed out only train stations
// (plus ferries/cableways, which are sparse landmarks), trams once a city fills the
// screen, and buses — by far the densest mode — only at street level.
internal fun TransportMode.minZoom(): Float = when (this) {
    TransportMode.TRAIN -> 0f
    TransportMode.FERRY, TransportMode.CABLEWAY -> 9.5f
    TransportMode.TRAM -> 11.5f
    TransportMode.BUS, TransportMode.OTHER -> 13f
}

internal fun TransportMode.color(): Int = when (this) {
    TransportMode.TRAIN -> Color.rgb(0xEB, 0x00, 0x00)
    TransportMode.TRAM -> Color.rgb(0x2E, 0x7D, 0x32)
    TransportMode.BUS -> Color.rgb(0x15, 0x65, 0xC0)
    TransportMode.FERRY -> Color.rgb(0x00, 0x83, 0x8F)
    TransportMode.CABLEWAY -> Color.rgb(0x6A, 0x1B, 0x9A)
    TransportMode.OTHER -> Color.rgb(0x61, 0x61, 0x61)
}

internal fun stopLayerId(mode: TransportMode) = "stops-${mode.name}"

internal val STOP_LAYER_IDS: Array<String> = TransportMode.entries.map(::stopLayerId).toTypedArray()

private fun iconName(mode: TransportMode) = "stop-${mode.name}"

internal fun MapStop.toFeature(): Feature = Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
    addStringProperty(PROP_NAME, name)
    addStringProperty(PROP_MODE, mode.name)
    addStringProperty(PROP_ICON, iconName(mode))
}

internal fun stopsCollection(stops: List<MapStop>): FeatureCollection =
    FeatureCollection.fromFeatures(stops.map { it.toFeature() })

// Adds the stop, selected-stop and user-location layers on top of the base style.
//
// Each stop is ONE symbol — its colored dot is the symbol's icon and its name the text
// under it — so the dot and the name always appear together: when a label would collide,
// MapLibre drops the whole symbol rather than leaving an unlabelled dot. There is one layer
// per mode so each gets its own minimum zoom; they're stacked train-last because MapLibre
// places symbols from the top layer down, so stations win collisions over bus stops.
internal fun addStopLayers(style: Style, density: Float) {
    for (mode in TransportMode.entries) style.addImage(iconName(mode), dotBitmap(mode, density))

    style.addSource(GeoJsonSource(USER_LOCATION_SOURCE))
    style.addLayer(
        CircleLayer("user-location-halo", USER_LOCATION_SOURCE).withProperties(
            circleRadius(22f),
            circleColor(Color.argb(0x40, 0x1A, 0x73, 0xE8)),
        ),
    )
    style.addLayer(
        CircleLayer("user-location-dot", USER_LOCATION_SOURCE).withProperties(
            circleRadius(7f),
            circleColor(Color.rgb(0x1A, 0x73, 0xE8)),
            circleStrokeWidth(2.5f),
            circleStrokeColor(Color.WHITE),
        ),
    )

    style.addSource(GeoJsonSource(STOPS_SOURCE))
    for (mode in TransportMode.entries.reversed()) {
        val layer = SymbolLayer(stopLayerId(mode), STOPS_SOURCE)
            .withFilter(eq(get(PROP_MODE), mode.name))
            .withProperties(*stopSymbolProperties(FONT_REGULAR, iconScale = 1f, allowOverlap = false))
        layer.setMinZoom(mode.minZoom())
        style.addLayer(layer)
    }

    // The tapped stop: drawn larger, always shown, and placed first (topmost layer) so its
    // own regular symbol and any neighbour it now overlaps give way to it.
    style.addSource(GeoJsonSource(SELECTED_SOURCE))
    style.addLayer(
        SymbolLayer(SELECTED_LAYER, SELECTED_SOURCE)
            .withProperties(*stopSymbolProperties(FONT_BOLD, iconScale = 1.6f, allowOverlap = true)),
    )
}

private fun stopSymbolProperties(font: Array<String>, iconScale: Float, allowOverlap: Boolean): Array<PropertyValue<*>> = arrayOf(
    iconImage(get(PROP_ICON)),
    iconSize(iconScale),
    iconAllowOverlap(allowOverlap),
    textField(get(PROP_NAME)),
    textFont(font),
    textSize(12f),
    textMaxWidth(8f),
    textColor(Color.rgb(0x21, 0x21, 0x21)),
    textHaloColor(Color.WHITE),
    textHaloWidth(1.5f),
    textAnchor(Property.TEXT_ANCHOR_TOP),
    textOffset(arrayOf(0f, 0.6f * iconScale)),
    textAllowOverlap(allowOverlap),
)

// Shows/hides the base style's own layers per the user's toggles.
internal fun applyBaseLayerVisibility(style: Style, hidden: Set<MapLayerGroup>) {
    for (layer in style.layers) {
        val id = layer.id
        val visible = when {
            id == MapLayerGroup.STYLE_TRANSIT_LAYER -> false
            else -> MapLayerGroup.of(id)?.let { it !in hidden } ?: continue
        }
        layer.setProperties(visibility(if (visible) Property.VISIBLE else Property.NONE))
    }
}

private fun dotBitmap(mode: TransportMode, density: Float): Bitmap {
    val radius = (if (mode == TransportMode.TRAIN) 7f else 5f) * density
    val ring = 2f * density
    val size = ((radius + ring) * 2).toInt() + 2
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f
    canvas.drawCircle(centre, centre, radius + ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    canvas.drawCircle(centre, centre, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mode.color() })
    return bitmap
}
