package io.github.clement83.sillage.ui.map

import androidx.compose.foundation.background
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import io.github.clement83.sillage.recording.RecorderState
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.components.TrackStatsRow
import io.github.clement83.sillage.ui.components.ElevationProfile
import io.github.clement83.sillage.geo.ProfileSample
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.delay

private const val GPS_STALE_MS = 10_000L

/** Live numbers on top of the map while a recording is open. */
@Composable
fun RecordingPanel(
    state: RecorderState,
    modifier: Modifier = Modifier,
    followedProfile: List<ProfileSample> = emptyList(),
    positionOnFollowedM: Double? = null,
) {
    val activeMs by produceState(state.activeMs(), state) {
        while (true) {
            value = state.activeMs()
            delay(1000)
        }
    }
    val now by produceState(System.currentTimeMillis(), state) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.offTrackM?.let { away ->
                Text(
                    stringResource(R.string.off_track, Format.distance(away)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            TrackStatsRow(activeMs, state.distanceM, state.elevationGainM)
            if (followedProfile.size >= 2) {
                ElevationProfile(
                    followedProfile,
                    markerDistanceM = positionOnFollowedM,
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                )
            }
            val message = when {
                state.status == RecordingStatus.PAUSED -> R.string.recording_paused
                !state.locationEnabled -> R.string.recording_location_off
                state.lastFix == null || now - state.lastFix.time > GPS_STALE_MS -> R.string.recording_searching_gps
                else -> null
            }
            if (message != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(message),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (message == R.string.recording_paused) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (message == R.string.recording_location_off) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }) { Text(stringResource(R.string.recording_location_off_action)) }
                    }
                }
            }
        }
    }
}

@Composable
fun StartRecordingButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        icon = { Icon(Icons.Filled.FiberManualRecord, contentDescription = null) },
        text = { Text(stringResource(R.string.recording_start)) },
    )
}

@Composable
fun PauseResumeButton(paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = if (paused) onResume else onPause,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = stringResource(if (paused) R.string.recording_resume else R.string.recording_pause),
        )
    }
}

/** The big one-tap key-moment button: 72 dp, usable with gloves without looking. */
@Composable
fun KeyMomentButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BadgedBox(
        badge = { if (count > 0) Badge { Text(count.toString()) } },
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(Icons.Filled.Flag, contentDescription = stringResource(R.string.key_moment), Modifier.size(32.dp))
        }
    }
}

@Composable
fun StopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.recording_stop))
    }
}

@Composable
fun StopConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recording_stop_title)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.recording_stop_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A recording left open by a killed process or a reboot: carry on, or close it where it stopped. */
@Composable
fun InterruptedRecordingDialog(recording: RecordingEntity, onResume: () -> Unit, onFinish: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recording_interrupted_title)) },
        text = {
            Text(
                stringResource(
                    R.string.recording_interrupted_body,
                    Format.dateTime(recording.startedAt),
                    Format.distance(recording.distanceM),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onResume) { Text(stringResource(R.string.recording_interrupted_resume)) } },
        dismissButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.recording_interrupted_finish)) } },
    )
}
