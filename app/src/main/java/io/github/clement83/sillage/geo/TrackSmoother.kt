package io.github.clement83.sillage.geo

import kotlin.math.max

/**
 * The clean version of a recorded track (#1795), computed from the raw fixes, which stay untouched:
 * fixes less accurate than [maxAccuracyM] are dropped, the others go through a Kalman filter weighted
 * by their accuracy (a few seconds of lag at most), and a point is kept only once [minStepM] away
 * from the previous one, so a stop leaves one point instead of a tangle. Deterministic: the same raw
 * fixes always give the same clean track, live or reloaded after the app was killed.
 */
class TrackSmoother(
    private val maxAccuracyM: Float = 20f,
    private val minStepM: Double = 3.0,
    /** How fast the true position may drift (m/s) when the fix gives no speed, or stands still. */
    private val minDriftMps: Double = 1.0,
) {
    private var lat = 0.0
    private var lon = 0.0
    private var variance = -1.0
    private var time = 0L
    private var lastKept: Fix? = null

    /** Feeds one raw fix; returns the clean point to add to the track, or null. */
    fun add(fix: Fix, newSegment: Boolean = false): Fix? {
        if (newSegment) {
            variance = -1.0
            lastKept = null
        }
        val accuracy = (fix.accuracy ?: DEFAULT_ACCURACY_M).coerceAtLeast(1f).toDouble()
        if (fix.accuracy != null && fix.accuracy > maxAccuracyM) return null
        if (variance < 0) {
            lat = fix.lat
            lon = fix.lon
            variance = accuracy * accuracy
        } else {
            val dt = (fix.time - time).coerceAtLeast(0) / 1000.0
            val drift = max(minDriftMps, fix.speed?.toDouble() ?: 0.0)
            variance += dt * drift * drift
            val gain = variance / (variance + accuracy * accuracy)
            lat += gain * (fix.lat - lat)
            lon += gain * (fix.lon - lon)
            variance *= 1 - gain
        }
        time = fix.time
        val point = fix.copy(lat = lat, lon = lon)
        val previous = lastKept
        if (previous != null && GeoMath.distanceM(previous, point) < minStepM) return null
        lastKept = point
        return point
    }

    companion object {
        /** A fix without accuracy is taken as this good. */
        private const val DEFAULT_ACCURACY_M = 10f

        /** The clean track of [segments] of raw fixes. */
        fun smooth(segments: List<List<Fix>>): List<List<Fix>> {
            val smoother = TrackSmoother()
            return segments.map { segment ->
                segment.mapIndexedNotNull { i, fix -> smoother.add(fix, newSegment = i == 0) }
            }
        }
    }
}
