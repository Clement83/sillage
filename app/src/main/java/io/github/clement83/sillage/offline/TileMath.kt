package io.github.clement83.sillage.offline

import io.github.clement83.sillage.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Web Mercator tile arithmetic for choosing which tiles to download. Pure JVM, tested. */
object TileMath {
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val MAX_LAT = 85.05112878

    /** Fractional tile column of [lon] at zoom [z]. */
    fun x(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    /** Fractional tile row (from the top) of [lat] at zoom [z]. */
    fun y(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - asinh(tan(r)) / PI) / 2.0 * (1 shl z)
    }

    /** Width of a tile at [lat], in metres on the ground. */
    fun tileWidthM(lat: Double, z: Int): Double = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / (1 shl z)

    /** Every tile of [zooms] that touches the box [minLon], [minLat], [maxLon], [maxLat]. */
    fun boxTiles(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, zooms: IntRange): Set<TileKey> {
        val out = LinkedHashSet<TileKey>()
        for (z in zooms) {
            val last = (1 shl z) - 1
            val x0 = clamp(floor(x(minLon, z)), last)
            val x1 = clamp(floor(x(maxLon, z)), last)
            val y0 = clamp(floor(y(maxLat, z)), last)
            val y1 = clamp(floor(y(minLat, z)), last)
            for (tx in x0..x1) for (ty in y0..y1) out += TileKey(z, tx, ty)
        }
        return out
    }

    /**
     * The tiles of [zooms] within about [radiusM] of the route [segments]: a corridor along the
     * outing. The route is walked in steps of a quarter tile so that no tile along it is skipped.
     */
    fun corridorTiles(segments: List<List<GeoPoint>>, radiusM: Double, zooms: IntRange): Set<TileKey> {
        val out = LinkedHashSet<TileKey>()
        for (z in zooms) {
            val last = (1 shl z) - 1
            for (segment in segments) {
                if (segment.isEmpty()) continue
                var prev = segment.first()
                addAround(out, z, last, prev, radiusM)
                for (p in segment.drop(1)) {
                    val dx = x(p.lon, z) - x(prev.lon, z)
                    val dy = y(p.lat, z) - y(prev.lat, z)
                    val steps = max(1, ceil(max(kotlin.math.abs(dx), kotlin.math.abs(dy)) * 4).toInt())
                    for (i in 1..steps) {
                        val f = i.toDouble() / steps
                        addAround(out, z, last, GeoPoint(prev.lat + (p.lat - prev.lat) * f, prev.lon + (p.lon - prev.lon) * f), radiusM)
                    }
                    prev = p
                }
            }
        }
        return out
    }

    private fun addAround(out: MutableSet<TileKey>, z: Int, last: Int, p: GeoPoint, radiusM: Double) {
        val r = radiusM / tileWidthM(p.lat, z)
        val fx = x(p.lon, z)
        val fy = y(p.lat, z)
        for (tx in clamp(floor(fx - r), last)..clamp(floor(fx + r), last)) {
            for (ty in clamp(floor(fy - r), last)..clamp(floor(fy + r), last)) out += TileKey(z, tx, ty)
        }
    }

    /** Bounding box [minLon, minLat, maxLon, maxLat] of [segments], or null when empty. */
    fun bounds(segments: List<List<GeoPoint>>): DoubleArray? {
        val points = segments.flatten()
        if (points.isEmpty()) return null
        return doubleArrayOf(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })
    }

    /** True when box [a] lies within box [b], both as [minLon, minLat, maxLon, maxLat]. */
    fun contains(b: List<Double>, a: DoubleArray): Boolean =
        b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]

    private fun clamp(v: Double, last: Int): Int = min(max(v.toInt(), 0), last)
}

/** One HTTP range of the tile data section and the tiles it carries. */
data class RangeRequest(val offset: Long, val length: Long, val entries: List<PlannedTile>)

/** A tile to copy: where it goes and where its bytes are, relative to the tile data section. */
data class PlannedTile(val key: TileKey, val offset: Long, val length: Int)

object RangePlan {
    /** Bytes of unwanted data worth downloading to save a request. */
    const val MAX_GAP = 32L * 1024

    /** Upper bound of one request, so that progress moves and a retry costs little. */
    const val MAX_RANGE = 4L * 1024 * 1024

    /**
     * Groups [tiles] into as few ranges as is reasonable: neighbours in the file (the archive is
     * clustered along the Hilbert curve) share a request when the gap between them is small.
     */
    fun plan(tiles: Collection<PlannedTile>, maxGap: Long = MAX_GAP, maxRange: Long = MAX_RANGE): List<RangeRequest> {
        val sorted = tiles.sortedWith(compareBy({ it.offset }, { it.length }))
        val out = ArrayList<RangeRequest>()
        var start = -1L
        var end = -1L
        var current = ArrayList<PlannedTile>()
        for (t in sorted) {
            val tEnd = t.offset + t.length
            if (start >= 0 && t.offset - end <= maxGap && max(end, tEnd) - start <= maxRange) {
                end = max(end, tEnd)
                current += t
            } else {
                if (start >= 0) out += RangeRequest(start, end - start, current)
                start = t.offset
                end = tEnd
                current = arrayListOf(t)
            }
        }
        if (start >= 0) out += RangeRequest(start, end - start, current)
        return out
    }

    /** Bytes actually downloaded by [ranges]. */
    fun bytes(ranges: List<RangeRequest>): Long = ranges.sumOf { it.length }
}
