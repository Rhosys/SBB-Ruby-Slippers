package ch.rhosys.sbb.ui.map

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

// Panning stops at the Swiss border (with a little slack around it), and zooming out
// stops once roughly the whole country fits on a phone screen.
private val SWITZERLAND = BoundingBox(47.95, 10.65, 45.70, 5.80)
private val SWITZERLAND_CENTER = GeoPoint(46.80, 8.23)
private const val MIN_ZOOM = 7.5
private const val MAX_ZOOM = 19.0
// "Almost all the way in": street level, where every bus stop is already shown.
private const val START_ZOOM = 17.0

/**
 * Full-screen OpenStreetMap picker: shows the stops around the user (only train
 * stations when zoomed out; trams, buses, ferries as they zoom in), their current
 * position, and returns the name of the stop they pick.
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
    var selected by remember { mutableStateOf<MapStop?>(null) }
    var zoom by remember { mutableDoubleStateOf(START_ZOOM) }
    var centeredOnUser by remember { mutableStateOf(userLocation != null) }

    val stopsOverlay = remember { StopsOverlay(density) { selected = it } }
    val locationOverlay = remember { UserLocationOverlay(density) }
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitDouble(SWITZERLAND)
            setMinZoomLevel(MIN_ZOOM)
            setMaxZoomLevel(MAX_ZOOM)
            val start = userLocation
            if (start != null) {
                controller.setZoom(START_ZOOM)
                controller.setCenter(GeoPoint(start.first, start.second))
            } else {
                controller.setZoom(MIN_ZOOM + 0.5)
                controller.setCenter(SWITZERLAND_CENTER)
            }
            overlays.add(stopsOverlay)
            overlays.add(locationOverlay)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    onViewportChanged()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    onViewportChanged()
                    return false
                }

                private fun onViewportChanged() {
                    zoom = zoomLevelDouble
                    viewModel.onViewportSettled(mapCenter.latitude, mapCenter.longitude)
                }
            })
        }
    }
    LaunchedEffect(stops) {
        stopsOverlay.stops = stops
        mapView.invalidate()
    }
    LaunchedEffect(selected) {
        stopsOverlay.selected = selected
        mapView.invalidate()
    }
    LaunchedEffect(userLocation) {
        val location = userLocation
        locationOverlay.location = location
        if (location != null && !centeredOnUser) {
            // First fix since opening — jump from the country overview to street level.
            centeredOnUser = true
            mapView.controller.setZoom(START_ZOOM)
            mapView.controller.setCenter(GeoPoint(location.first, location.second))
        }
        mapView.invalidate()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        text = if (zoom < 14.0) "Zoom in for trams and buses" else "Tap a stop to choose it",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FloatingActionButton(
                    onClick = {
                        userLocation?.let { (lat, lng) ->
                            mapView.controller.animateTo(GeoPoint(lat, lng), START_ZOOM, 600L)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Show my location")
                }
            }
            Spacer(Modifier.padding(6.dp))
            SelectedStopCard(stop = selected, onChoose = { selected?.let { onStopChosen(it.name) } })
        }
    }
}

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

// osmdroid needs an identifying user agent (OSM tile policy) and, on scoped storage,
// a tile cache inside the app's own cache dir — which Auto Backup never includes.
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.userAgentValue = context.packageName
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
}
