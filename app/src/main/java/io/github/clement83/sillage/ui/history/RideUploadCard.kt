package io.github.clement83.sillage.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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

@Composable
private fun uploadError(code: String?): String = when (code) {
    "tooEarly" -> stringResource(R.string.ride_error_too_early)
    "closed" -> stringResource(R.string.ride_error_closed)
    "tooLarge" -> stringResource(R.string.ride_error_too_large)
    "notGpx", "noTrack", "doctype" -> stringResource(R.string.ride_error_not_gpx)
    RideUploads.ERROR_NOT_FOUND -> stringResource(R.string.outing_gone)
    RideUploads.ERROR_UNAUTHORIZED -> stringResource(R.string.ride_error_unauthorized)
    RideUploads.ERROR_NO_ACCESS -> stringResource(R.string.ride_error_no_access)
    else -> stringResource(R.string.ride_error_other, code.orEmpty())
}
