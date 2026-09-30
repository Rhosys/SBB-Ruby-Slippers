package ch.rhosys.sbb.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.MotionEvent
import ch.rhosys.sbb.domain.model.MapStop
import ch.rhosys.sbb.domain.model.TransportMode
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

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

private const val TAP_RADIUS_DP = 24f
// Collision-grid cell size; about one short label wide, so each rect lands in few cells.
private const val GRID_CELL_PX = 128

// Draws each stop inside the viewport that suits the current zoom as a colored dot
// with its name centred underneath — a stop and its name always appear together.
// Where labels would collide, the later stop is skipped entirely (dot and name), so
// a zoomed-out map thins itself out instead of turning into overlapping text. Stops
// are placed train-first, so busier modes give way to stations. Drawn directly on
// the canvas rather than as one osmdroid Marker per stop, which would not scale to
// the several thousand stops Switzerland has.
internal class StopsOverlay(
    private val density: Float,
    private val onStopTapped: (MapStop) -> Unit,
) : Overlay() {
    // Stable sort: within a mode the feed's own order is kept, so which of two
    // colliding stops wins stays the same from frame to frame (no flicker on pan).
    var stops: List<MapStop> = emptyList()
        set(value) { field = value.sortedBy { it.mode.ordinal } }
    var selected: MapStop? = null

    private class Placed(val stop: MapStop, val x: Float, val y: Float, val radius: Float)

    // What the last frame actually drew — taps only ever hit a stop the user can see.
    private var placed: List<Placed> = emptyList()

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
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0x21, 0x21, 0x21)
    }
    private val labelAscent = -labelText.fontMetrics.ascent
    private val labelHeight = labelText.fontMetrics.let { it.descent - it.ascent }
    private val labelGap = 2f * density
    private val labelPadding = 3f * density
    // measureText on thousands of names every frame is wasteful; widths never change.
    private val labelWidths = HashMap<String, Float>()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        val grid = HashMap<Long, MutableList<RectF>>()
        val frame = ArrayList<Placed>()

        // The selected stop reserves its space first, so it is never the one dropped.
        selected?.let { stop -> place(stop, projection, isSelected = true, grid)?.let(frame::add) }
        forEachVisible(mapView, zoom) { stop ->
            if (stop != selected) place(stop, projection, isSelected = false, grid)?.let(frame::add)
        }
        // Drawn in reverse so the selected stop (and stations) end up on top.
        for (i in frame.indices.reversed()) drawPlaced(canvas, frame[i])
        placed = frame
    }

    private fun place(
        stop: MapStop,
        projection: Projection,
        isSelected: Boolean,
        grid: HashMap<Long, MutableList<RectF>>,
    ): Placed? {
        geo.setCoords(stop.lat, stop.lng)
        projection.toPixels(geo, point)
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val baseRadius = if (stop.mode == TransportMode.TRAIN) 7f else 5f
        val radius = (if (isSelected) baseRadius + 5f else baseRadius) * density
        val halfWidth = maxOf(radius, labelWidth(stop.name) / 2f) + labelPadding
        val rect = RectF(
            x - halfWidth,
            y - radius - labelPadding,
            x + halfWidth,
            y + radius + labelGap + labelHeight + labelPadding,
        )
        val cells = cellsOf(rect)
        if (!isSelected && cells.any { cell -> grid[cell]?.any { RectF.intersects(it, rect) } == true }) return null
        for (cell in cells) grid.getOrPut(cell) { ArrayList(4) }.add(rect)
        return Placed(stop, x, y, radius)
    }

    private fun drawPlaced(canvas: Canvas, p: Placed) {
        fill.color = p.stop.mode.color()
        canvas.drawCircle(p.x, p.y, p.radius, fill)
        canvas.drawCircle(p.x, p.y, p.radius, ring)
        val baseline = p.y + p.radius + labelGap + labelAscent
        canvas.drawText(p.stop.name, p.x, baseline, labelHalo)
        canvas.drawText(p.stop.name, p.x, baseline, labelText)
    }

    private fun labelWidth(name: String): Float = labelWidths.getOrPut(name) { labelText.measureText(name) }

    private fun cellsOf(rect: RectF): List<Long> {
        val left = Math.floorDiv(rect.left.toInt(), GRID_CELL_PX)
        val right = Math.floorDiv(rect.right.toInt(), GRID_CELL_PX)
        val top = Math.floorDiv(rect.top.toInt(), GRID_CELL_PX)
        val bottom = Math.floorDiv(rect.bottom.toInt(), GRID_CELL_PX)
        val cells = ArrayList<Long>((right - left + 1) * (bottom - top + 1))
        for (cx in left..right) for (cy in top..bottom) cells.add((cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL))
        return cells
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val maxDistance = TAP_RADIUS_DP * density
        val hit = placed
            .map { it to hypot(it.x - e.x, it.y - e.y) }
            .filter { (_, distance) -> distance <= maxDistance }
            .minByOrNull { (_, distance) -> distance }
            ?.first ?: return false
        onStopTapped(hit.stop)
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
