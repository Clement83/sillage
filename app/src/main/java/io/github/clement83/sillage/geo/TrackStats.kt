package io.github.clement83.sillage.geo

/**
 * Running distance and elevation gain of a track, fed fix by fix. The live recording and a track
 * reloaded from the database go through the same code, so both always agree.
 *
 * - Distance only grows once the rider is [minStepM] away from the last counted point, so GPS
 *   jitter while standing still adds nothing.
 * - Elevation gain uses a hysteresis of [climbThresholdM]: altitude noise smaller than that is ignored.
 * - Nothing is counted across segments (pause, interruption).
 */
class TrackStats(
    private val minStepM: Double = 3.0,
    private val climbThresholdM: Double = 5.0,
) {
    var distanceM = 0.0
        private set
    var elevationGainM = 0.0
        private set

    private var anchor: Fix? = null
    private var elevationRef: Double? = null

    fun add(fix: Fix, newSegment: Boolean = false) {
        if (newSegment) {
            anchor = null
            elevationRef = null
        }
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
        fix.alt?.let { alt ->
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
}
