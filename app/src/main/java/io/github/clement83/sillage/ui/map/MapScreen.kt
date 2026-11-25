package io.github.clement83.sillage.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.view.Gravity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.RecordingStatus
import io.github.clement83.sillage.map.MapLibreView
import io.github.clement83.sillage.map.TrackLayers
import io.github.clement83.sillage.map.awaitMap
import io.github.clement83.sillage.map.awaitStyle
import io.github.clement83.sillage.map.rememberMapView
import io.github.clement83.sillage.ui.appContainer
import io.github.clement83.sillage.geo.Fix
import io.github.clement83.sillage.geo.GeoMath
import io.github.clement83.sillage.geo.GeoPoint
import io.github.clement83.sillage.geo.TrackShape
import io.github.clement83.sillage.geo.CleanTrack
import io.github.clement83.sillage.routing.RouteResult
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.importer.TrackGeometry
import io.github.clement83.sillage.ui.rememberUnlockRequest
import io.github.clement83.sillage.ui.permissions.LocationAccess
import io.github.clement83.sillage.ui.permissions.LocationPermissionState
import io.github.clement83.sillage.ui.permissions.rememberLocationPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** OpenTopoMap's most detailed level: trails and contour lines are readable. */
private const val TRACKING_ZOOM = 17.0
private const val RECORDING_SOURCE = "recording"
private const val MOMENTS_SOURCE = "recording-moments"
private const val FOLLOWED_SOURCE = "followed-track"
private const val FOLLOWED_WAYPOINTS_SOURCE = "followed-waypoints"
private const val ROUTE_SOURCE = "route-to-point"
private const val STRAIGHT_SOURCE = "route-straight"
private const val DESTINATION_SOURCE = "route-destination"
private const val TRACK_REDRAW_MS = 1500L

/**
 * The map, the recording controls and the live numbers. In [lockedMode] (shown over the lock screen
 * during a recording) only what is safe without unlocking remains: no stop, no dialogs.
 */
