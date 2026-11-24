package io.github.clement83.sillage.geo

/**
 * Drops the GPS fixes that would spoil a track: too inaccurate, out of order, or implying an
 * impossible speed from the last accepted fix.
 *
 * The speed check is skipped after a long gap, and gives up after a few rejections in a row, so a
 * stale reference point (tunnel, pause, a ride in a car) can never lock the recording out.
 */
class FixFilter(
    private val maxAccuracyM: Float = 30f,
    private val maxSpeedMps: Double = 25.0,
    private val gapMs: Long = 30_000,
    private val maxRejectsInRow: Int = 5,
) {
    private var last: Fix? = null
    private var speedRejects = 0

    fun accept(fix: Fix): Boolean {
        if (fix.accuracy != null && fix.accuracy > maxAccuracyM) return false
        val prev = last
        if (prev != null) {
            val dtMs = fix.time - prev.time
            if (dtMs <= 0) return false
            if (dtMs < gapMs) {
                val speed = GeoMath.distanceM(prev, fix) / (dtMs / 1000.0)
                if (speed > maxSpeedMps && ++speedRejects < maxRejectsInRow) return false
            }
        }
        speedRejects = 0
        last = fix
        return true
    }

    /** Forget the reference point, e.g. when a new segment starts after a pause. */
    fun reset() {
        last = null
        speedRejects = 0
    }
}
