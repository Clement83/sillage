package io.github.clement83.sillage.ui.outings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.OutingEntity
import io.github.clement83.sillage.singletrack.Outing
import io.github.clement83.sillage.singletrack.RefreshResult
import io.github.clement83.sillage.singletrack.decode
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutingsScreen(onOpen: (Long) -> Unit, onScan: () -> Unit, onAccount: () -> Unit) {
    val container = appContainer()
    val repository = container.outings
    val outings by repository.outings.collectAsStateWithLifecycle(initialValue = null)
    val account by container.account.account.collectAsStateWithLifecycle(initialValue = null)
    val revoked by repository.accountRevoked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }

    suspend fun sync() {
        refreshing = true
        offline = repository.syncAccount() == RefreshResult.OFFLINE
        refreshing = false
    }
    val server = account?.server
    LaunchedEffect(server) { if (server != null) sync() }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { if (account != null) sync() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            val list = outings
            val startOfToday = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            val upcoming = list.orEmpty().filter { it.startsAt >= startOfToday }
            val past = list.orEmpty().filter { it.startsAt < startOfToday }.sortedByDescending { it.startsAt }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.outings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onAccount) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = stringResource(R.string.account_title))
                        }
                    }
                }
                item {
                    AccountBanner(
                        paired = account != null,
                        lastSyncAt = account?.lastSyncAt,
                        revoked = revoked,
                        offline = offline,
                        onPair = {
                            repository.consumeRevoked()
                            onAccount()
                        },
                    )
                }
                if (list != null && list.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.outings_empty),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.outings_upcoming)) }
                    items(upcoming, key = { it.id }) { OutingCard(it, onClick = { onOpen(it.id) }) }
                }
                if (past.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.outings_past)) }
                    items(past, key = { it.id }) { OutingCard(it, onClick = { onOpen(it.id) }) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onScan,
            icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
            text = { Text(stringResource(R.string.outings_scan)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun AccountBanner(paired: Boolean, lastSyncAt: Long?, revoked: Boolean, offline: Boolean, onPair: () -> Unit) {
    when {
        !paired -> Card(
            colors = if (revoked) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            } else {
                CardDefaults.cardColors()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp)) {
                Text(
                    stringResource(if (revoked) R.string.outings_account_revoked else R.string.outings_account_unpaired),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                TextButton(onClick = onPair, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.account_pair))
                }
            }
        }
        else -> Text(
            when {
                offline -> stringResource(R.string.outings_offline)
                lastSyncAt != null -> stringResource(R.string.outings_account_synced, Format.dateTime(lastSyncAt))
                else -> stringResource(R.string.outings_account_never)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun OutingCard(entity: OutingEntity, onClick: () -> Unit) {
    val outing = remember(entity.json) { entity.decode() }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entity.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Format.dateTime(entity.startsAt), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                outing?.distanceM?.let { Text(Format.distance(it), style = MaterialTheme.typography.bodyMedium) }
                outing?.elevationGainM?.let {
                    Text(stringResource(R.string.stat_climb) + " " + Format.elevation(it), style = MaterialTheme.typography.bodyMedium)
                }
                if (outing?.role == Outing.ROLE_ORGANIZER) Tag(stringResource(R.string.outing_role_organizer))
                if (outing?.cancelled == true) Tag(stringResource(R.string.outing_cancelled), error = true)
            }
        }
    }
}

@Composable
internal fun Tag(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
    )
}
