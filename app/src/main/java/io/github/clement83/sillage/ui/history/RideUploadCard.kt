package io.github.clement83.sillage.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.UploadState
import io.github.clement83.sillage.singletrack.RideUploads
import io.github.clement83.sillage.singletrack.SoloOutings
import io.github.clement83.sillage.singletrack.webUrl
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import io.github.clement83.sillage.ui.openInBrowser
import kotlinx.coroutines.launch

/**
 * The ride's singletrack outing and its upload (#1731): queued, sent, failed and why; send, send
 * again, cancel. Nothing is shown for a ride that was not on an outing.
 */
@Composable
fun RideUploadCard(recording: RecordingEntity) {
    val outingId = recording.outingId ?: return
    val container = appContainer()
    val outingFlow = remember(outingId) { container.outings.observe(outingId) }
    val outing by outingFlow.collectAsStateWithLifecycle(initialValue = null)
    val account by container.account.account.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var canSend by remember { mutableStateOf(false) }
    LaunchedEffect(recording, outing, account) { canSend = container.rides.target(recording) != null }
    val row = outing ?: return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.ride_outing, row.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            val state = recording.uploadState
            val status = when (state) {
                UploadState.QUEUED -> stringResource(R.string.ride_queued)
                UploadState.SENT -> stringResource(R.string.ride_sent, Format.dateTime(recording.uploadedAt ?: 0))
                UploadState.CANCELLED -> stringResource(R.string.ride_cancelled)
                UploadState.FAILED -> uploadError(recording.uploadError)
                null -> if (canSend) null else stringResource(R.string.ride_error_no_access)
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == UploadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalArrangement = Arrangement.Center,
            ) {
                if (state == UploadState.SENT) {
                    row.webUrl()?.let { url ->
                        TextButton(onClick = { context.openInBrowser(url) }) { Text(stringResource(R.string.ride_view)) }
                    }
                }
                when {
                    state == UploadState.QUEUED -> TextButton(onClick = { scope.launch { container.rides.cancel(recording.id) } }) {
                        Text(stringResource(R.string.ride_cancel))
                    }
                    canSend -> Button(onClick = { scope.launch { container.rides.send(recording.id) } }) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                        Text(
                            stringResource(if (state == null) R.string.ride_send else R.string.ride_resend),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A ride off any outing, with a paired account (#1766): "Sync with singletrack" (a private solo
 * outing), then its state.
 * Once made, the ride is on that outing and [RideUploadCard] takes over.
 */
@Composable
fun SoloOutingCard(recording: RecordingEntity) {
    if (!SoloOutings.canMake(recording)) return
    val container = appContainer()
    val account by container.account.account.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    // #1777: one of my outings of the day started within the hour of this ride? Then offer to join it.
    val outings by container.outings.outings.collectAsStateWithLifecycle(initialValue = emptyList())
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val server = account?.server
    val state = recording.uploadState
    val unsynced = state == null || state == UploadState.CANCELLED
    LaunchedEffect(recording.id, server, unsynced) {
        if (server != null && unsynced) container.outings.syncAccount() // fresher list if online; the cache otherwise
    }
    val match = remember(outings, recording.startedAt, server) {
        server?.let { SoloOutings.matchingOuting(recording.startedAt, outings, it) }
    }
    if (account == null) return

    if (unsynced && match != null && !dismissed) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.match_question), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.match_title, match.title, Format.timeOfDay(match.startsAt)),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.match_skip)) }
                    TextButton(onClick = { scope.launch { container.rides.makeOuting(recording.id) } }) { Text(stringResource(R.string.match_solo)) }
                    Button(onClick = { scope.launch { container.rides.joinOuting(recording.id, match.id) } }) { Text(stringResource(R.string.match_join)) }
                }
            }
        }
    } else if (unsynced) {
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Flag, contentDescription = null)
            Text(stringResource(R.string.solo_make), Modifier.padding(start = 8.dp))
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (state) {
                        UploadState.QUEUED -> stringResource(R.string.solo_queued)
                        UploadState.SENT -> stringResource(R.string.solo_sent, Format.dateTime(recording.uploadedAt ?: 0))
                        else -> uploadError(recording.uploadError)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == UploadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    when (state) {
                        UploadState.QUEUED -> TextButton(onClick = { scope.launch { container.rides.cancel(recording.id) } }) {
                            Text(stringResource(R.string.ride_cancel))
                        }
                        UploadState.FAILED -> if (recording.uploadError != RideUploads.ERROR_SERVER_TOO_OLD) {
                            Button(onClick = { scope.launch { container.rides.makeOuting(recording.id) } }) { Text(stringResource(R.string.retry)) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.solo_confirm_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { container.rides.makeOuting(recording.id) }
                }) { Text(stringResource(R.string.solo_sync)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun uploadError(code: String?): String = when (code) {
    "tooEarly" -> stringResource(R.string.ride_error_too_early)
    "closed" -> stringResource(R.string.ride_error_closed)
    "tooLarge", "tooBig" -> stringResource(R.string.ride_error_too_large)
    "notGpx", "noTrack", "doctype", "noPoints" -> stringResource(R.string.ride_error_not_gpx)
    RideUploads.ERROR_SERVER_TOO_OLD -> stringResource(R.string.solo_error_server)
    RideUploads.ERROR_NOT_FOUND -> stringResource(R.string.outing_gone)
    RideUploads.ERROR_UNAUTHORIZED -> stringResource(R.string.ride_error_unauthorized)
    RideUploads.ERROR_NO_ACCESS -> stringResource(R.string.ride_error_no_access)
    else -> stringResource(R.string.ride_error_other)
}
