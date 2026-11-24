package io.github.clement83.sillage.ui.scan

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.clement83.sillage.R
import io.github.clement83.sillage.singletrack.SingletrackLink

/** Scans a ride QR (`/s/…`, `/i/…`) or the "Pair the app" QR; anything else is politely refused. */
@Composable
fun ScanScreen(onLink: (SingletrackLink) -> Unit, onBack: () -> Unit) {
    val granted = rememberCameraPermission()
    var rejected by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (granted) {
            QrScanner(
                onCode = { text ->
                    val link = SingletrackLink.parse(text)
                    if (link != null) onLink(link) else rejected = true
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.Center).size(240.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
            )
        }
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        when {
                            !granted -> R.string.scan_camera_denied
                            rejected -> R.string.scan_not_singletrack
                            else -> R.string.scan_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}
