package app.clairix.nav

import androidx.compose.ui.res.stringResource
import app.clairix.ui.theme.MapPalette
import app.clairix.ui.components.*
import app.clairix.ui.theme.ClairixTheme
import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.location.Location
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clairix.BuildConfig
import app.clairix.R
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val M_PER_MILE = 1609.344
private const val FT_PER_M = 3.28084
private const val MPH_PER_MPS = 2.236936
private const val MPH_PER_KPH = 0.621371
private const val LITERS_PER_GALLON = 3.785411784
/** Over the posted limit by more than this, the speed readout turns red. */
private const val SPEEDING_MARGIN_MPH = 5
/** An incident this close ahead gets the "Still there?" prompt. */
private const val INCIDENT_PROMPT_M = 300.0
/** The SOS button floats over the Drive tab's bottom-right corner; panels keep clear of it. */
private val SOS_CLEARANCE = 72.dp

// Map rendering follows road conventions, not the app theme: see MapPalette.
private val TRAFFIC_FREE = MapPalette.TrafficFree
private val TRAFFIC_SLOW = MapPalette.TrafficSlow
private val TRAFFIC_HEAVY = MapPalette.TrafficHeavy
private val TRAFFIC_STOPPED = MapPalette.TrafficStopped
private val TRAFFIC_UNKNOWN = MapPalette.TrafficUnknown
private val ROUTE_ALT = MapPalette.RouteAlternative
private val ROUTE_CASING = MapPalette.RouteCasing
private val WORK_ZONE_LINE = MapPalette.WorkZone
private val CLOSURE_LINE = MapPalette.Closure
/** A known plate reader closer than this on the route ahead is named in the guidance banner. */
private const val CAMERA_BANNER_M = 1_500.0
/** Incident types that cover a stretch of road and are drawn along it. */
private val LINE_TYPES = setOf(
    Incident.CLOSURE, Incident.LANE_CLOSURE, Incident.WORK_ZONE, Incident.DETOUR, Incident.RESTRICTION,
)

private fun levelColor(level: TrafficLevel): Color = when (level) {
    TrafficLevel.FREE -> TRAFFIC_FREE
    TrafficLevel.SLOW -> TRAFFIC_SLOW
    TrafficLevel.HEAVY -> TRAFFIC_HEAVY
    TrafficLevel.STOPPED -> TRAFFIC_STOPPED
    TrafficLevel.UNKNOWN -> TRAFFIC_UNKNOWN
}

@DrawableRes
private fun incidentIcon(type: String): Int = when (type) {
    Incident.POLICE -> R.drawable.ic_incident_police
    Incident.CRASH -> R.drawable.ic_incident_crash
    Incident.CLOSURE, Incident.LANE_CLOSURE -> R.drawable.ic_incident_closure
    Incident.JAM -> R.drawable.ic_incident_jam
    else -> R.drawable.ic_incident_hazard
}

// ── Formatting (US units) ──────────────────────────────────────────────

private fun fmtDistance(m: Double): String {
    val ft = m * FT_PER_M
    if (ft < 1000) return "${maxOf(0, (ft / 50).roundToInt() * 50)} ft"
    val mi = m / M_PER_MILE
    return if (mi < 10) String.format(Locale.US, "%.1f mi", mi) else "${mi.roundToInt()} mi"
}

private fun fmtDuration(sec: Double): String {
    val min = maxOf(0, (sec / 60).roundToInt())
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

private fun fmtClock(context: Context, epochMs: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(epochMs))

/** An end time: the clock time when it is today, else date and time. */
private fun fmtUntil(context: Context, epochSec: Long): String {
    val ms = epochSec * 1000
    val today = android.text.format.DateUtils.isToday(ms)
    return if (today) fmtClock(context, ms)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(ms))
}

private fun sourceLabel(inc: Incident): String = when (inc.origin) {
    Incident.Origin.RELAY -> "Driver reports"
    Incident.Origin.PROVIDER -> when (inc.source) {
        "here" -> "HERE"
        "tomtom" -> "TomTom"
        else -> inc.source
    }
    Incident.Origin.WORK_ZONE -> inc.source
}

/** What a tap on an incident shows: description, road, source and when it ends. */
private fun incidentSnippet(context: Context, inc: Incident, now: Long): String = buildString {
    fun part(text: String) { if (isNotEmpty()) append(" · "); append(text) }
    inc.description?.let { part(it) }
    inc.road?.takeIf { r -> inc.description?.contains(r, ignoreCase = true) != true }?.let { part(it) }
    part(sourceLabel(inc))
    if (inc.origin == Incident.Origin.RELAY) {
        part(if (inc.confirmations == 1) "1 report" else "${inc.confirmations} reports")
        if (inc.createdAt > 0) part(fmtDuration((now - inc.createdAt).toDouble()) + " ago")
    }
    inc.delaySec?.takeIf { it >= 60 }?.let { part("delay ${fmtDuration(it.toDouble())}") }
    if (inc.expiresAt > 0) part("until ${fmtUntil(context, inc.expiresAt)}")
}

private fun limitMph(kph: Int): Int = ((kph * MPH_PER_KPH) / 5).roundToInt() * 5

private fun dp(v: Float): Float = v * Resources.getSystem().displayMetrics.density

// ── Screen ─────────────────────────────────────────────────────────────

/** Turn-by-turn navigation: offline routing on a downloaded state map, with crowd-sourced traffic. */
@Composable
fun NavScreen(modifier: Modifier = Modifier, vm: NavViewModel = viewModel()) {
    val graphState by vm.graph.collectAsStateWithLifecycle()
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionResult()
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(graphState is GraphState.Ready, hasPermission) {
        if (graphState is GraphState.Ready && !hasPermission && !askedPermission) {
            askedPermission = true
            requestPermission()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setLocationActive(true)
                Lifecycle.Event.ON_PAUSE -> vm.setLocationActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setLocationActive(false)
        }
    }

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val g = graphState) {
            is GraphState.Ready -> NavMapLayer(
                vm = vm,
                regionName = g.name,
                graph = g.graph,
                hasPermission = hasPermission,
                onRequestPermission = requestPermission,
                onShowRegions = { showRegions = true },
            )
            else -> RegionSetup(vm = vm, onClose = null)
        }
        if (showRegions && graphState is GraphState.Ready) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RegionSetup(vm = vm, onClose = { showRegions = false })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 120.dp))
    }
}

