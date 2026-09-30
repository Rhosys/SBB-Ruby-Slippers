package ch.rhosys.sbb.ui.map

import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.rhosys.sbb.domain.model.MapStop
import ch.rhosys.sbb.domain.model.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.hypot

// The camera centre can't leave Switzerland (with a little slack around it), and zooming out
// stops once roughly the whole country fits on a phone screen. MapLibre zoom levels are
// based on 512 px tiles, one level "further out" than classic 256 px raster zooms.
private val SWITZERLAND = LatLngBounds.from(47.95, 10.65, 45.70, 5.80)
private val SWITZERLAND_CENTER = LatLng(46.80, 8.23)
private const val MIN_ZOOM = 6.5
private const val MAX_ZOOM = 19.0
private const val OVERVIEW_ZOOM = 7.0
// "Almost all the way in": street level, where every bus stop is already shown.
private const val START_ZOOM = 16.0
private const val TAP_RADIUS_DP = 24f
private const val RECENTER_ANIMATION_MS = 600

/**
 * Full-screen OpenStreetMap picker (MapLibre, OpenFreeMap vector tiles): shows the stops
 * around the user (only train stations when zoomed out; trams, buses, ferries as they zoom
 * in), their current position, and returns the name of the stop they pick. The base map's
 * own features (roads, labels, buildings, …) can be switched on and off from the layers menu.
 */
@Composable
fun StopMapPicker(
    onStopChosen: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: StopMapViewModel = hiltViewModel(),
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        StopMapContent(viewModel = viewModel, onStopChosen = onStopChosen, onDismiss = onDismiss)
    }
}

@Composable
private fun StopMapContent(
    viewModel: StopMapViewModel,
    onStopChosen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val stops by viewModel.stops.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val hiddenLayers by viewModel.hiddenLayers.collectAsState()
    var selected by remember { mutableStateOf<MapStop?>(null) }
    var zoom by remember { mutableDoubleStateOf(START_ZOOM) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var centeredOnUser by remember { mutableStateOf(userLocation != null) }
    var layersMenuOpen by remember { mutableStateOf(false) }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        // TextureView rendering so the Compose controls layered on top composite cleanly.
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        MapView(context, options).apply {
            onCreate(null)
            getMapAsync { m ->
                configureMap(m, density, viewModel.userLocation.value)
                m.addOnMapClickListener { point ->
                    val hit = stopNear(m, point, TAP_RADIUS_DP * density)
                    if (hit != null) selected = hit
                    hit != null
                }
                m.addOnCameraIdleListener {
                    val camera = m.cameraPosition
                    zoom = camera.zoom
                    camera.target?.let { viewModel.onViewportSettled(it.latitude, it.longitude) }
                }
                m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                    addStopLayers(style, density)
                    loadedStyle = style
                }
                map = m
            }
        }
    }

    LaunchedEffect(loadedStyle, stops) {
        val style = loadedStyle ?: return@LaunchedEffect
        val collection = withContext(Dispatchers.Default) { stopsCollection(stops) }
        style.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(collection)
    }
    LaunchedEffect(loadedStyle, selected) {
        val source = loadedStyle?.getSourceAs<GeoJsonSource>(SELECTED_SOURCE) ?: return@LaunchedEffect
        val stop = selected
        if (stop == null) source.setGeoJson(emptyCollection()) else source.setGeoJson(stop.toFeature())
    }
    LaunchedEffect(loadedStyle, hiddenLayers) {
        loadedStyle?.let { applyBaseLayerVisibility(it, hiddenLayers) }
    }
    LaunchedEffect(map, loadedStyle, userLocation) {
        val location = userLocation
        loadedStyle?.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE)?.let { source ->
            if (location == null) {
                source.setGeoJson(emptyCollection())
            } else {
                source.setGeoJson(Point.fromLngLat(location.second, location.first))
            }
        }
        val m = map
        if (m != null && location != null && !centeredOnUser) {
            // First fix since opening — jump from the country overview to street level.
            centeredOnUser = true
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.first, location.second), START_ZOOM))
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        mapView.onStart()
        mapView.onResume()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 56.dp),
        ) {
            FilledTonalIconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = if (zoom < TransportMode.BUS.minZoom()) "Zoom in for trams and buses" else "Tap a stop to choose it",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            FilledTonalIconButton(onClick = { layersMenuOpen = true }) {
                Icon(Icons.Default.Layers, contentDescription = "Map layers")
            }
            DropdownMenu(expanded = layersMenuOpen, onDismissRequest = { layersMenuOpen = false }) {
                for (group in MapLayerGroup.entries) {
                    val visible = group !in hiddenLayers
                    DropdownMenuItem(
                        text = { Text(group.label) },
                        leadingIcon = { Checkbox(checked = visible, onCheckedChange = null) },
                        onClick = { viewModel.setLayerVisible(group, !visible) },
                    )
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FloatingActionButton(
                    onClick = {
                        val location = userLocation ?: return@FloatingActionButton
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(location.first, location.second), START_ZOOM),
                            RECENTER_ANIMATION_MS,
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Show my location")
                }
            }
            Spacer(Modifier.height(12.dp))
            SelectedStopCard(stop = selected, onChoose = { selected?.let { onStopChosen(it.name) } })
        }
    }
}

