package io.github.clement83.sillage.ui.outings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.singletrack.ApiResult
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import kotlinx.coroutines.launch

/** Length of singletrack's pairing codes (#1720). */
private const val CODE_LENGTH = 6

/**
 * Pairs the app with a singletrack account (#1728), from the "Pair the app" QR code ([qrServer] and
 * [qrCode], paired straight away) or a code typed by hand. Once paired: which server, since when, unpair.
 */
@Composable
fun AccountScreen(qrServer: String?, qrCode: String?, onBack: () -> Unit, onScan: () -> Unit, onPaired: () -> Unit) {
    val container = appContainer()
    val repository = container.outings
    val account by container.account.account.collectAsStateWithLifecycle(initialValue = null)
    val settingsServer by container.settings.serverUrl.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    val deviceName = remember { listOf(Build.MANUFACTURER, Build.MODEL).distinct().joinToString(" ").trim() }

    fun pair(server: String, value: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val r = repository.pair(server, value, deviceName)
            busy = false
            when (r) {
                is ApiResult.Ok -> {
                    // The paired server is the app's server too (Réglages show it).
                    container.settings.setServerUrl(server)
                    onPaired()
                }
                ApiResult.Unauthorized, ApiResult.NotFound -> error = R.string.account_error_code
                ApiResult.TooManyRequests -> error = R.string.account_error_too_many
                else -> error = R.string.open_offline
            }
        }
    }
    LaunchedEffect(qrServer, qrCode) {
        if (qrServer != null && qrCode != null) {
            code = qrCode
            pair(qrServer, qrCode)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val paired = account
            if (paired != null) {
                Text(
                    stringResource(R.string.account_paired, paired.server, Format.dateTime(paired.pairedAt)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = { confirmUnpair = true }) { Text(stringResource(R.string.account_unpair)) }
            } else {
                val server = qrServer ?: settingsServer
                Text(stringResource(R.string.account_how), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (server != null) stringResource(R.string.account_server, server) else stringResource(R.string.account_no_server),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.account_server_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Text(stringResource(R.string.account_scan), Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH) },
                    label = { Text(stringResource(R.string.account_code_label)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (server != null && code.length == CODE_LENGTH) pair(server, code) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { server?.let { pair(it, code) } }, enabled = !busy && server != null && code.length == CODE_LENGTH) {
                        Text(stringResource(R.string.account_pair))
                    }
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(stringResource(R.string.account_pairing))
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.account_unpair)) },
            text = { Text(stringResource(R.string.account_unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch { repository.unpair() }
                }) { Text(stringResource(R.string.account_unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
