package ch.rhosys.sbb.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import ch.rhosys.sbb.domain.model.MapStop
import ch.rhosys.sbb.domain.model.TransportMode
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

// Which stops are worth drawing at a zoom level: zoomed out only train stations
// (plus ferries/cableways, which are sparse landmarks), trams once a city fills the
// screen, and buses — by far the densest mode — only at street level.
internal fun TransportMode.isVisibleAt(zoom: Double): Boolean = when (this) {
    TransportMode.TRAIN -> true
    TransportMode.FERRY, TransportMode.CABLEWAY -> zoom >= 10.5
    TransportMode.TRAM -> zoom >= 12.5
    TransportMode.BUS, TransportMode.OTHER -> zoom >= 14.0
}

internal fun TransportMode.color(): Int = when (this) {
    TransportMode.TRAIN -> Color.rgb(0xEB, 0x00, 0x00)
    TransportMode.TRAM -> Color.rgb(0x2E, 0x7D, 0x32)
    TransportMode.BUS -> Color.rgb(0x15, 0x65, 0xC0)
    TransportMode.FERRY -> Color.rgb(0x00, 0x83, 0x8F)
    TransportMode.CABLEWAY -> Color.rgb(0x6A, 0x1B, 0x9A)
    TransportMode.OTHER -> Color.rgb(0x61, 0x61, 0x61)
}

private const val LABEL_ZOOM_TRAIN = 13.0
private const val LABEL_ZOOM_ALL = 16.0
private const val TAP_RADIUS_DP = 24f

// Draws every stop inside the viewport that suits the current zoom as a colored
// dot (labelled once zoomed in far enough), and reports taps on them. Drawn directly
// on the canvas rather than as one osmdroid Marker per stop, which would not scale
// to the several thousand stops Switzerland has.
internal class StopsOverlay(
    private val density: Float,
    private val onStopTapped: (MapStop) -> Unit,
) : Overlay() {
    var stops: List<MapStop> = emptyList()
    var selected: MapStop? = null

    private val geo = GeoPoint(0.0, 0.0)
    private val point = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = Color.rgb(0x21, 0x21, 0x21)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        forEachVisible(mapView, zoom) { stop ->
            if (stop != selected) drawStop(canvas, projection, stop, zoom, isSelected = false)
        }
        // Drawn last so it sits on top of any neighbouring dot.
        selected?.let { drawStop(canvas, projection, it, zoom, isSelected = true) }
    }

    private fun drawStop(canvas: Canvas, projection: Projection, stop: MapStop, zoom: Double, isSelected: Boolean) {
        geo.setCoords(stop.lat, stop.lng)
        projection.toPixels(geo, point)
        val baseRadius = if (stop.mode == TransportMode.TRAIN) 7f else 5f
        val radius = (if (isSelected) baseRadius + 5f else baseRadius) * density
        fill.color = stop.mode.color()
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, fill)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, ring)

        val showLabel = isSelected || zoom >= LABEL_ZOOM_ALL ||
            (stop.mode == TransportMode.TRAIN && zoom >= LABEL_ZOOM_TRAIN)
        if (showLabel) {
            val x = point.x + radius + 4f * density
            val y = point.y + labelText.textSize / 3f
            canvas.drawText(stop.name, x, y, labelHalo)
            canvas.drawText(stop.name, x, y, labelText)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val maxDistance = TAP_RADIUS_DP * density
        var best: MapStop? = null
        var bestDistance = Float.MAX_VALUE
        forEachVisible(mapView, mapView.zoomLevelDouble) { stop ->
            geo.setCoords(stop.lat, stop.lng)
            projection.toPixels(geo, point)
            val dx = point.x - e.x
            val dy = point.y - e.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = stop
            }
        }
        val hit = best?.takeIf { bestDistance <= maxDistance } ?: return false
        onStopTapped(hit)
        return true
    }

    private inline fun forEachVisible(mapView: MapView, zoom: Double, action: (MapStop) -> Unit) {
        val box = mapView.boundingBox
        // A small margin so dots straddling the edge don't pop in and out while panning.
        val latMargin = (box.latNorth - box.latSouth) * 0.05
        val lngMargin = (box.lonEast - box.lonWest) * 0.05
        val north = box.latNorth + latMargin
        val south = box.latSouth - latMargin
        val east = box.lonEast + lngMargin
        val west = box.lonWest - lngMargin
        for (stop in stops) {
            if (stop.lat > north || stop.lat < south || stop.lng > east || stop.lng < west) continue
            if (!stop.mode.isVisibleAt(zoom)) continue
            action(stop)
        }
    }
}

// The user's own position: a blue dot with a white ring over a soft halo.
internal class UserLocationOverlay(private val density: Float) : Overlay() {
    var location: Pair<Double, Double>? = null

    private val geo = GeoPoint(0.0, 0.0)
    private val point = Point()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x40, 0x1A, 0x73, 0xE8) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1A, 0x73, 0xE8) }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val (lat, lng) = location ?: return
        geo.setCoords(lat, lng)
        mapView.projection.toPixels(geo, point)
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        canvas.drawCircle(x, y, 22f * density, halo)
        canvas.drawCircle(x, y, 9f * density, ring)
        canvas.drawCircle(x, y, 6.5f * density, dot)
    }
}
