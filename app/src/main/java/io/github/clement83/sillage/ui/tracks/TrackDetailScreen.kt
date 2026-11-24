package io.github.clement83.sillage.ui.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.geo.TrackShape
import io.github.clement83.sillage.importer.TrackGeometry
import io.github.clement83.sillage.map.MapLibreView
import io.github.clement83.sillage.map.MapStyles
import io.github.clement83.sillage.map.TrackLayers
import io.github.clement83.sillage.map.awaitMap
import io.github.clement83.sillage.map.awaitStyle
import io.github.clement83.sillage.map.rememberMapView
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import io.github.clement83.sillage.ui.components.ElevationProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.Style

private const val TRACK_SOURCE = "planned-track"
private const val WAYPOINTS_SOURCE = "planned-waypoints"

@Composable
fun TrackDetailScreen(trackId: Long, onBack: () -> Unit, onFollow: () -> Unit) {
    val container = appContainer()
    val dao = container.database.tracks()
    val recorder = container.recorder
    val scope = rememberCoroutineScope()
    val flow = remember(trackId) { dao.observe(trackId) }
    val track by flow.collectAsStateWithLifecycle(initialValue = null)
    val recorderState by recorder.state.collectAsStateWithLifecycle()
    var geometry by remember { mutableStateOf<TrackGeometry?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(trackId) {
        flow.first { it == null }
        onBack()
    }
    LaunchedEffect(trackId) { geometry = container.tracks.geometry(trackId) }
    val profile = remember(geometry) { geometry?.let { TrackShape.profile(it.segments) }.orEmpty() }

    val mapView = rememberMapView()
    var style by remember { mutableStateOf<Style?>(null) }
    val waypointColor = MaterialTheme.colorScheme.secondary.toArgb()
    val paddingPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    LaunchedEffect(mapView) {
        val m = mapView.awaitMap()
        m.uiSettings.isRotateGesturesEnabled = false
        val s = m.awaitStyle(MapStyles.OPEN_TOPO_MAP)
        TrackLayers.ensureGradedLine(s, TRACK_SOURCE, width = 5f)
        TrackLayers.ensurePoints(s, WAYPOINTS_SOURCE, waypointColor)
        style = s
    }
    LaunchedEffect(style, geometry) {
        val s = style ?: return@LaunchedEffect
        val g = geometry ?: return@LaunchedEffect
        TrackLayers.setGradedChunks(s, TRACK_SOURCE, TrackShape.gradeChunks(g.segments))
        TrackLayers.setPoints(s, WAYPOINTS_SOURCE, g.waypoints.map { it.lat to it.lon })
        TrackLayers.fitPoints(mapView.awaitMap(), g.segments, paddingPx)
    }

    val followed = recorderState.isActive && recorderState.followedTrackId == trackId

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                track?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.track_delete_title))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapLibreView(mapView, Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            track?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(Format.distance(it.distanceM), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.stat_climb) + " " + Format.elevation(it.elevationGainM),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (profile.size >= 2) {
                ElevationProfile(profile, markerDistanceM = null, modifier = Modifier.fillMaxWidth().height(72.dp))
            } else if (geometry != null) {
                Text(
                    stringResource(R.string.profile_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (followed) {
                    OutlinedButton(onClick = { recorder.follow(null) }) { Text(stringResource(R.string.track_stop_following)) }
                } else {
                    Button(onClick = {
                        if (recorderState.isActive) recorder.follow(trackId) else recorder.start(followTrackId = trackId)
                        onFollow()
                    }) {
                        Icon(Icons.Filled.Navigation, contentDescription = null)
                        Text(stringResource(R.string.track_follow), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(track?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.key_moment_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    name.trim().takeIf { it.isNotEmpty() }?.let { scope.launch { dao.rename(trackId, it) } }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.track_delete_title)) },
            text = { Text(stringResource(R.string.track_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (followed) recorder.follow(null)
                    scope.launch { dao.delete(trackId) }
                }) { Text(stringResource(R.string.recording_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