// Limits, gestures and the placement of MapLibre's own logo/attribution/compass — moved to
// the top, below the back/layers controls, so the bottom card never covers them.
private fun configureMap(map: MapLibreMap, density: Float, startLocation: Pair<Double, Double>?) {
    map.setMinZoomPreference(MIN_ZOOM)
    map.setMaxZoomPreference(MAX_ZOOM)
    map.setLatLngBoundsForCameraTarget(SWITZERLAND)
    val edge = (12 * density).toInt()
    val belowControls = (68 * density).toInt()
    map.uiSettings.apply {
        setTiltGesturesEnabled(false)
        setLogoGravity(Gravity.TOP or Gravity.START)
        setLogoMargins(edge, belowControls, 0, 0)
        setAttributionGravity(Gravity.TOP or Gravity.END)
        setAttributionMargins(0, belowControls, edge, 0)
        setCompassGravity(Gravity.TOP or Gravity.END)
        setCompassMargins(0, belowControls + (40 * density).toInt(), edge, 0)
    }
    val camera = if (startLocation != null) {
        CameraUpdateFactory.newLatLngZoom(LatLng(startLocation.first, startLocation.second), START_ZOOM)
    } else {
        CameraUpdateFactory.newLatLngZoom(SWITZERLAND_CENTER, OVERVIEW_ZOOM)
    }
    map.moveCamera(camera)
}

// The nearest stop MapLibre actually drew within the tap radius — labels it dropped for
// lack of space aren't on screen, so they're never picked by accident.
private fun stopNear(map: MapLibreMap, point: LatLng, radiusPx: Float): MapStop? {
    val tap = map.projection.toScreenLocation(point)
    val box = RectF(tap.x - radiusPx, tap.y - radiusPx, tap.x + radiusPx, tap.y + radiusPx)
    return map.queryRenderedFeatures(box, *STOP_LAYER_IDS)
        .mapNotNull { it.toMapStop() }
        .map { stop ->
            val screen = map.projection.toScreenLocation(LatLng(stop.lat, stop.lng))
            stop to hypot(screen.x - tap.x, screen.y - tap.y)
        }
        .filter { (_, distance) -> distance <= radiusPx }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

private fun Feature.toMapStop(): MapStop? {
    val name = getStringProperty(PROP_NAME) ?: return null
    val mode = TransportMode.entries.firstOrNull { it.name == getStringProperty(PROP_MODE) } ?: return null
    val point = geometry() as? Point ?: return null
    return MapStop(name, lat = point.latitude(), lng = point.longitude(), mode = mode)
}

private fun emptyCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

@Composable
private fun SelectedStopCard(stop: MapStop?, onChoose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stop == null) {
                Text(
                    "Pick a stop on the map",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(
                    modifier = Modifier.size(40.dp).background(Color(stop.mode.color()), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(stop.mode.icon(), contentDescription = null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stop.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stop.mode.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onChoose) { Text("Choose") }
            }
        }
    }
}

private fun TransportMode.icon(): ImageVector = when (this) {
    TransportMode.TRAIN -> Icons.Default.Train
    TransportMode.TRAM -> Icons.Default.Tram
    TransportMode.BUS -> Icons.Default.DirectionsBus
    TransportMode.FERRY -> Icons.Default.DirectionsBoat
    TransportMode.CABLEWAY, TransportMode.OTHER -> Icons.Default.Place
}

private fun TransportMode.label(): String = when (this) {
    TransportMode.TRAIN -> "Train station"
    TransportMode.TRAM -> "Tram stop"
    TransportMode.BUS -> "Bus stop"
    TransportMode.FERRY -> "Ferry pier"
    TransportMode.CABLEWAY -> "Cable car / funicular"
    TransportMode.OTHER -> "Stop"
}