@Composable
private fun NavMapLayer(
    vm: NavViewModel,
    regionName: String,
    graph: RoadGraph,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onShowRegions: () -> Unit,
) {
    val navState by vm.navState.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val previewRuns by vm.previewRuns.collectAsStateWithLifecycle()
    val navRuns by vm.navRuns.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val fuelPrice by vm.fuelPricePerGal.collectAsStateWithLifecycle()
    val answered by vm.answeredIncidents.collectAsStateWithLifecycle()
    val knownCameras by vm.knownCameras.collectAsStateWithLifecycle()
    val avoidCameras by vm.avoidCameras.collectAsStateWithLifecycle()
    val warnCameras by vm.warnCameras.collectAsStateWithLifecycle()
    val shareTraffic by vm.shareTraffic.collectAsStateWithLifecycle()
    val cameraStatus by vm.cameraStatus.collectAsStateWithLifecycle()
    val workZoneStatus by vm.workZoneStatus.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val lowBattery by vm.lowBattery.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    // A spoofed or jammed receiver makes every turn instruction wrong; say so over the map.
    val gnss by app.clairix.Registry.gnss.collectAsStateWithLifecycle()

    val nav = navState as? NavState.Navigating
    var following by remember { mutableStateOf(true) }
    var showReport by remember { mutableStateOf(false) }
    var showFuelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(nav != null) { if (nav != null) following = true }

    Box(Modifier.fillMaxSize()) {
        NavMap(
            graph = graph,
            nav = nav,
            location = location,
            options = options,
            previewRuns = previewRuns,
            navRuns = navRuns,
            incidents = incidents,
            knownCameras = knownCameras,
            following = following,
            onUserPan = { following = false },
            onLongPress = { lat, lon -> if (nav == null) vm.pickPoint(lat, lon) },
            onSelectRoute = vm::selectRoute,
        )

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (gnss.verdict) {
                app.clairix.analysis.GnssVerdict.SPOOFED -> app.clairix.ui.components.Notice(
                    app.clairix.ui.components.Tone.Danger,
                    stringResource(R.string.drive_the_satellite_signals_are_not_coming),
                    title = stringResource(R.string.drive_gps_looks_spoofed)
                )
                app.clairix.analysis.GnssVerdict.JAMMED -> app.clairix.ui.components.Notice(
                    app.clairix.ui.components.Tone.Danger,
                    stringResource(R.string.drive_something_is_drowning_out_the_satellites),
                    title = stringResource(R.string.drive_gps_looks_jammed)
                )
                else -> {}
            }
            TilesUnavailableNotice(stringResource(R.string.drive_routes_and_directions_come_from_the))
            when {
                nav != null -> ManeuverBanner(nav)
                navState is NavState.Routing -> StatusCard(stringResource(R.string.drive_finding_your_route), busy = true, onCancel = vm::stopNavigation)
                else -> {
                    // A newer build of the map in use: one line, and the way to it.
                    updates[graph.region]?.let { newer -> MapUpdateBanner(regionName, newer, onShowRegions) }
                    SearchBar(
                        state = search,
                        regionName = regionName,
                        updateAvailable = updates.containsKey(graph.region),
                        onQuery = vm::setQuery,
                        onSubmit = vm::submitSearch,
                        onClear = vm::clearSearch,
                        onShowRegions = onShowRegions,
                    )
                    if (options == null && (search.loading || search.results.isNotEmpty() || search.error != null)) {
                        SearchResults(search, location, vm::chooseDestination, vm::searchOnline)
                    }
                }
            }
            if (!hasPermission) PermissionCard(onRequestPermission)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val showRecenter = !following && (nav != null || location != null)
            if (showRecenter) {
                FilledTonalButton(
                    onClick = { following = true },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.drive_re_center)) }
            }
            // The data credit the licence asks for: on the map, above whatever
            // panel is open, clear of the SOS button.
            MapAttribution(Modifier.padding(start = 8.dp, bottom = if (nav == null && options == null) 8.dp else 0.dp))
            if (nav != null) {
                val prompt = nav.incidentsAhead.firstOrNull {
                    it.distanceM <= INCIDENT_PROMPT_M && it.incident.votable && it.incident.id !in answered
                }
                if (prompt != null) {
                    IncidentPrompt(
                        ahead = prompt,
                        onVote = { still -> vm.vote(prompt.incident, still) },
                        onDismiss = { vm.dismissIncident(prompt.incident) },
                    )
                }
                NavBottomBar(
                    nav = nav,
                    muted = muted,
                    lowBattery = lowBattery,
                    onMute = { vm.setMuted(!muted) },
                    onReport = { showReport = true },
                    onEnd = vm::stopNavigation,
                )
            } else {
                options?.let { opts ->
                    RouteOptionsSheet(
                        options = opts,
                        fuelPricePerGal = fuelPrice,
                        avoidCameras = avoidCameras,
                        warnCameras = warnCameras,
                        shareTraffic = shareTraffic,
                        cameraStatus = cameraStatus,
                        workZoneStatus = workZoneStatus,
                        voice = voice,
                        onAvoidCameras = vm::setAvoidCameras,
                        onWarnCameras = vm::setWarnCameras,
                        onShareTraffic = vm::setShareTraffic,
                        onProfile = vm::selectProfile,
                        onSelect = vm::selectRoute,
                        onGo = { idx -> vm.selectRoute(idx); vm.startNavigation() },
                        onClose = vm::closeOptions,
                        onEditFuel = { showFuelDialog = true },
                    )
                }
            }
        }
    }

    if (showReport) {
        ReportDialog(
            onReport = { type -> showReport = false; vm.report(type) },
            onDismiss = { showReport = false },
        )
    }
    if (showFuelDialog) {
        FuelPriceDialog(
            current = fuelPrice,
            onSave = { vm.setFuelPrice(it); showFuelDialog = false },
            onDismiss = { showFuelDialog = false },
        )
    }
}

// ── Map ────────────────────────────────────────────────────────────────

/** Same osmdroid setup as the Map tab ([MapTiles]). */
private fun configureNavOsm(context: Context) = MapTiles.configure(context)

private fun LatLon.geo(): GeoPoint = GeoPoint(lat, lon)

private fun followZoom(speedMps: Float?): Double {
    val mph = (speedMps ?: 0f) * MPH_PER_MPS
    return when {
        mph < 30 -> 17.5
        mph < 55 -> 16.8
        else -> 16.0
    }
}

