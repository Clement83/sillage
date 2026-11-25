package io.github.clement83.sillage.ui.outings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.geo.ProfileSample
import io.github.clement83.sillage.geo.TrackShape
import io.github.clement83.sillage.map.MapLibreView
import io.github.clement83.sillage.map.TrackLayers
import io.github.clement83.sillage.map.awaitMap
import io.github.clement83.sillage.map.awaitStyle
import io.github.clement83.sillage.map.rememberMapView
import io.github.clement83.sillage.singletrack.Outing
import io.github.clement83.sillage.singletrack.RefreshResult
import io.github.clement83.sillage.singletrack.decode
import io.github.clement83.sillage.singletrack.webUrl
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import io.github.clement83.sillage.ui.offline.OutingOfflineMap
import io.github.clement83.sillage.ui.components.ElevationProfile
import io.github.clement83.sillage.ui.openGeo
import io.github.clement83.sillage.ui.openInBrowser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.Style

private const val ROUTE_SOURCE = "outing-route"
private const val MEETING_SOURCE = "outing-meeting"

/** Re-fetch an outing opened again after this long; sooner, the saved copy is fresh enough. */
private const val REFRESH_AFTER_MS = 60_000L

/** A singletrack outing, read only (#1727): date, meeting point, route, profile; follow it or see it on the website. */
@Composable
fun OutingScreen(outingId: Long, onBack: () -> Unit, onFollow: () -> Unit) {
    val container = appContainer()
    val repository = container.outings
    val recorder = container.recorder
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val flow = remember(outingId) { repository.observe(outingId) }
    val entity by flow.collectAsStateWithLifecycle(initialValue = null)
    val recorderState by recorder.state.collectAsStateWithLifecycle()
    val outing = remember(entity?.json) { entity?.decode() }
    var refresh by remember { mutableStateOf<RefreshResult?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(outingId) {
        flow.first { it == null }
        onBack()
    }
    LaunchedEffect(outingId) {
        val row = flow.first { it != null }!!
        if (!row.hasDetail || System.currentTimeMillis() - row.fetchedAt > REFRESH_AFTER_MS) {
            refresh = repository.refresh(outingId)
        }
    }

    val segments = remember(outing?.track) { outing?.track?.segments().orEmpty() }
    val profile = remember(outing?.track) {
        outing?.track?.profile?.map { ProfileSample(it.distKm * 1000, it.ele) }?.takeIf { it.size >= 2 }
            ?: TrackShape.profile(segments)
    }

    val mapView = rememberMapView()
    var style by remember { mutableStateOf<Style?>(null) }
    val meetingColor = MaterialTheme.colorScheme.secondary.toArgb()
    val paddingPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    LaunchedEffect(mapView) {
        val m = mapView.awaitMap()
        m.uiSettings.isRotateGesturesEnabled = false
        container.offline.baseStyle.collect { json ->
            val s = m.awaitStyle(json)
            TrackLayers.ensureGradedLine(s, ROUTE_SOURCE, width = 5f)
            TrackLayers.ensurePoints(s, MEETING_SOURCE, meetingColor)
            style = s
        }
    }
    LaunchedEffect(style, segments, outing?.meeting) {
        val s = style ?: return@LaunchedEffect
        val meeting = outing?.meeting?.let { m -> if (m.lat != null && m.lon != null) m.lat to m.lon else null }
        TrackLayers.setGradedChunks(s, ROUTE_SOURCE, TrackShape.gradeChunks(segments))
        TrackLayers.setPoints(s, MEETING_SOURCE, listOfNotNull(meeting))
        val framed = segments.ifEmpty { listOfNotNull(meeting?.let { listOf(io.github.clement83.sillage.geo.GeoPoint(it.first, it.second)) }) }
        if (framed.isNotEmpty()) TrackLayers.fitPoints(mapView.awaitMap(), framed, paddingPx)
    }

    val row = entity
    val followed = recorderState.isActive && row?.trackId != null && recorderState.followedTrackId == row.trackId

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                row?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.outing_delete))
            }
        }
        val banner = when {
            outing?.cancelled == true -> stringResource(R.string.outing_cancelled)
            refresh == RefreshResult.NOT_FOUND -> stringResource(R.string.outing_gone)
            refresh == RefreshResult.OFFLINE && row != null -> stringResource(R.string.outing_stale, Format.dateTime(row.fetchedAt))
            else -> null
        }
        banner?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp)) {
            MapLibreView(mapView, Modifier.fillMaxSize())
            if (outing != null && row?.hasDetail == true && segments.isEmpty()) {
                Text(
                    stringResource(R.string.outing_no_route),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface).padding(6.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row != null) Text(Format.dateTime(row.startsAt), style = MaterialTheme.typography.titleSmall)
            outing?.meeting?.let { meeting ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        meeting.label ?: stringResource(R.string.outing_meeting),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (meeting.lat != null && meeting.lon != null) {
                        TextButton(onClick = { context.openGeo(meeting.lat, meeting.lon, meeting.label) }) {
                            Icon(Icons.Outlined.Directions, contentDescription = null)
                            Text(stringResource(R.string.outing_directions), Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
            outing?.let { OutingFacts(it) }
            if (profile.size >= 2) {
                ElevationProfile(profile, markerDistanceM = null, modifier = Modifier.fillMaxWidth().height(56.dp))
            }
            if (row != null && segments.isNotEmpty()) OutingOfflineMap(outingId, row.server, row.title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row?.webUrl(outing)?.let { url ->
                    OutlinedButton(onClick = { context.openInBrowser(url) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.outing_open_web), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (followed) {
                    OutlinedButton(onClick = { recorder.follow(null) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.track_stop_following), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else if (segments.isNotEmpty()) {
                    Button(
                        onClick = {
                            scope.launch {
                                val trackId = repository.routeTrack(outingId) ?: return@launch
                                if (recorderState.isActive) {
                                    recorder.follow(trackId, outingId = outingId)
                                } else {
                                    recorder.start(followTrackId = trackId, outingId = outingId)
                                }
                                onFollow()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Navigation, contentDescription = null)
                        Text(
                            stringResource(R.string.outing_follow),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.outing_delete)) },
            text = { Text(stringResource(R.string.outing_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (followed) recorder.follow(null)
                    scope.launch { repository.delete(outingId) }
                }) { Text(stringResource(R.string.outing_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Distance, climb, level, e-bikes, organiser, my answer, riders going. */
@Composable
private fun OutingFacts(outing: Outing) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val distance = outing.track?.distanceM ?: outing.distanceM
        val climb = outing.track?.elevationGainM ?: outing.elevationGainM
        distance?.let { Text(Format.distance(it), style = MaterialTheme.typography.bodyMedium) }
        climb?.let { Text(stringResource(R.string.stat_climb) + " " + Format.elevation(it), style = MaterialTheme.typography.bodyMedium) }
        levelLabel(outing.level)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        ebikeLabel(outing.ebike)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    }
    val people = buildList {
        if (outing.role == Outing.ROLE_ORGANIZER) {
            add(stringResource(R.string.outing_role_organizer))
        } else {
            outing.organizerName?.let { add(stringResource(R.string.outing_organized_by, it)) }
            answerLabel(outing.myAnswer)?.let { add(stringResource(R.string.outing_my_answer, stringResource(it))) }
        }
        outing.yesCount?.let { add(pluralStringResource(R.plurals.outing_yes_count, it, it)) }
    }
    if (people.isNotEmpty()) {
        Text(
            people.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun levelLabel(level: String?) = when (level) {
    "balade" -> R.string.outing_level_balade
    "sportif" -> R.string.outing_level_sportif
    "enduro" -> R.string.outing_level_enduro
    else -> null
}

private fun ebikeLabel(ebike: String?) = when (ebike) {
    "yes" -> R.string.outing_ebike_yes
    "no" -> R.string.outing_ebike_no
    "only" -> R.string.outing_ebike_only
    else -> null
}

private fun answerLabel(answer: String?) = when (answer) {
    "yes" -> R.string.answer_yes
    "maybe" -> R.string.answer_maybe
    "no" -> R.string.answer_no
    "pending" -> R.string.answer_pending
    else -> null
}
