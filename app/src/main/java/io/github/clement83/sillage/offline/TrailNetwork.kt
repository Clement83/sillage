package io.github.clement83.sillage.offline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * The trails of the offline map around a point (#1796), read from the vector tiles on the phone
 * at [zoom] and kept in a small cache. [tile] gives a stored tile (XYZ), or null where the map was
 * not downloaded: then there is nothing to snap to.
 */
class TrailNetwork(
    private val tile: (z: Int, x: Int, y: Int) -> ByteArray?,
    private val zoom: Int = ZOOM,
    private val kinds: Set<String> = KINDS,
) {
    private val cache = object : LinkedHashMap<Long, List<VectorTile.Line>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<VectorTile.Line>>?) = size > CACHE_TILES
    }

    /** The lines of the tiles within [radiusM] of the point (one to four tiles). */
    fun near(lat: Double, lon: Double, radiusM: Double = 60.0): List<VectorTile.Line> {
        val dLat = radiusM / M_PER_DEG
        val dLon = radiusM / (M_PER_DEG * cos(Math.toRadians(lat)))
        val keys = LinkedHashSet<Pair<Int, Int>>()
        for (la in listOf(lat - dLat, lat + dLat)) for (lo in listOf(lon - dLon, lon + dLon)) keys += tileX(lo) to tileY(la)
        return keys.flatMap { (x, y) -> lines(x, y) }
    }

    private fun lines(x: Int, y: Int): List<VectorTile.Line> {
        val key = (x.toLong() shl 32) or y.toLong()
        return cache.getOrPut(key) {
            val data = runCatching { tile(zoom, x, y) }.getOrNull() ?: return@getOrPut emptyList()
            runCatching { VectorTile.lines(data, zoom, x, y, LAYER, kinds) }.getOrDefault(emptyList())
        }
    }

    private fun tileX(lon: Double) = floor((lon + 180) / 360 * (1 shl zoom)).toInt()

    private fun tileY(lat: Double): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl zoom)).toInt()
    }

    companion object {
        /** Protomaps' trails are complete from zoom 14 (z15 only splits them further). */
        const val ZOOM = 14
        const val LAYER = "roads"

        /** Trails and tracks, and the small roads ridden between them. */
        val KINDS = setOf("path", "minor_road")
        private const val CACHE_TILES = 24
        private const val M_PER_DEG = 111_195.0
    }
}
