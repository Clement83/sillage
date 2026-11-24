package io.github.clement83.sillage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.clement83.sillage.R
import io.github.clement83.sillage.ui.Format

/** Time · distance · climb, the three numbers shown everywhere a track is. */
@Composable
fun TrackStatsRow(activeMs: Long, distanceM: Double, elevationGainM: Double, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat(stringResource(R.string.stat_time), Format.duration(activeMs))
        Stat(stringResource(R.string.stat_distance), Format.distance(distanceM))
        Stat(stringResource(R.string.stat_climb), Format.elevation(elevationGainM))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
