package io.github.clement83.sillage.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.filled.Flag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box as MenuAnchor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.KeyMomentEntity
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.export.ExportFile
import io.github.clement83.sillage.export.ExportFiles
import io.github.clement83.sillage.export.ExportFormat
import io.github.clement83.sillage.export.ExportTrack
import io.github.clement83.sillage.export.ExportWaypoint
import io.github.clement83.sillage.export.TrackExport
import io.github.clement83.sillage.geo.Fix
import io.github.clement83.sillage.map.MapLibreView
import io.github.clement83.sillage.map.TrackLayers
import io.github.clement83.sillage.map.awaitMap
import io.github.clement83.sillage.map.awaitStyle
import io.github.clement83.sillage.map.rememberMapView
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import io.github.clement83.sillage.ui.components.TrackStatsRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.Style

private const val TRACK_SOURCE = "detail-track"
private const val MOMENTS_SOURCE = "detail-moments"

@Composable
fun RecordingDetailScreen(recordingId: Long, onBack: () -> Unit) {
    val dao = appContainer().database.recordings()
    val baseStyle = appContainer().offline.baseStyle
    val scope = rememberCoroutineScope()
    val flow = remember(recordingId) { dao.observe(recordingId) }
    val recording by flow.collectAsStateWithLifecycle(initialValue = null)
    var segments by remember { mutableStateOf<List<List<Fix>>>(emptyList()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val momentsFlow = remember(recordingId) { dao.observeKeyMoments(recordingId) }
    val moments by momentsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var renaming by remember { mutableStateOf<KeyMomentEntity?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.export_saved)
    val failedMessage = stringResource(R.string.export_failed)
    val shareTitle = stringResource(R.string.export_share_title)
    val momentsFolder = stringResource(R.string.key_moments_title)
    val defaultMomentName = stringResource(R.string.key_moment_default_name)
    fun exportFile(format: ExportFormat): ExportFile? {
        val rec = recording ?: return null
        val name = rec.title ?: "Sillage ${Format.dateTime(rec.startedAt)}"
        val track = ExportTrack(
            name = name,
            startedAt = rec.startedAt,
            segments = segments,
            waypoints = moments.mapIndexed { i, m ->
                ExportWaypoint(m.time, m.lat, m.lon, m.alt, m.label ?: defaultMomentName.format(i + 1))
            },
        )
        val content = when (format) {
            ExportFormat.GPX -> TrackExport.gpx(track)
            ExportFormat.KML -> TrackExport.kml(track, momentsFolder)
        }
        return ExportFile(TrackExport.fileName(rec.startedAt, rec.title, format.extension), format, content)
    }
    var pendingSave by remember { mutableStateOf<ExportFile?>(null) }
    fun onSaveTarget(uri: android.net.Uri?) {
        val file = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        scope.launch {
            val ok = runCatching { ExportFiles.save(context, file, uri) }.isSuccess
            snackbar.showSnackbar(if (ok) savedMessage else failedMessage)
        }
    }
    val saveGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GPX.mimeType), ::onSaveTarget)
    val saveKml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.KML.mimeType), ::onSaveTarget)
    fun share(format: ExportFormat) {
        val file = exportFile(format) ?: return
        scope.launch { ExportFiles.share(context, file, shareTitle) }
    }
    fun save(format: ExportFormat) {
        val file = exportFile(format) ?: return
        pendingSave = file
        (if (format == ExportFormat.GPX) saveGpx else saveKml).launch(file.name)
    }

    // Deleted (here, or discarded by the recorder): nothing left to show.
    LaunchedEffect(recordingId) {
        flow.first { it == null }
        onBack()
    }

    // Reload when the last batch lands (the screen can open while the recorder is still saving).
    LaunchedEffect(recordingId, recording?.pointCount) {
        segments = dao.points(recordingId).groupBy { it.segment }.toSortedMap().values.map { seg -> seg.map { it.toFix() } }
    }

    val mapView = rememberMapView()
    var style by remember { mutableStateOf<Style?>(null) }
    val trackColor = MaterialTheme.colorScheme.secondary.toArgb()
    val paddingPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    LaunchedEffect(mapView) {
        val m = mapView.awaitMap()
        m.uiSettings.isRotateGesturesEnabled = false
        baseStyle.collect { json ->
            val s = m.awaitStyle(json)
            TrackLayers.ensureLine(s, TRACK_SOURCE, trackColor, width = 4f)
            TrackLayers.ensurePoints(s, MOMENTS_SOURCE, trackColor)
            style = s
        }
    }
    LaunchedEffect(style, moments) {
        val s = style ?: return@LaunchedEffect
        TrackLayers.setPoints(s, MOMENTS_SOURCE, moments.map { it.lat to it.lon })
    }
    LaunchedEffect(style, segments) {
        val s = style ?: return@LaunchedEffect
        TrackLayers.setSegments(s, TRACK_SOURCE, segments)
        TrackLayers.fit(mapView.awaitMap(), segments, paddingPx)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                recording?.let { it.title ?: Format.dateTime(it.startedAt) }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapLibreView(mapView, Modifier.fillMaxSize())
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            recording?.let { TrackStatsRow(it.activeMs, it.distanceM, it.elevationGainM) }
            recording?.let { RideUploadCard(it) }
            if (moments.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.key_moments_title), style = MaterialTheme.typography.titleMedium)
                moments.forEachIndexed { index, moment ->
                    KeyMomentRow(
                        name = moment.label ?: stringResource(R.string.key_moment_default_name, index + 1),
                        time = Format.timeOfDay(moment.time),
                        onRename = { renaming = moment },
                        onDelete = { scope.launch { dao.deleteKeyMoment(moment.id) } },
                    )
                }
                HorizontalDivider()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ExportMenu(enabled = recording != null && segments.isNotEmpty(), onShare = ::share, onSave = ::save)
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text(stringResource(R.string.recording_delete), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    renaming?.let { moment ->
        RenameKeyMomentDialog(
            initial = moment.label.orEmpty(),
            onSave = { name ->
                renaming = null
                scope.launch { dao.renameKeyMoment(moment.id, name.trim().ifEmpty { null }) }
            },
            onDismiss = { renaming = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = { Text(stringResource(R.string.recording_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Leaving the screen is done by the deletion watcher above.
                    scope.launch { dao.delete(recordingId) }
                }) { Text(stringResource(R.string.recording_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun KeyMomentRow(name: String, time: String, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.key_moment_rename))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.key_moment_delete))
        }
    }
}

@Composable
private fun RenameKeyMomentDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_moment_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.key_moment_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ExportMenu(enabled: Boolean, onShare: (ExportFormat) -> Unit, onSave: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    MenuAnchor {
        Button(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Text(stringResource(R.string.export), Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_share_as, format.name)) },
                    onClick = { open = false; onShare(format) },
                )
            }
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_save_as, format.name)) },
                    onClick = { open = false; onSave(format) },
                )
            }
        }
    }
}
