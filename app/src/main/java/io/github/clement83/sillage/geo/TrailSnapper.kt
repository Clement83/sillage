package io.github.clement83.sillage.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Snaps the clean track onto the trails of the offline map (#1796), point by point. A point within
 * [snapM] of a trail going the same way (±[maxAngleDeg]) is moved onto it; the trail being followed
 * is kept as long as I stay within [leaveM] of it and no other trail is clearly closer, so two
 * parallel trails do not make the track zigzag. After [leaveAfter] points in a row further than
 * [leaveM], I am off the known trails: the points stay where the GPS put them, until I am back near
 * one. [linesNear] gives the trails around a point (the same list object for the same trail).
 */
class TrailSnapper(
    private val linesNear: (lat: Double, lon: Double) -> List<List<GeoPoint>>,
    private val snapM: Double = 15.0,
    private val leaveM: Double = 20.0,
    private val leaveAfter: Int = 3,
    private val maxAngleDeg: Double = 45.0,
) {
    private class Hit(val line: List<GeoPoint>, val lat: Double, val lon: Double, val distanceM: Double, val bearing: Double)

    private var current: List<GeoPoint>? = null
    private var away = 0
    private var previous: Fix? = null

    fun add(fix: Fix, newSegment: Boolean = false): Fix {
        if (newSegment) {
            current = null
            away = 0
            previous = null
        }
        val heading = previous?.takeIf { GeoMath.distanceM(it, fix) >= MIN_MOVE_M }?.let { bearing(it.lat, it.lon, fix.lat, fix.lon) }
        previous = fix
        var best: Hit? = null
        var onCurrent: Hit? = null
        for (line in linesNear(fix.lat, fix.lon)) {
            val hit = project(line, fix.lat, fix.lon) ?: continue
            if (line === current) onCurrent = hit
            if (heading != null && angle(heading, hit.bearing) > maxAngleDeg) continue
            if (best == null || hit.distanceM < best.distanceM) best = hit
        }
        val stay = onCurrent?.takeIf { it.distanceM <= leaveM && (best == null || best.distanceM + SWITCH_MARGIN_M >= it.distanceM) }
        val chosen = stay ?: best?.takeIf { it.distanceM <= snapM }
        if (chosen != null) {
            current = chosen.line
            away = 0
            return fix.copy(lat = chosen.lat, lon = chosen.lon)
        }
        if (current != null && ++away >= leaveAfter) {
            current = null
            away = 0
        }
        return fix
    }

    /** The nearest point of [line], with the bearing of the stretch it lies on. */
    private fun project(line: List<GeoPoint>, lat: Double, lon: Double): Hit? {
        if (line.size < 2) return null
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG
        var best: Hit? = null
        for (i in 0 until line.size - 1) {
            val ax = (line[i].lon - lon) * kx
            val ay = (line[i].lat - lat) * M_PER_DEG
            val bx = (line[i + 1].lon - lon) * kx
            val by = (line[i + 1].lat - lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = hypot(px, py)
            if (best == null || d < best.distanceM) {
                best = Hit(line, lat + py / M_PER_DEG, lon + px / kx, d, Math.toDegrees(atan2(dx, dy)))
            }
        }
        return best
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * cos(Math.toRadians(lat1))
        val dy = lat2 - lat1
        return Math.toDegrees(atan2(dx, dy))
    }

    /** Angle between two directions of travel along a trail, either way: 0 to 90°. */
    private fun angle(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 540) % 360 - 180) // 0..180
        return minOf(d, 180 - d)
    }

    private companion object {
        const val M_PER_DEG = 111_195.0
        const val MIN_MOVE_M = 2.0

        /** Another trail must be this much closer to leave the one being followed. */
        const val SWITCH_MARGIN_M = 8.0
    }
}

/**
 * The clean track (#1795, #1796) built fix by fix: smoothed, then snapped onto the trails when a
 * [snapper] is given. Feed it the raw segments again and again (live), only the new fixes are
 * processed; a shorter input (another recording) starts over.
 */
class CleanTrack(private val snapper: (() -> TrailSnapper)? = null) {
    private var smoother = TrackSmoother()
    private var trails = snapper?.invoke()
    private val done = ArrayList<Int>()
    private val out = ArrayList<MutableList<Fix>>()

    fun feed(segments: List<List<Fix>>): List<List<Fix>> {
        if (segments.size < done.size || segments.indices.any { it < done.size && segments[it].size < done[it] }) reset()
        segments.forEachIndexed { i, seg ->
            while (done.size <= i) {
                done.add(0)
                out.add(mutableListOf())
            }
            for (j in done[i] until seg.size) {
                val p = smoother.add(seg[j], newSegment = j == 0) ?: continue
                out[i] += trails?.add(p, newSegment = out[i].isEmpty()) ?: p
            }
            done[i] = seg.size
        }
        return out.map { it.toList() }
    }

    private fun reset() {
        smoother = TrackSmoother()
        trails = snapper?.invoke()
        done.clear()
        out.clear()
    }
}