@Composable
fun MapScreen(onRecordingFinished: (Long) -> Unit, lockedMode: Boolean = false) {
    val context = LocalContext.current
    val recorder = appContainer().recorder
    val recorderState by recorder.state.collectAsStateWithLifecycle()
    val openRecording by recorder.openRecording.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    val permission = rememberLocationPermissionState()
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var camera by rememberSaveable { mutableStateOf(listOf(46.6, 2.4, 5.0, 1.0)) }
    var showPermissionCard by rememberSaveable { mutableStateOf(!permission.granted) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val trackColor = MaterialTheme.colorScheme.secondary.toArgb()
    val followedWaypointColor = MaterialTheme.colorScheme.primary.toArgb()
    val tracks = appContainer().tracks
    val baseStyle = appContainer().offline.baseStyle
    var followed by remember { mutableStateOf<TrackGeometry?>(null) }
    val routing = appContainer().routing
    val smoothTrack = appContainer().settings.smoothTrack
    val container = appContainer()
    // The clean track, built fix by fix as they come (#1795, #1796).
    val cleanTrack = remember { CleanTrack(container::trailSnapper) }
    val routeColor = MaterialTheme.colorScheme.tertiary.toArgb()
    // The point tapped on the map and the route to it (#1733).
    var destination by remember { mutableStateOf<GeoPoint?>(null) }
    var route by remember { mutableStateOf<RouteUi?>(null) }
    var pendingFollow by remember { mutableStateOf<Long?>(null) }
    val density = LocalDensity.current
    val attributionBottomPx = with(density) { 88.dp.roundToPx() }
    val attributionEndPx = with(density) { 8.dp.roundToPx() }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        recorder.start(followTrackId = pendingFollow)
        pendingFollow = null
    }
    fun startRecording(followTrackId: Long? = null) {
        when {
            permission.access != LocationAccess.Precise -> showPermissionCard = true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                pendingFollow = followTrackId
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> recorder.start(followTrackId = followTrackId)
        }
        map?.takeIf { it.locationComponent.isLocationComponentActivated }?.followMe()
    }

    LaunchedEffect(mapView) {
        val m = mapView.awaitMap()
        m.cameraPosition = CameraPosition.Builder().target(LatLng(camera[0], camera[1])).zoom(camera[2]).build()
        // The bottom row of buttons would hide the map credits: keep them reachable above it.
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.END
        m.uiSettings.setAttributionMargins(0, 0, attributionEndPx, attributionBottomPx)
        m.addOnCameraIdleListener {
            val c = m.cameraPosition
            // Before the location component is up, keep the previous wish to follow the position.
            val tracking = if (m.locationComponent.isLocationComponentActivated) {
                m.locationComponent.cameraMode == CameraMode.TRACKING
            } else {
                camera[3] == 1.0
            }
            c.target?.let { camera = listOf(it.latitude, it.longitude, c.zoom, if (tracking) 1.0 else 0.0) }
        }
        if (!lockedMode) {
            m.addOnMapClickListener { p ->
                destination = GeoPoint(p.latitude, p.longitude)
                true
            }
        }
        map = m
        // A new base map (zone downloaded or deleted) means a new style: the layers go on again.
        baseStyle.collect { json ->
            val s = m.awaitStyle(json)
            // Order matters: the followed track under the recorded one, markers on top.
            TrackLayers.ensureGradedLine(s, FOLLOWED_SOURCE, width = 6f)
            TrackLayers.ensureLine(s, RECORDING_SOURCE, trackColor, width = 4f)
            TrackLayers.ensurePoints(s, FOLLOWED_WAYPOINTS_SOURCE, followedWaypointColor)
            TrackLayers.ensurePoints(s, MOMENTS_SOURCE, trackColor)
            TrackLayers.ensureDashedLine(s, STRAIGHT_SOURCE, routeColor, width = 2f)
            TrackLayers.ensureLine(s, ROUTE_SOURCE, routeColor, width = 5f)
            TrackLayers.ensurePoints(s, DESTINATION_SOURCE, routeColor)
            style = s
        }
    }

    LaunchedEffect(map, style, permission.granted) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (permission.granted) {
            showPermissionCard = false
            m.enableLocation(context, s, track = camera[3] == 1.0)
        }
    }

    // Redraw the recorded track at most every TRACK_REDRAW_MS: cheap even for a long ride.
    LaunchedEffect(style) {
        val s = style ?: return@LaunchedEffect
        // The clean track (#1795), recomputed from the raw fixes: same result live and after a restart.
        combine(recorder.state.map { it.segments }.distinctUntilChanged(), smoothTrack) { segments, smooth -> segments to smooth }
            .conflate()
            .collect { (segments, smooth) ->
                val shown = if (smooth) withContext(Dispatchers.Default) { cleanTrack.feed(segments) } else segments
                TrackLayers.setSegments(s, RECORDING_SOURCE, shown)
                delay(TRACK_REDRAW_MS)
            }
    }

    // The imported track being followed, coloured by grade (#1742).
    LaunchedEffect(style, recorderState.followedTrackId) {
        val s = style ?: return@LaunchedEffect
        val g = recorderState.followedTrackId?.let { tracks.geometry(it) }
        followed = g
        TrackLayers.setGradedChunks(s, FOLLOWED_SOURCE, g?.let { TrackShape.gradeChunks(it.segments) }.orEmpty())
        TrackLayers.setPoints(s, FOLLOWED_WAYPOINTS_SOURCE, g?.waypoints?.map { it.lat to it.lon }.orEmpty())
    }
    val followedProfile = remember(followed) { followed?.let { TrackShape.profile(it.segments) }.orEmpty() }
    val positionOnFollowed = remember(followed, recorderState.lastFix) {
        val fix = recorderState.lastFix
        if (followed == null || fix == null) null else TrackShape.locate(followed!!.segments, fix.lat, fix.lon)?.first
    }

    // A tap on the map: the way there along the paths, or the straight distance without segments.
    LaunchedEffect(style, destination) {
        val s = style ?: return@LaunchedEffect
        val to = destination
        TrackLayers.setSegments(s, ROUTE_SOURCE, emptyList())
        TrackLayers.setSegments(s, STRAIGHT_SOURCE, emptyList())
        if (to == null) {
            route = null
            TrackLayers.setPoints(s, DESTINATION_SOURCE, emptyList())
            return@LaunchedEffect
        }
        TrackLayers.setPoints(s, DESTINATION_SOURCE, listOf(to.lat to to.lon))
        val from = recorder.state.value.lastFix?.let { GeoPoint(it.lat, it.lon) }
            ?: map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.lastKnownLocation?.let { GeoPoint(it.latitude, it.longitude) }
        if (from == null) {
            route = RouteUi.NoPosition
            return@LaunchedEffect
        }
        // The straight line at once, the way along the paths when found.
        val straightM = GeoMath.distanceM(from.lat, from.lon, to.lat, to.lon)
        TrackLayers.setSegments(s, STRAIGHT_SOURCE, listOf(listOf(Fix(0, from.lat, from.lon), Fix(0, to.lat, to.lon))))
        route = RouteUi.Computing
        when (val r = routing.route(from, to)) {
            is RouteResult.Found -> {
                route = RouteUi.Found(r.route, straightM)
                TrackLayers.setSegments(s, ROUTE_SOURCE, listOf(r.route.points.map { Fix(0, it.lat, it.lon) }))
            }
            else -> route = RouteUi.Straight(straightM)
        }
    }

    LaunchedEffect(style) {
        val s = style ?: return@LaunchedEffect
        recorder.state.map { st -> st.keyMoments.map { it.lat to it.lon } }.distinctUntilChanged().collect {
            TrackLayers.setPoints(s, MOMENTS_SOURCE, it)
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapLibreView(mapView, Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recorderState.isActive) RecordingPanel(recorderState, followedProfile = followedProfile, positionOnFollowedM = positionOnFollowed)
            if (lockedMode) {
                val unlock = rememberUnlockRequest()
                FilledTonalButton(onClick = unlock, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null)
                    Text(stringResource(R.string.lock_unlock), Modifier.padding(start = 8.dp))
                }
            } else if (showPermissionCard && permission.access != LocationAccess.Precise) {
                PermissionCard(permission = permission, onDismiss = { showPermissionCard = false })
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        route?.let { state ->
            RouteCard(
                state = state,
                onFollow = {
                    val found = state as? RouteUi.Found ?: return@RouteCard
                    val to = destination ?: return@RouteCard
                    scope.launch {
                        val name = context.getString(R.string.route_name, Format.dateTime(System.currentTimeMillis()))
                        val trackId = tracks.saveRoute(name, found.route.points, to)
                        if (recorderState.isActive) recorder.follow(trackId) else startRecording(followTrackId = trackId)
                        destination = null
                    }
                },
                onClose = { destination = null },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recorderState.isActive) {
                PauseResumeButton(
                    paused = recorderState.status == RecordingStatus.PAUSED,
                    onPause = recorder::pause,
                    onResume = recorder::resume,
                )
                if (!lockedMode) StopButton(onClick = { confirmStop = true })
                Box(Modifier.weight(1f))
                KeyMomentButton(count = recorderState.keyMomentCount, onClick = recorder::keyMoment)
            } else if (openRecording == null || recorderState.serviceRunning) {
                StartRecordingButton(onClick = ::startRecording)
            }
            Box(Modifier.weight(1f))
            FloatingActionButton(
                onClick = {
                    val m = map
                    if (!permission.granted || m == null) {
                        showPermissionCard = true
                    } else if (m.locationComponent.isLocationComponentActivated) {
                        m.followMe()
                    }
                },
            ) {
                Icon(
                    if (permission.granted) Icons.Filled.MyLocation else Icons.Filled.LocationDisabled,
                    contentDescription = stringResource(R.string.map_recenter),
                )
            }
        }
        }
    }

    if (confirmStop) {
        StopConfirmDialog(
            onConfirm = {
                confirmStop = false
                val id = recorderState.recordingId
                // A recording without a single point is discarded by the service: nothing to show.
                val hasPoints = recorderState.segments.any { it.isNotEmpty() }
                recorder.stop()
                if (id != null && hasPoints) onRecordingFinished(id)
            },
            onDismiss = { confirmStop = false },
        )
    }

    val interrupted = openRecording
    if (interrupted != null && !recorderState.serviceRunning && !lockedMode) {
        InterruptedRecordingDialog(
            recording = interrupted,
            onResume = {
                if (permission.access == LocationAccess.Precise) recorder.resumeInterrupted() else permission.request()
            },
            onFinish = { scope.launch { recorder.finishInterrupted() } },
        )
    }
}

