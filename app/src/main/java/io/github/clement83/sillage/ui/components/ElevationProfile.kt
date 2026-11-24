package io.github.clement83.sillage.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.clement83.sillage.geo.ProfileSample
import io.github.clement83.sillage.ui.Format

/** Elevation over distance, with an optional marker at [markerDistanceM] (where I am). */
@Composable
fun ElevationProfile(samples: List<ProfileSample>, markerDistanceM: Double?, modifier: Modifier = Modifier) {
    if (samples.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val marker = MaterialTheme.colorScheme.secondary
    val minAlt = samples.minOf { it.alt }
    val maxAlt = samples.maxOf { it.alt }
    val total = samples.last().distanceM.coerceAtLeast(1.0)
    val span = (maxAlt - minAlt).coerceAtLeast(20.0)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            fun x(d: Double) = (d / total * size.width).toFloat()
            fun y(a: Double) = (size.height - (a - minAlt) / span * size.height * 0.9 - size.height * 0.05).toFloat()
            val path = Path().apply {
                moveTo(x(samples[0].distanceM), y(samples[0].alt))
                samples.drop(1).forEach { lineTo(x(it.distanceM), y(it.alt)) }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(samples.last().distanceM), size.height)
                lineTo(x(samples[0].distanceM), size.height)
                close()
            }
            drawPath(area, fill)
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
            markerDistanceM?.let { d ->
                val mx = x(d.coerceIn(0.0, total))
                val alt = samples.minByOrNull { kotlin.math.abs(it.distanceM - d) }!!.alt
                drawLine(marker, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(marker, radius = 5.dp.toPx(), center = Offset(mx, y(alt)))
            }
        }
        Text(
            "${Format.elevation(maxAlt)} ↑",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
        )
        Text(
            Format.distance(total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
        )
    }
}
