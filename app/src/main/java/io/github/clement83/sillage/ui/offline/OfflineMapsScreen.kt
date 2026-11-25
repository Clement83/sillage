package io.github.clement83.sillage.ui.offline

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.clement83.sillage.data.OfflineZoneEntity
import io.github.clement83.sillage.data.ZoneKind
import io.github.clement83.sillage.data.ZoneState
import io.github.clement83.sillage.offline.CatalogState
import io.github.clement83.sillage.offline.OfflineMaps
import io.github.clement83.sillage.offline.OfflineRegion
import io.github.clement83.sillage.ui.appContainer
import kotlinx.coroutines.launch

/** Offline maps (#1729): what is on the phone, what the server offers, and how to download. */
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val maps = container.offline
    val settings = container.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zones by maps.zones.collectAsStateWithLifecycle(initialValue = emptyList())
    val mobileData by settings.offlineMobileData.collectAsStateWithLifecycle(initialValue = false)
    val offlineFirst by settings.offlineFirst.collectAsStateWithLifecycle(initialValue = false)
    var catalog by remember { mutableStateOf<CatalogState?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var used by remember { mutableLongStateOf(0L) }
    var toDownload by remember { mutableStateOf<OfflineRegion?>(null) }
    var toDownloadRouting by remember { mutableStateOf<OfflineRegion?>(null) }
    var toDelete by remember { mutableStateOf<OfflineZoneEntity?>(null) }

    LaunchedEffect(attempt) {
        catalog = null
        catalog = maps.catalog(force = true)
    }
    LaunchedEffect(zones) { used = maps.sizeOnDisk() }
    val ready = catalog as? CatalogState.Ready

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (zones.isNotEmpty()) {
                Text(stringResource(R.string.offline_on_phone), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.offline_used, Formatter.formatShortFileSize(context, used)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                zones.forEach { zone ->
                    val region = ready?.catalog?.regions?.firstOrNull { it.id == zone.regionId && ready.server == zone.server }
                    ZoneCard(
                        zone = zone,
                        mobileData = mobileData,
                        updateAvailable = zone.state == ZoneState.READY && region != null && region.version != zone.version,
                        onRetry = { scope.launch { maps.retry(zone) } },
                        onUpdate = { scope.launch { maps.update(zone) } },
                        onDelete = { toDelete = zone },
                    )
                }
                HorizontalDivider()
            }

            Text(stringResource(R.string.offline_regions), style = MaterialTheme.typography.titleSmall)
            when (val c = catalog) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                CatalogState.NoAccount -> Text(stringResource(R.string.offline_error_account), style = MaterialTheme.typography.bodyMedium)
                CatalogState.Unreachable -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.offline_unreachable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                }
                is CatalogState.Ready -> {
                    val regions = c.catalog.regions.takeIf { c.catalog.enabled }.orEmpty()
                    if (regions.isEmpty()) {
                        Text(stringResource(R.string.offline_none_offered), style = MaterialTheme.typography.bodyMedium)
                    }
                    regions.forEach { region ->
                        fun has(kind: ZoneKind) = zones.any { it.kind == kind && it.regionId == region.id && it.server == c.server }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(region.name, style = MaterialTheme.typography.bodyLarge)
                            RegionPart(
                                label = stringResource(R.string.offline_map, Formatter.formatShortFileSize(context, region.pmtilesSize)),
                                downloadable = !has(ZoneKind.REGION),
                                onDownload = { toDownload = region },
                            )
                            if (region.segments.isNotEmpty()) {
                                RegionPart(
                                    label = stringResource(R.string.offline_routing, Formatter.formatShortFileSize(context, region.segments.sumOf { it.size })),
                                    downloadable = !has(ZoneKind.ROUTING),
                                    onDownload = { toDownloadRouting = region },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            SwitchRow(stringResource(R.string.offline_mobile_data), mobileData) {
                scope.launch {
                    settings.setOfflineMobileData(it)
                    maps.requeuePending()
                }
            }
            SwitchRow(stringResource(R.string.offline_first), offlineFirst) { scope.launch { settings.setOfflineFirst(it) } }
        }
    }

    toDownload?.let { region ->
        DownloadDialog(
            name = region.name,
            wifiOnly = !mobileData,
            estimate = { maps.estimate(region, null) },
            onConfirm = {
                toDownload = null
                ready?.let { r -> scope.launch { maps.download(r.server, region, null, region.name) } }
            },
            onDismiss = { toDownload = null },
        )
    }
    toDownloadRouting?.let { region ->
        val name = stringResource(R.string.offline_routing, region.name)
        DownloadDialog(
            name = name,
            wifiOnly = !mobileData,
            estimate = { maps.estimateRouting(region) },
            onConfirm = {
                toDownloadRouting = null
                ready?.let { r -> scope.launch { maps.downloadRouting(r.server, region, name) } }
            },
            onDismiss = { toDownloadRouting = null },
        )
    }
    toDelete?.let { zone ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.offline_delete_title, zone.name)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { maps.delete(zone) }
                }) { Text(stringResource(R.string.offline_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One thing a region offers (its map, its routes): size, and Download if not on the phone yet. */
@Composable
private fun RegionPart(label: String, downloadable: Boolean, onDownload: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (downloadable) {
            OutlinedButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Text(stringResource(R.string.offline_download), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun ZoneCard(
    zone: OfflineZoneEntity,
    mobileData: Boolean,
    updateAvailable: Boolean,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(zone.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ZoneStatus(zone, mobileData, updateAvailable)
                when {
                    zone.state == ZoneState.FAILED -> TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    updateAvailable -> TextButton(onClick = onUpdate) { Text(stringResource(R.string.offline_update)) }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.offline_delete))
            }
        }
    }
}

/** One line on a zone: waiting, progress, ready and its size, or why it failed. */
@Composable
fun ZoneStatus(zone: OfflineZoneEntity, mobileData: Boolean, updateAvailable: Boolean = false) {
    val context = LocalContext.current
    val percent = if (zone.totalTiles > 0) zone.doneTiles * 100 / zone.totalTiles else 0
    val (text, error) = when (zone.state) {
        ZoneState.QUEUED -> stringResource(if (mobileData) R.string.offline_state_waiting else R.string.offline_state_waiting_wifi) to false
        ZoneState.DOWNLOADING -> stringResource(R.string.offline_state_downloading, percent) to false
        ZoneState.READY -> (
            if (updateAvailable) stringResource(R.string.offline_update_available)
            else stringResource(R.string.offline_state_ready, Formatter.formatShortFileSize(context, zone.bytes))
            ) to false
        ZoneState.FAILED -> stringResource(
            when (zone.error) {
                OfflineMaps.ERROR_GONE -> R.string.offline_error_gone
                OfflineMaps.ERROR_UNAUTHORIZED, OfflineMaps.ERROR_NO_ACCOUNT -> R.string.offline_error_account
                OfflineMaps.ERROR_NO_ROUTE -> R.string.offline_error_no_route
                else -> R.string.offline_error_other
            },
        ) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (zone.state == ZoneState.DOWNLOADING) {
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
    }
}

/** Confirms a download with its estimated size, measured while the dialog is open. */
@Composable
fun DownloadDialog(name: String, wifiOnly: Boolean, estimate: suspend () -> Long?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var size by remember { mutableStateOf<Long?>(null) }
    var measuring by remember { mutableStateOf(true) }
    LaunchedEffect(name) {
        size = estimate()
        measuring = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_confirm_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    measuring -> CircularProgressIndicator()
                    size != null -> Text(stringResource(R.string.offline_confirm_size, Formatter.formatShortFileSize(context, size!!)))
                }
                if (wifiOnly) Text(stringResource(R.string.offline_confirm_wifi))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.offline_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
