package io.github.clement83.sillage.geo

import kotlin.math.exp

/**
 * Running distance and elevation gain of a track, fed fix by fix. The live recording and a track
 * reloaded from the database go through the same code, so both always agree.
 *
 * - Distance only grows once the rider is [minStepM] away from the last counted point, so GPS
 *   jitter while standing still adds nothing.
 * - Elevation gain uses a hysteresis of [climbThresholdM] on a cleaned altitude. GPS altitude is
 *   noisy (±10 m swings every few seconds) and wild for a while after the receiver starts (tens of
 *   metres a second): counted raw, a flat ride gains hundreds of metres. So the altitude of the first
 *   [warmUpMs] of a segment is ignored, spikes are dropped by a median of the last [medianFixes]
 *   altitudes, and what is left is smoothed with a time constant of [smoothingMs].
 *   [raw] turns all of this off, for tracks whose altitude is already clean (imported, planned).
 * - Nothing is counted across segments (pause, interruption).
 */
class TrackStats(
    private val minStepM: Double = 3.0,
    private val climbThresholdM: Double = 5.0,
    private val warmUpMs: Long = 30_000,
    private val medianFixes: Int = 9,
    private val smoothingMs: Long = 30_000,
) {
    var distanceM = 0.0
        private set
    var elevationGainM = 0.0
        private set

    private var anchor: Fix? = null
    private var elevationRef: Double? = null
    private var segmentStart: Long? = null
    private val recentAlts = ArrayDeque<Double>()
    private var smoothedAlt: Double? = null
    private var smoothedAt = 0L

    fun add(fix: Fix, newSegment: Boolean = false) {
        if (newSegment) {
            anchor = null
            elevationRef = null
            segmentStart = null
            recentAlts.clear()
            smoothedAlt = null
        }
        if (segmentStart == null) segmentStart = fix.time
        val a = anchor
        if (a == null) {
            anchor = fix
        } else {
            val step = GeoMath.distanceM(a, fix)
            if (step >= minStepM) {
                distanceM += step
                anchor = fix
            }
        }
        fix.alt?.let { raw -> cleanAltitude(fix.time, raw) }?.let { alt ->
            val ref = elevationRef
            when {
                ref == null -> elevationRef = alt
                alt > ref + climbThresholdM -> {
                    elevationGainM += alt - ref
                    elevationRef = alt
                }
                alt < ref - climbThresholdM -> elevationRef = alt
            }
        }
    }

    /** The altitude to count, or null while the receiver warms up. */
    private fun cleanAltitude(time: Long, raw: Double): Double? {
        if (time - (segmentStart ?: time) < warmUpMs) return null
        recentAlts.addLast(raw)
        while (recentAlts.size > medianFixes) recentAlts.removeFirst()
        val median = median(recentAlts)
        val previous = smoothedAlt
        val smoothed = if (previous == null || smoothingMs <= 0) {
            median
        } else {
            val dt = (time - smoothedAt).coerceAtLeast(0)
            previous + (median - previous) * (1 - exp(-dt.toDouble() / smoothingMs))
        }
        smoothedAlt = smoothed
        smoothedAt = time
        return smoothed
    }

    companion object {
        /** For altitudes that are already clean (imported or planned tracks): plain hysteresis. */
        fun raw(minStepM: Double = 3.0) = TrackStats(minStepM = minStepM, warmUpMs = 0, medianFixes = 1, smoothingMs = 0)

        private fun median(values: Collection<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
