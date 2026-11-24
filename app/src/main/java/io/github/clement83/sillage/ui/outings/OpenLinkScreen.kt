package io.github.clement83.sillage.ui.outings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.clement83.sillage.R
import io.github.clement83.sillage.singletrack.OpenResult
import io.github.clement83.sillage.singletrack.SingletrackLink
import io.github.clement83.sillage.singletrack.webUrl
import io.github.clement83.sillage.ui.appContainer
import io.github.clement83.sillage.ui.openInBrowser

/** Opens a scanned or tapped outing link: fetch, save, then hand over to the outing screen (#1727). */
@Composable
fun OpenLinkScreen(link: SingletrackLink, onOpened: (Long) -> Unit, onBack: () -> Unit, onAccount: () -> Unit) {
    val repository = appContainer().outings
    val context = LocalContext.current
    var result by remember { mutableStateOf<OpenResult?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(link, attempt) {
        result = null
        val r = repository.open(link)
        if (r is OpenResult.Opened) onOpened(r.outingId) else result = r
    }

    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val r = result) {
                null -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.open_loading))
                }
                else -> {
                    Text(
                        stringResource(
                            when (r) {
                                OpenResult.NotFound -> R.string.open_not_found
                                OpenResult.NeedsAccount -> R.string.open_needs_account
                                OpenResult.JoinOnWebsite -> R.string.open_join
                                else -> R.string.open_offline
                            },
                        ),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        link.webUrl()?.let { url ->
                            OutlinedButton(onClick = { context.openInBrowser(url) }, modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(if (r == OpenResult.JoinOnWebsite) R.string.open_join_action else R.string.outing_open_web),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        when (r) {
                            OpenResult.NeedsAccount -> Button(onClick = onAccount, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.account_pair))
                            }
                            OpenResult.Offline -> Button(onClick = { attempt++ }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.retry))
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
