package io.github.clement83.sillage.ui.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.ZoneState
import io.github.clement83.sillage.offline.CatalogState
import io.github.clement83.sillage.offline.OfflineRegion
import io.github.clement83.sillage.ui.appContainer
import kotlinx.coroutines.launch

/**
 * On an outing (#1729): "Download the map of this ride" when the paired server has a region under
 * its route, then the download's state. Nothing without a paired account on the outing's server.
 */
@Composable
fun OutingOfflineMap(outingId: Long, server: String, title: String) {
    val container = appContainer()
    val maps = container.offline
    val scope = rememberCoroutineScope()
    val account by container.account.account.collectAsStateWithLifecycle(initialValue = null)
    val zoneFlow = remember(outingId) { maps.zoneForOuting(outingId) }
    val zone by zoneFlow.collectAsStateWithLifecycle(initialValue = null)
    val mobileData by container.settings.offlineMobileData.collectAsStateWithLifecycle(initialValue = false)
    var region by remember { mutableStateOf<OfflineRegion?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val paired = account?.server == server
    LaunchedEffect(paired, outingId) {
        region = null
        if (!paired) return@LaunchedEffect
        val c = maps.catalog() as? CatalogState.Ready ?: return@LaunchedEffect
        if (c.catalog.enabled) region = maps.regionFor(c.catalog, outingId)
    }

    val z = zone
    when {
        z != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (z.state == ZoneState.READY) {
                Icon(Icons.Outlined.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.offline_ready_outing), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
            } else {
                Column(Modifier.weight(1f)) { ZoneStatus(z, mobileData) }
                if (z.state == ZoneState.FAILED) TextButton(onClick = { scope.launch { maps.retry(z) } }) { Text(stringResource(R.string.retry)) }
            }
        }
        region != null && paired -> OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Text(stringResource(R.string.offline_download_outing), Modifier.padding(start = 6.dp))
        }
    }

    val r = region
    if (confirm && r != null) {
        DownloadDialog(
            name = title,
            wifiOnly = !mobileData,
            estimate = { maps.estimate(r, outingId) },
            onConfirm = {
                confirm = false
                scope.launch { maps.download(server, r, outingId, title) }
            },
            onDismiss = { confirm = false },
        )
    }
}
