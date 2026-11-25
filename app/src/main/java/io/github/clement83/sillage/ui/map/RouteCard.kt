package io.github.clement83.sillage.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.clement83.sillage.R
import io.github.clement83.sillage.routing.Route
import io.github.clement83.sillage.ui.Format

/** What the map shows for the point tapped (#1733). */
sealed interface RouteUi {
    data object Computing : RouteUi
    data object NoPosition : RouteUi
    data class Found(val route: Route, val straightM: Double) : RouteUi

    /** No way along the paths here: the straight distance. */
    data class Straight(val distanceM: Double) : RouteUi
}

/** The route to the tapped point: distance and climb, follow it, close. */
@Composable
fun RouteCard(state: RouteUi, onFollow: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state == RouteUi.Computing) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    when (state) {
                        RouteUi.Computing -> stringResource(R.string.route_computing)
                        RouteUi.NoPosition -> stringResource(R.string.route_no_position)
                        is RouteUi.Found -> stringResource(
                            R.string.route_found,
                            Format.distance(state.route.distanceM),
                            Format.elevation(state.route.climbM),
                        )
                        is RouteUi.Straight -> stringResource(R.string.route_straight, Format.distance(state.distanceM))
                    },
                    style = if (state is RouteUi.Found || state is RouteUi.Straight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                )
                if (state is RouteUi.Found) {
                    Text(
                        stringResource(R.string.route_straight, Format.distance(state.straightM)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state is RouteUi.Found) TextButton(onClick = onFollow) { Text(stringResource(R.string.track_follow)) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.route_close)) }
        }
    }
}