@Composable
private fun PermissionCard(permission: LocationPermissionState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permission_location_title), style = MaterialTheme.typography.titleMedium)
            val body = when {
                permission.access == LocationAccess.Approximate -> R.string.permission_location_coarse_body
                permission.permanentlyDenied -> R.string.permission_location_denied_body
                else -> R.string.permission_location_body
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_dismiss)) }
                Button(onClick = permission::request) {
                    Text(
                        stringResource(
                            if (permission.permanentlyDenied) R.string.permission_open_settings else R.string.permission_allow,
                        ),
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.enableLocation(context: Context, style: Style, track: Boolean) {
    val component = locationComponent
    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(true)
                .locationEngineRequest(
                    LocationEngineRequest.Builder(1000)
                        .setFastestInterval(500)
                        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                        .build(),
                )
                .build(),
        )
    }
    component.isLocationComponentEnabled = true
    component.renderMode = RenderMode.COMPASS
    // Show where we were last seen right away; the first fresh fix can take a while indoors.
    if (component.lastKnownLocation == null) {
        val manager = context.getSystemService(LocationManager::class.java)
        manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { component.forceLocationUpdate(it) }
    }
    if (track) followMe()
}

/** Follow my position, zooming in to at least street level, in one camera transition. */
private fun MapLibreMap.followMe() {
    locationComponent.setCameraMode(
        CameraMode.TRACKING,
        750L,
        maxOf(cameraPosition.zoom, TRACKING_ZOOM),
        null,
        null,
        null,
    )
}
