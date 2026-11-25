package io.github.clement83.sillage.geo

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Tells when I leave the followed track (#1797), fed fix by fix. Off the track = more than
 * [thresholdM] from it for at least [delayMs], so a hairpin or a GPS jump does not count. Nothing is
 * reported before I first reach the track (a start away from it is not "leaving" it). While off, a
 * reminder every [reminderMs]. Back on it: no alert, just [Event.BACK].
 */
class OffTrackDetector(
    segments: List<List<GeoPoint>>,
    private val thresholdM: Double = 40.0,
    private val delayMs: Long = 15_000,
    private val reminderMs: Long = 120_000,
) {
    enum class Event { LEFT, REMIND, BACK }

    /** [distanceM] from the track (null without track), [offTrack] once the delay has passed. */
    data class Status(val distanceM: Double?, val offTrack: Boolean, val event: Event?)

    private val lats: DoubleArray
    private val lons: DoubleArray

    /** Whether vertex i and i + 1 are linked (false across segments). */
    private val linked: BooleanArray

    init {
        val points = segments.filter { it.isNotEmpty() }
        lats = DoubleArray(points.sumOf { it.size })
        lons = DoubleArray(lats.size)
        linked = BooleanArray(lats.size)
        var i = 0
        for (seg in points) {
            seg.forEachIndexed { j, p ->
                lats[i] = p.lat
                lons[i] = p.lon
                linked[i] = j < seg.size - 1
                i++
            }
        }
    }

    private var joined = false
    private var awaySince: Long? = null
    private var off = false
    private var lastAlertAt = 0L
    private var hint = 0

    fun update(time: Long, lat: Double, lon: Double, reminders: Boolean = true): Status {
        val d = distanceTo(lat, lon) ?: return Status(null, false, null)
        if (d <= thresholdM) {
            joined = true
            awaySince = null
            val wasOff = off
            off = false
            return Status(d, false, if (wasOff) Event.BACK else null)
        }
        if (!joined) return Status(d, false, null)
        val since = awaySince ?: time.also { awaySince = it }
        if (!off) {
            if (time - since < delayMs) return Status(d, false, null)
            off = true
            lastAlertAt = time
            return Status(d, true, Event.LEFT)
        }
        if (reminders && time - lastAlertAt >= reminderMs) {
            lastAlertAt = time
            return Status(d, true, Event.REMIND)
        }
        return Status(d, true, null)
    }

    /**
     * Distance to the nearest stretch of the track. Looks around the last nearest point first (cheap,
     * the usual case), and over the whole track when that finds nothing close.
     */
    internal fun distanceTo(lat: Double, lon: Double): Double? {
        if (lats.isEmpty()) return null
        if (lats.size == 1) return GeoMath.distanceM(lat, lon, lats[0], lons[0])
        val near = nearest(lat, lon, (hint - WINDOW).coerceAtLeast(0), (hint + WINDOW).coerceAtMost(lats.size - 1))
        val best = if (near.second <= thresholdM) near else nearest(lat, lon, 0, lats.size - 1)
        hint = best.first
        return best.second
    }

    /** (index, distance) of the nearest stretch starting between [from] and [to]. */
    private fun nearest(lat: Double, lon: Double, from: Int, to: Int): Pair<Int, Double> {
        // Local flat projection around the fix: metres east (x) and north (y).
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG
        var bestI = from
        var bestD = Double.MAX_VALUE
        for (i in from..to) {
            val ax = (lons[i] - lon) * kx
            val ay = (lats[i] - lat) * M_PER_DEG
            val d = if (linked[i] && i + 1 < lats.size) {
                val bx = (lons[i + 1] - lon) * kx
                val by = (lats[i + 1] - lat) * M_PER_DEG
                pointToSegment(ax, ay, bx, by)
            } else {
                hypot(ax, ay)
            }
            if (d < bestD) {
                bestD = d
                bestI = i
            }
        }
        return bestI to bestD
    }

    /** Distance from the origin to the segment a–b. */
    private fun pointToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    private companion object {
        const val M_PER_DEG = 111_195.0
        const val WINDOW = 300
    }
}