@SuppressLint("ClickableViewAccessibility") // the listener only observes drags; MapView still handles every touch
@Composable
private fun NavMap(
    graph: RoadGraph,
    nav: NavState.Navigating?,
    location: Location?,
    options: RouteOptions?,
    previewRuns: List<TrafficRun>,
    navRuns: List<TrafficRun>,
    incidents: List<Incident>,
    knownCameras: List<KnownCamera>,
    following: Boolean,
    onUserPan: () -> Unit,
    onLongPress: (Double, Double) -> Unit,
    onSelectRoute: (Int) -> Unit,
) {
    val context = LocalContext.current
    val longPress by rememberUpdatedState(onLongPress)
    val userPan by rememberUpdatedState(onUserPan)
    val selectRoute by rememberUpdatedState(onSelectRoute)

    val routeLayer = remember { FolderOverlay() }
    val cameraLayer = remember { FolderOverlay() }
    val incidentLayer = remember { FolderOverlay() }
    val markerLayer = remember { FolderOverlay() }

    val mapView = remember {
        configureNavOsm(context)
        // The signed tile setting's server (OpenStreetMap's until one names
        // another), every tile answer counted for TilesUnavailableNotice.
        MapTiles.newMapView(context).apply {
            setTilesScaledToDpi(true)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isFlingEnabled = true
            setMinZoomLevel(4.0)
            controller.setZoom(9.0)
            controller.setCenter(GeoPoint((graph.minLat + graph.maxLat) / 2, (graph.minLon + graph.maxLon) / 2))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    longPress(p.latitude, p.longitude)
                    return true
                }
            }))
            overlays.add(routeLayer)
            overlays.add(cameraLayer)
            overlays.add(incidentLayer)
            overlays.add(markerLayer)
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f
                var downY = 0f
                override fun onTouch(v: View, ev: MotionEvent): Boolean {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                        MotionEvent.ACTION_POINTER_DOWN -> userPan()
                        MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) userPan()
                    }
                    return false
                }
            })
        }
    }
    FollowTileServer(mapView)

    val positionMarker = remember {
        Marker(mapView).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_nav_position)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isFlat = true
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    val destMarker = remember {
        Marker(mapView).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_nav_destination)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    // Routes: grey tappable alternatives under the selected or driven route, coloured by traffic.
    val previewRoutes = if (nav == null) options?.routes.orEmpty() else emptyList()
    val selectedIdx = options?.selected ?: -1
    LaunchedEffect(nav?.route, navRuns, previewRoutes, selectedIdx, previewRuns) {
        routeLayer.items.clear()
        val casingW = dp(11f)
        val lineW = dp(7f)
        fun addLine(points: List<LatLon>, color: Color, width: Float, onClick: (() -> Unit)?) {
            if (points.size < 2) return
            val line = Polyline().apply {
                outlinePaint.color = color.toArgb()
                outlinePaint.strokeWidth = width
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(points.map { it.geo() })
                setOnClickListener { _, _, _ -> if (onClick != null) { onClick(); true } else false }
            }
            routeLayer.add(line)
        }
        fun addRoute(route: Route, runs: List<TrafficRun>) {
            addLine(route.geometry, ROUTE_CASING, casingW, null)
            if (runs.isEmpty()) addLine(route.geometry, TRAFFIC_UNKNOWN, lineW, null)
            else for (r in runs) addLine(r.points, levelColor(r.level), lineW, null)
        }
        val driving = nav?.route
        if (driving != null) {
            addRoute(driving, navRuns)
        } else {
            previewRoutes.forEachIndexed { i, r ->
                if (i != selectedIdx) {
                    addLine(r.geometry, ROUTE_CASING, casingW, null)
                    addLine(r.geometry, ROUTE_ALT, lineW) { selectRoute(i) }
                }
            }
            previewRoutes.getOrNull(selectedIdx)?.let { addRoute(it, previewRuns) }
        }
        mapView.invalidate()
    }

    // Fit the whole choice of routes on screen when a new set arrives.
    LaunchedEffect(previewRoutes) {
        if (previewRoutes.isEmpty()) return@LaunchedEffect
        val pts = previewRoutes.flatMap { it.geometry }.map { it.geo() }
        if (pts.size < 2) return@LaunchedEffect
        val box = BoundingBox.fromGeoPointsSafe(pts)
        val fit = { mapView.zoomToBoundingBox(box.increaseByScale(1.25f), true, dp(48f).toInt()) }
        if (mapView.isLayoutOccurred) fit() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
    }

    LaunchedEffect(incidents) {
        incidentLayer.items.clear()
        val now = System.currentTimeMillis() / 1000
        val live = incidents.filter { it.expiresAt !in 1 until now }
        fun drawnAsLine(inc: Incident) = (inc.geom?.size ?: 0) >= 2 && inc.type in LINE_TYPES
        // Stretches first so every marker stays on top of them: closures red and dashed, other work orange.
        for (inc in live) {
            if (!drawnAsLine(inc)) continue
            val geom = inc.geom!!
            val closed = inc.type == Incident.CLOSURE
            val line = Polyline(mapView).apply {
                outlinePaint.color = (if (closed) CLOSURE_LINE else WORK_ZONE_LINE).toArgb()
                outlinePaint.strokeWidth = dp(5f)
                outlinePaint.strokeCap = android.graphics.Paint.Cap.BUTT
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                if (closed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(10f), dp(6f)), 0f)
                setPoints(geom.map { it.geo() })
                title = incidentLabel(inc.type)
                snippet = incidentSnippet(context, inc, now)
            }
            incidentLayer.add(line)
        }
        // Work zones drawn as a line are tapped on it; a marker each would bury the map in icons.
        // A point-only zone has no line, so it keeps its marker.
        for (inc in live) {
            if (inc.origin == Incident.Origin.WORK_ZONE && drawnAsLine(inc)) continue
            val m = Marker(mapView).apply {
                position = GeoPoint(inc.lat, inc.lon)
                icon = ContextCompat.getDrawable(context, incidentIcon(inc.type))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = incidentLabel(inc.type)
                snippet = incidentSnippet(context, inc, now)
            }
            incidentLayer.add(m)
        }
        mapView.invalidate()
    }

    // Known plate readers, from the region's signed list; tapping one says what the map knows of it.
    LaunchedEffect(knownCameras) {
        cameraLayer.items.clear()
        for (c in knownCameras) {
            val m = Marker(mapView).apply {
                position = GeoPoint(c.lat, c.lon)
                icon = ContextCompat.getDrawable(context, R.drawable.ic_camera_alpr)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Known plate reader"
                snippet = cameraSnippet(c)
            }
            cameraLayer.add(m)
        }
        mapView.invalidate()
    }

    // Car and destination markers.
    val pos: LatLon? = nav?.position ?: location?.let { LatLon(it.latitude, it.longitude) }
    val bearing: Float? = nav?.bearingDeg ?: location?.takeIf { it.hasBearing() }?.bearing
    val dest: LatLon? = when {
        nav != null -> LatLon(nav.destLat, nav.destLon)
        options != null -> LatLon(options.dest.lat, options.dest.lon)
        else -> null
    }
    LaunchedEffect(pos, bearing, dest) {
        markerLayer.items.clear()
        if (dest != null) {
            destMarker.position = dest.geo()
            markerLayer.add(destMarker)
        }
        if (pos != null) {
            positionMarker.position = pos.geo()
            // Flat markers turn with the map, so a negative rotation keeps the arrow on the heading.
            if (bearing != null) positionMarker.rotation = -bearing
            markerLayer.add(positionMarker)
        }
        mapView.invalidate()
    }

    // Camera: bearing-up follow while navigating, north-up otherwise.
    var centeredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(nav != null) {
        if (nav == null) mapView.setMapOrientation(0f, true)
    }
    LaunchedEffect(pos, bearing, following, nav != null) {
        val p = pos ?: return@LaunchedEffect
        if (nav != null) {
            if (!following) return@LaunchedEffect
            val current = mapView.mapOrientation
            val target = if (bearing != null) ((360f - bearing) % 360f + 360f) % 360f else current
            val clockwise = ((target - current + 360f) % 360f) < 180f
            mapView.controller.animateTo(p.geo(), followZoom(nav.speedMps), 900L, target, clockwise)
        } else if (following && options == null) {
            if (!centeredOnce) {
                mapView.controller.setZoom(16.0)
                mapView.controller.setCenter(p.geo())
                centeredOnce = true
            } else {
                mapView.controller.animateTo(p.geo())
            }
        }
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            routeLayer.items.clear()
            incidentLayer.items.clear()
            markerLayer.items.clear()
            mapView.overlays.removeAll { it !is org.osmdroid.views.overlay.TilesOverlay }
            mapView.onDetach()
        }
    }

    // Decoded tiles held in memory are for nobody once the screen is hidden; they come back
    // from the tile cache on disk when the map is seen again ([NavMemory]).
    DisposableEffect(mapView) {
        val app = context.applicationContext
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (NavMemory.plan(level, navigating = false).tiles) mapView.tileProvider.clearTileCache()
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = mapView.tileProvider.clearTileCache()
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** "Newer Texas map available (built 31 Oct 2026, 202 MB)" over the Drive tab's map, with Update. */
@Composable
private fun MapUpdateBanner(regionName: String, newer: PublishedGraph, onShowRegions: () -> Unit) {
    Surface(shape = ClairixShapes.Card, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                MapDownloads.updateLine(regionName, newer.builtAtEpochSec?.let(::fmtDate), fmtBytes(newer.compressedBytes)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            GhostButton(stringResource(R.string.drive_update), onClick = onShowRegions)
        }
    }
}

// ── Search ─────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    state: SearchState,
    regionName: String,
    updateAvailable: Boolean,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onShowRegions: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Surface(shape = ClairixShapes.Card, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.drive_search_for_a_place_or_address)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit() }),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.query.isNotEmpty()) {
                            GhostButton(stringResource(R.string.comms_clear), onClick = { focus.clearFocus(); onClear() })
                        }
                        GhostButton(stringResource(R.string.drive_go), onClick = { focus.clearFocus(); onSubmit() }, enabled = state.query.isNotBlank())
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.drive_long_press_the_map_to_drop),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A newer build of this map has been published: say so where the maps are picked.
                GhostButton(if (updateAvailable) stringResource(R.string.drive_map_update, regionName) else "$regionName map", onClick = onShowRegions)
            }
        }
    }
}

@Composable
private fun SearchResults(state: SearchState, here: Location?, onPick: (Place) -> Unit, onOnline: () -> Unit) {
    Surface(shape = ClairixShapes.Card, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
            }
            if (state.source == SearchSource.OFFLINE && !state.loading) {
                // The offline search sent nothing anywhere; going online is the owner's call, said plainly.
                Row(
                    Modifier.fillMaxWidth().clickable { onOnline() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.drive_search_online_instead), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.drive_sends_what_you_typed_and_your),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.results) { place ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(place) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                place.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (here != null) {
                            val d = RoadGeo.haversineM(here.latitude, here.longitude, place.lat, place.lon)
                            Text(fmtDistance(d), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (state.source == SearchSource.ONLINE && state.results.isNotEmpty()) {
                // Nominatim's usage policy asks for the data credit wherever its results are shown.
                val context = LocalContext.current
                Text(
                    stringResource(R.string.drive_results_openstreetmap_contributors_nominatim),
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(R.string.drive_open_the_map_data_licence)) { openLink(context, TileConfig.OSM_COPYRIGHT_URL) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Notice(
        Tone.Caution,
        stringResource(R.string.drive_navigation_needs_your_precise_location_to),
        action = { PrimaryButton(stringResource(R.string.drive_allow_location), onClick = onRequest) }
    )
}

@Composable
private fun StatusCard(text: String, busy: Boolean, onCancel: (() -> Unit)?) {
    Surface(shape = ClairixShapes.Card, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(text, Modifier.weight(1f))
            if (onCancel != null) GhostButton(stringResource(R.string.settings_cancel), onClick = onCancel)
        }
    }
}

// ── Route options ──────────────────────────────────────────────────────

private val PROFILE_CHIPS = listOf(
    RouteProfile.FASTEST, RouteProfile.NO_TOLLS, RouteProfile.SHORTEST, RouteProfile.ECO, RouteProfile.NO_HIGHWAYS,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteOptionsSheet(
    options: RouteOptions,
    fuelPricePerGal: Double,
    avoidCameras: Boolean,
    warnCameras: Boolean,
    shareTraffic: Boolean,
    cameraStatus: CameraFeed.Status,
    workZoneStatus: WorkZoneFeed.Status,
    voice: VoiceSource,
    onAvoidCameras: (Boolean) -> Unit,
    onWarnCameras: (Boolean) -> Unit,
    onShareTraffic: (Boolean) -> Unit,
    onProfile: (RouteProfile) -> Unit,
    onSelect: (Int) -> Unit,
    onGo: (Int) -> Unit,
    onClose: () -> Unit,
    onEditFuel: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = ClairixShapes.Sheet,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(options.dest.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        options.dest.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GhostButton(stringResource(R.string.comms_close), onClick = onClose)
            }
            if (options.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            options.error?.let {
                Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            }
            if (options.routes.isNotEmpty()) {
                val selectedProfile = options.selectedRoute?.profile
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (p in PROFILE_CHIPS) {
                        FilterChip(
                            selected = p == selectedProfile,
                            onClick = { onProfile(p) },
                            label = { Text(profileLabel(p)) },
                        )
                    }
                }
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.routes.forEachIndexed { i, r ->
                        RouteCard(
                            route = r,
                            selected = i == options.selected,
                            fuelPricePerGal = fuelPricePerGal,
                            cameras = options.camerasOn(i),
                            cameraStatus = cameraStatus,
                            onSelect = { onSelect(i) },
                            onGo = { onGo(i) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, end = SOS_CLEARANCE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    String.format(Locale.US, stringResource(R.string.drive_fuel_at_2f_gal), fuelPricePerGal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GhostButton(stringResource(R.string.settings_change), onClick = onEditFuel)
            }
            CameraToggleRow(
                title = stringResource(R.string.drive_route_around_known_plate_readers),
                checked = avoidCameras,
                onChange = onAvoidCameras,
            )
            CameraToggleRow(
                title = stringResource(R.string.drive_say_when_one_is_ahead),
                checked = warnCameras,
                onChange = onWarnCameras,
            )
            Text(
                when (val st = cameraStatus) {
                    is CameraFeed.Status.Loaded -> stringResource(R.string.drive_plate_readers_known_in_this_map, st.count)
                    CameraFeed.Status.NotPublished -> stringResource(R.string.drive_no_plate_reader_list_has_been)
                    CameraFeed.Status.Unknown -> stringResource(R.string.drive_the_plate_reader_list_for_this)
                },
                Modifier.padding(top = 4.dp, end = SOS_CLEARANCE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The work zones the routes above take into account, and how old that list is: the
            // feed is rebuilt a few times a day, not on a fixed clock, so the age is said.
            Text(
                WorkZoneFeed.statusText(workZoneStatus, System.currentTimeMillis() / 1000),
                Modifier.padding(top = 4.dp, end = SOS_CLEARANCE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What leaves the phone for traffic, said where a drive is started (gap audit item 8).
            CameraToggleRow(
                title = stringResource(R.string.drive_share_traffic_data),
                checked = shareTraffic,
                onChange = onShareTraffic,
            )
            Text(
                if (shareTraffic) SHARE_TRAFFIC_ON else SHARE_TRAFFIC_OFF,
                Modifier.padding(top = 2.dp, end = SOS_CLEARANCE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What a drive costs, said where it is started.
            Text(
                stringResource(R.string.drive_navigating_keeps_the_gps_on_once, DrivePower.CUTOFF_PCT),
                Modifier.padding(top = 4.dp, end = SOS_CLEARANCE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (voice == VoiceSource.NETWORK) {
                Notice(
                    Tone.Caution,
                    stringResource(R.string.drive_this_phone_has_no_offline_voice),
                    Modifier.padding(top = 8.dp, end = SOS_CLEARANCE),
                    title = stringResource(R.string.drive_spoken_directions_may_leave_the_phone),
                    action = {
                        GhostButton(stringResource(R.string.drive_speech_settings), onClick = {
                            runCatching {
                                context.startActivity(Intent(TTS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        })
                    }
                )
            }
        }
    }
}

/** What "Share traffic data" sends, with Comms set up; the website's egress table says the same. */
internal const val SHARE_TRAFFIC_ON =
    "With Comms set up, while you navigate, anonymous speed samples (a road segment, its map position, a speed and a time; never " +
        "your GPS track) go to the Clairix relay, which can read them but is not told who sent them. While you " +
        "navigate or look at routes, the box around the route, at most about 55 km across, goes through the relay " +
        "to HERE and TomTom for incidents; they see the relay, never your phone."

/** What still goes with "Share traffic data" off. */
internal const val SHARE_TRAFFIC_OFF =
    "Off: no speed samples and no route box for HERE and TomTom are sent. With Comms set up, live speeds for this " +
        "map region, typical speeds for the roads of a route, and incidents you report yourself still go through the relay."

/** Android's text-to-speech settings, where an offline voice is installed. */
private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

@Composable
private fun CameraToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, end = SOS_CLEARANCE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** What the map knows about a reader, for its marker. */
private fun cameraSnippet(c: KnownCamera): String = buildString {
    append(c.summary)
    c.directionDeg?.let { append(" · faces ").append(compassName(it)) }
    c.zone?.let { append(" · watches ").append(it) }
    append(" · mapped in OpenStreetMap")
}

private fun compassName(deg: Int): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((deg % 360) + 360) % 360 + 22) / 45 % 8]
}

@Composable
private fun RouteCard(
    route: Route,
    selected: Boolean,
    fuelPricePerGal: Double,
    cameras: Int?,
    cameraStatus: CameraFeed.Status,
    onSelect: () -> Unit,
    onGo: () -> Unit,
) {
    val context = LocalContext.current
    val delayMin = ((route.durationSec - route.freeFlowDurationSec) / 60).roundToInt()
    val gallons = route.fuelLiters / LITERS_PER_GALLON
    val cost = gallons * fuelPricePerGal
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        onClick = onSelect,
        shape = ClairixShapes.Card,
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtDuration(route.durationSec), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "arrive ${fmtClock(context, System.currentTimeMillis() + (route.durationSec * 1000).toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Text(
                    buildString {
                        append(fmtDistance(route.distanceM))
                        append(" · ")
                        append(profileLabel(route.profile))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (delayMin >= 1) stringResource(R.string.drive_min_traffic, delayMin) else stringResource(R.string.drive_no_traffic_delay),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        delayMin >= 10 -> TRAFFIC_HEAVY
                        delayMin >= 3 -> TRAFFIC_SLOW
                        else -> TRAFFIC_FREE
                    }
                )
                Text(
                    buildString {
                        if (route.tollM >= 50) append(stringResource(R.string.drive_tolls_for)).append(fmtDistance(route.tollM)) else append(stringResource(R.string.drive_no_tolls))
                        append(" · ")
                        append(String.format(Locale.US, stringResource(R.string.drive_2f_gal_1f_l_2f_fuel), gallons, route.fuelLiters, cost))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cameraStatus is CameraFeed.Status.Loaded) {
                    Text(
                        when {
                            cameras == null -> stringResource(R.string.drive_plate_readers_not_counted_yet)
                            cameras == 0 -> stringResource(R.string.drive_passes_no_known_plate_reader)
                            cameras == 1 -> stringResource(R.string.drive_passes_1_known_plate_reader)
                            else -> stringResource(R.string.drive_passes_known_plate_readers, cameras)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cameras != null && cameras > 0) ClairixTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(stringResource(R.string.drive_go), onClick = onGo, tone = Tone.Safe, modifier = Modifier.heightIn(min = 56.dp))
        }
    }
}

@Composable
private fun FuelPriceDialog(current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", current)) }
    val value = text.trim().removePrefix("$").toDoubleOrNull()
    val valid = value != null && value > 0 && value <= 50
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ClairixShapes.Sheet,
        containerColor = ClairixTheme.colors.surfaceRaised,
        title = { Text(stringResource(R.string.drive_fuel_price)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.drive_dollars_per_gallon)) },
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { GhostButton(stringResource(R.string.settings_save), onClick = { if (valid) onSave(value!!) }, enabled = valid) },
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss) },
    )
}

// ── Driving ────────────────────────────────────────────────────────────

@Composable
private fun ManeuverBanner(nav: NavState.Navigating) {
    val m = nav.nextManeuver
    Surface(
        Modifier.fillMaxWidth(),
        shape = ClairixShapes.Hero,
        color = MapPalette.GuidanceSign,
        contentColor = MapPalette.GuidanceSignInk,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(m?.type ?: ManeuverType.STRAIGHT, Modifier.size(64.dp), MapPalette.GuidanceSignInk)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (m != null) fmtDistance(nav.distanceToManeuverM) else fmtDistance(nav.remainingDistanceM),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        m?.instruction ?: stringResource(R.string.drive_continue_to_your_destination),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val camera = nav.camerasAhead.firstOrNull()?.takeIf { it.distanceM <= CAMERA_BANNER_M }
            when {
                nav.rerouting -> BannerNote(stringResource(R.string.drive_rerouting))
                nav.offRoute -> BannerNote(stringResource(R.string.drive_off_route))
                camera != null -> BannerNote(stringResource(R.string.drive_plate_reader_in, fmtDistance(camera.distanceM)))
                nav.currentStreet != null -> BannerNote(stringResource(R.string.drive_on, nav.currentStreet))
            }
        }
    }
}

@Composable
private fun BannerNote(text: String) {
    Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MapPalette.GuidanceSignInk.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun NavBottomBar(
    nav: NavState.Navigating,
    muted: Boolean,
    lowBattery: Boolean,
    onMute: () -> Unit,
    onReport: () -> Unit,
    onEnd: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = ClairixShapes.Sheet,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmtClock(context, nav.etaEpochMs), style = MaterialTheme.typography.headlineLarge, color = ClairixTheme.colors.safe)
                    Text(
                        "${fmtDuration(nav.remainingSec)} · ${fmtDistance(nav.remainingDistanceM)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                SpeedReadout(nav.speedMps, nav.speedLimitKph)
            }
            if (lowBattery) {
                Text(
                    stringResource(R.string.drive_battery_low_traffic_and_reports_every),
                    Modifier.padding(top = 4.dp, end = SOS_CLEARANCE),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.caution,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = SOS_CLEARANCE),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(stringResource(R.string.safety_end), onClick = onEnd, tone = Tone.Danger)
                SecondaryButton(if (muted) stringResource(R.string.comms_unmute) else stringResource(R.string.comms_mute), onClick = onMute)
                SecondaryButton(stringResource(R.string.comms_report), onClick = onReport, tone = Tone.Accent)
            }
        }
    }
}

@Composable
private fun SpeedReadout(speedMps: Float?, limitKph: Int?) {
    val mph = speedMps?.let { (it * MPH_PER_MPS).roundToInt() }
    val limit = limitKph?.let { limitMph(it) }
    val speeding = mph != null && limit != null && mph > limit + SPEEDING_MARGIN_MPH
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (speeding) ClairixTheme.colors.danger else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    mph?.toString() ?: "–",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (speeding) ClairixTheme.colors.onDanger else MaterialTheme.colorScheme.onSurface
                )
                Text("mph", style = MaterialTheme.typography.bodySmall, color = if (speeding) ClairixTheme.colors.onDanger else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (limit != null) {
            // US-style regulatory sign: black on white, rectangular.
            Column(
                Modifier
                    .width(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MapPalette.SpeedSignFace)
                    .border(2.dp, MapPalette.SpeedSignInk, RoundedCornerShape(6.dp))
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("SPEED\nLIMIT", style = MaterialTheme.typography.labelSmall, color = MapPalette.SpeedSignInk, textAlign = TextAlign.Center)
                Text("$limit", style = MaterialTheme.typography.headlineMedium, color = MapPalette.SpeedSignInk)
            }
        }
    }
}

@Composable
private fun IncidentPrompt(ahead: IncidentAhead, onVote: (Boolean) -> Unit, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = ClairixShapes.Card,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(incidentIcon(ahead.incident.type)), contentDescription = null, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${incidentLabel(ahead.incident.type)} in ${fmtDistance(ahead.distanceM)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(R.string.drive_still_there), style = MaterialTheme.typography.bodyMedium)
            }
            GhostButton(stringResource(R.string.scan_yes), onClick = { onVote(true) })
            GhostButton(stringResource(R.string.drive_no), onClick = { onVote(false) })
            GhostButton(stringResource(R.string.shell_skip), onClick = onDismiss)
        }
    }
}

@Composable
private fun ReportDialog(onReport: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ClairixShapes.Sheet,
        containerColor = ClairixTheme.colors.surfaceRaised,
        title = { Text(stringResource(R.string.comms_report)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in Incident.TYPES.chunked(3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (type in row) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onReport(type) }
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Image(painterResource(incidentIcon(type)), contentDescription = null, Modifier.size(44.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(incidentLabel(type), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    stringResource(R.string.drive_reports_go_to_your_relay_with),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss) },
    )
}

// ── Maneuver icons ─────────────────────────────────────────────────────

/** Unit vector for a heading [deg] clockwise from straight up, in screen coordinates. */
private fun dir(deg: Float): Offset {
    val r = deg * PI.toFloat() / 180f
    return Offset(sin(r), -cos(r))
}

/** Draws a stroked polyline through [pts] ending in a filled arrowhead at the last point. */
private fun DrawScope.arrow(pts: List<Offset>, color: Color, width: Float, head: Float) {
    if (pts.size < 2) return
    val tip = pts.last()
    val prev = pts[pts.size - 2]
    val len = hypot(tip.x - prev.x, tip.y - prev.y).coerceAtLeast(0.001f)
    val d = Offset((tip.x - prev.x) / len, (tip.y - prev.y) / len)
    val n = Offset(-d.y, d.x)
    val base = tip - d * head
    val shaft = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) lineTo(pts[i].x, pts[i].y)
        // The shaft stops inside the head so its round cap does not poke out of the tip.
        val end = tip - d * (head * 0.6f)
        lineTo(end.x, end.y)
    }
    drawPath(shaft, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val h = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + n.x * head * 0.75f, base.y + n.y * head * 0.75f)
        lineTo(base.x - n.x * head * 0.75f, base.y - n.y * head * 0.75f)
        close()
    }
    drawPath(h, color)
}

@Composable
private fun ManeuverIcon(type: ManeuverType, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val head = w * 0.2f
        val faded = color.copy(alpha = 0.35f)
        val cx = w / 2
        val bottom = Offset(cx, h * 0.95f)
        val junction = Offset(cx, h * 0.52f)
        fun turn(deg: Float, length: Float = w * 0.38f) {
            arrow(listOf(bottom, junction, junction + dir(deg) * length), color, sw, head)
        }
        fun fork(ours: Float, other: Float) {
            val l = w * 0.4f
            drawLine(faded, junction, junction + dir(other) * l, strokeWidth = sw, cap = StrokeCap.Round)
            arrow(listOf(bottom, junction, junction + dir(ours) * l), color, sw, head)
        }
        when (type) {
            ManeuverType.STRAIGHT -> arrow(listOf(bottom, Offset(cx, h * 0.05f)), color, sw, head)
            ManeuverType.SLIGHT_LEFT -> turn(-40f)
            ManeuverType.LEFT -> turn(-90f)
            ManeuverType.SHARP_LEFT -> turn(-140f, w * 0.34f)
            ManeuverType.SLIGHT_RIGHT -> turn(40f)
            ManeuverType.RIGHT -> turn(90f)
            ManeuverType.SHARP_RIGHT -> turn(140f, w * 0.34f)
            ManeuverType.KEEP_LEFT -> fork(-28f, 28f)
            ManeuverType.KEEP_RIGHT -> fork(28f, -28f)
            ManeuverType.RAMP_LEFT -> {
                drawLine(faded, bottom, Offset(cx, h * 0.05f), strokeWidth = sw, cap = StrokeCap.Round)
                arrow(listOf(bottom, junction, junction + dir(-35f) * (w * 0.42f)), color, sw, head)
            }
            ManeuverType.RAMP_RIGHT -> {
                drawLine(faded, bottom, Offset(cx, h * 0.05f), strokeWidth = sw, cap = StrokeCap.Round)
                arrow(listOf(bottom, junction, junction + dir(35f) * (w * 0.42f)), color, sw, head)
            }
            ManeuverType.MERGE -> {
                val main = w * 0.64f
                drawLine(faded, Offset(main, h * 0.95f), Offset(main, h * 0.05f), strokeWidth = sw, cap = StrokeCap.Round)
                arrow(
                    listOf(Offset(w * 0.22f, h * 0.95f), Offset(w * 0.22f, h * 0.7f), Offset(main - sw, h * 0.38f), Offset(main - sw, h * 0.08f)),
                    color, sw, head,
                )
            }
            ManeuverType.UTURN -> {
                // Driving on the right, a U-turn swings left: up the right side, over, down the left.
                val right = w * 0.66f
                val left = w * 0.3f
                val r = (right - left) / 2
                val top = h * 0.32f
                val p = Path().apply {
                    moveTo(right, h * 0.95f)
                    lineTo(right, top)
                    arcTo(androidx.compose.ui.geometry.Rect(left, top - r, right, top + r), 0f, -180f, false)
                    lineTo(left, h * 0.5f)
                }
                drawPath(p, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                arrow(listOf(Offset(left, h * 0.5f), Offset(left, h * 0.8f)), color, sw, head)
            }
            ManeuverType.ROUNDABOUT -> {
                val r = w * 0.18f
                val c = Offset(cx, h * 0.5f)
                drawCircle(color, radius = r, center = c, style = Stroke(width = sw))
                drawLine(color, bottom, Offset(cx, c.y + r), strokeWidth = sw, cap = StrokeCap.Round)
                val exit = c + dir(45f) * r
                arrow(listOf(exit, exit + dir(45f) * (w * 0.3f)), color, sw, head)
            }
            ManeuverType.DEPART -> {
                drawCircle(color, radius = w * 0.09f, center = Offset(cx, h * 0.85f))
                arrow(listOf(Offset(cx, h * 0.72f), Offset(cx, h * 0.08f)), color, sw, head)
            }
            ManeuverType.ARRIVE -> {
                drawLine(color, bottom, Offset(cx, h * 0.55f), strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.2f, center = Offset(cx, h * 0.33f))
                drawCircle(MapPalette.GuidanceSign, radius = w * 0.08f, center = Offset(cx, h * 0.33f))
            }
        }
    }
}

// ── Region setup ───────────────────────────────────────────────────────

@Composable
private fun RegionSetup(vm: NavViewModel, onClose: (() -> Unit)?) {
    val graphState by vm.graph.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    val prompt by vm.downloadPrompt.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<InstalledRegion?>(null) }

    val currentSlug = when (val g = graphState) {
        is GraphState.Ready -> g.slug
        is GraphState.Loading -> g.slug
        is GraphState.Failed -> g.slug
        GraphState.None -> null
    }
    val busy = download is GraphDownload.Downloading || download is GraphDownload.Installing
    val shown = vm.regions.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.drive_offline_road_map), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onClose != null) GhostButton(stringResource(R.string.comms_done), onClick = onClose)
            }
            Text(
                stringResource(R.string.drive_maps_cover_the_united_states_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.drive_the_map_behind_the_route_is),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val g = graphState) {
            is GraphState.Loading -> item { StatusCard(stringResource(R.string.drive_opening_the_map, g.name), busy = true, onCancel = null) }
            is GraphState.Failed -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.drive_the_map_could_not_be_opened, g.name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(g.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            stringResource(R.string.drive_delete_it_and_download_it_again),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            else -> Unit
        }

        item { DownloadStatus(download, vm.regions, onCancel = vm::cancelDownload) }

        if (installed.isNotEmpty()) {
            item { Text(stringResource(R.string.drive_on_this_phone), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(installed, key = { "installed-${it.slug}" }) { r ->
                val newer = updates[r.slug]
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                String.format(Locale.US, stringResource(R.string.drive_0f_mb_built_s), r.sizeBytes / 1_048_576.0, fmtDate(r.builtAtEpochSec)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (newer != null) {
                                Text(
                                    MapDownloads.updateLine(r.name, newer.builtAtEpochSec?.let(::fmtDate), fmtBytes(newer.compressedBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (r.slug == currentSlug && graphState is GraphState.Ready) {
                            Text(stringResource(R.string.drive_in_use), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        } else {
                            GhostButton(stringResource(R.string.drive_use), onClick = { vm.selectRegion(r.slug); onClose?.invoke() })
                        }
                        if (newer != null) {
                            val region = vm.regions.firstOrNull { it.slug == r.slug }
                            if (region != null) GhostButton(stringResource(R.string.drive_update), onClick = { vm.prepareDownload(region) }, enabled = !busy)
                        }
                        GhostButton(stringResource(R.string.settings_delete), onClick = { confirmDelete = r }, enabled = !busy)
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.drive_download_a_state), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.drive_filter_states)) },
            )
        }
        items(shown, key = { "region-${it.slug}" }) { region ->
            val have = installed.any { it.slug == region.slug }
            // Up to date as far as is known; it can still be downloaded again.
            val upToDate = have && region.slug !in updates
            val label = when {
                !have -> stringResource(R.string.drive_download)
                !upToDate -> stringResource(R.string.drive_update)
                else -> stringResource(R.string.drive_on_this_phone)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { vm.prepareDownload(region) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(region.name, Modifier.weight(1f))
                Text(
                    label,
                    color = if (busy || upToDate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item { Spacer(Modifier.height(SOS_CLEARANCE + 16.dp)) }
    }

    prompt?.let { p ->
        DownloadPromptDialog(
            prompt = p,
            onConfirm = vm::confirmDownload,
            onDismiss = vm::dismissDownload,
        )
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = ClairixShapes.Sheet,
            containerColor = ClairixTheme.colors.surfaceRaised,
            title = { Text(stringResource(R.string.drive_delete, r.name)) },
            text = {
                Text(
                    String.format(Locale.US, stringResource(R.string.drive_frees_0f_mb_you_can_download), r.sizeBytes / 1_048_576.0),
                )
            },
            confirmButton = {
                GhostButton(stringResource(R.string.settings_delete), onClick = { vm.deleteRegion(r.slug); confirmDelete = null }, tone = Tone.Danger)
            },
            dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = { confirmDelete = null }) },
        )
    }
}

private fun fmtDate(epochSec: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSec * 1000))

private fun fmtBytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
}

/**
 * What a map download costs, before anything is fetched: the download, the space on the
 * phone, whether there is room, and whether the connection is metered ([MapDownloads]).
 */
@Composable
private fun DownloadPromptDialog(prompt: DownloadPrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = prompt.region.name
    when (prompt) {
        is DownloadPrompt.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            shape = ClairixShapes.Sheet,
            containerColor = ClairixTheme.colors.surfaceRaised,
            title = { Text(name) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.drive_checking_the_maps_size))
                }
            },
            confirmButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss) },
        )
        is DownloadPrompt.Ready -> {
            val plan = prompt.plan
            val published = prompt.published
            val installedAt = prompt.installedBuiltAtSec
            val newer = installedAt != null && MapDownloads.isNewer(installedAt, published?.builtAtEpochSec) == true
            val title = when {
                installedAt == null -> stringResource(R.string.drive_download_2, name)
                newer -> stringResource(R.string.drive_update_2, name)
                else -> stringResource(R.string.drive_download_again, name)
            }
            val lines = buildList {
                if (published != null) {
                    val onPhone = plan.installedBytes?.let { (if (plan.estimated) stringResource(R.string.drive_about) else " · ") + "${fmtBytes(it)} on this phone" }.orEmpty()
                    add(stringResource(R.string.drive_download_3, fmtBytes(published.compressedBytes), onPhone))
                    published.builtAtEpochSec?.let { add(stringResource(R.string.drive_built, fmtDate(it))) }
                } else {
                    add(
                        stringResource(R.string.drive_the_maps_size_could_not_be)
                    )
                }
                if (installedAt != null) {
                    add(stringResource(R.string.drive_the_one_on_this_phone_was, fmtDate(installedAt)))
                }
                val need = plan.requiredFreeBytes
                when {
                    need == null -> add(stringResource(R.string.drive_this_phone_has_free, fmtBytes(plan.freeBytes)))
                    plan.enoughSpace -> add(stringResource(R.string.drive_needs_free_while_it_installs_this, fmtBytes(need), fmtBytes(plan.freeBytes)))
                    else -> add(
                        stringResource(R.string.drive_not_enough_space_it_needs_free, fmtBytes(need), fmtBytes(plan.freeBytes), fmtBytes(plan.missingBytes))
                    )
                }
                if (plan.metered) {
                    add(
                        stringResource(R.string.drive_this_connection_is_metered_mobile_data, (published?.let { fmtBytes(it.compressedBytes) } ?: stringResource(R.string.drive_up_to_about_200_mb)))
                    )
                }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                shape = ClairixShapes.Sheet,
                containerColor = ClairixTheme.colors.surfaceRaised,
                title = { Text(title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                },
                confirmButton = {
                    GhostButton(
                        when {
                            plan.metered -> stringResource(R.string.drive_download_anyway)
                            newer -> stringResource(R.string.drive_update)
                            else -> stringResource(R.string.drive_download)
                        },
                        onClick = onConfirm,
                        enabled = plan.enoughSpace,
                    )
                },
                dismissButton = { GhostButton(stringResource(R.string.comms_not_now), onClick = onDismiss) },
            )
        }
    }
}

@Composable
private fun DownloadStatus(download: GraphDownload, regions: List<MapRegion>, onCancel: () -> Unit) {
    fun name(slug: String) = regions.firstOrNull { it.slug == slug }?.name ?: slug
    when (download) {
        is GraphDownload.Downloading -> Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.drive_downloading, name(download.slug)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    GhostButton(stringResource(R.string.settings_cancel), onClick = onCancel)
                }
                val f = download.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    buildString {
                        append(String.format(Locale.US, stringResource(R.string.drive_1f_mb), download.bytes / 1_048_576.0))
                        download.totalBytes?.let { append(String.format(Locale.US, stringResource(R.string.drive_of_1f_mb), it / 1_048_576.0)) }
                    },
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is GraphDownload.Installing -> StatusCard(stringResource(R.string.drive_installing, name(download.slug)), busy = true, onCancel = null)
        is GraphDownload.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "${name(download.slug)}: ${download.message}",
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        is GraphDownload.Done, GraphDownload.Idle -> Unit
    }
}
