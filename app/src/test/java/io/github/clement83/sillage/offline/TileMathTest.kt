package io.github.clement83.sillage.offline

import io.github.clement83.sillage.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class TileMathTest {

    private fun tileOf(lat: Double, lon: Double, z: Int) =
        TileKey(z, floor(TileMath.x(lon, z)).toInt(), floor(TileMath.y(lat, z)).toInt())

    @Test
    fun `projects to tile coordinates`() {
        assertEquals(0.5, TileMath.x(0.0, 0), 1e-9)
        assertEquals(0.5, TileMath.y(0.0, 0), 1e-9)
        // Known tile: Toulon harbour at z12.
        assertEquals(TileKey(12, 2115, 1503), tileOf(43.115, 5.93, 12))
    }

    @Test
    fun `box tiles cover the box at each zoom`() {
        val tiles = TileMath.boxTiles(5.9, 43.1, 6.0, 43.2, 8..10)
        assertTrue(tileOf(43.15, 5.95, 10) in tiles)
        assertEquals(setOf(8, 9, 10), tiles.map { it.z }.toSet())
        assertEquals(1, tiles.count { it.z == 8 })
    }

    @Test
    fun `corridor keeps about the radius around the route`() {
        // 5 km due east at 43.2° N, then 2 km of corridor on each side.
        val route = listOf(listOf(GeoPoint(43.2, 6.0), GeoPoint(43.2, 6.0616)))
        val tiles = TileMath.corridorTiles(route, 2000.0, 16..16)
        val northM = 1.0 / 111_320.0
        assertTrue(tileOf(43.2, 6.03, 16) in tiles) // on the route
        assertTrue(tileOf(43.2 + 1500 * northM, 6.03, 16) in tiles) // 1.5 km away
        assertFalse(tileOf(43.2 + 3500 * northM, 6.03, 16) in tiles) // 3.5 km away
        assertFalse(tileOf(43.2, 6.0616 + 0.05, 16) in tiles) // 4 km past the end
        // Roughly 9 km x 4 km of z16 tiles (~450 m wide here): a few hundred, not thousands.
        assertTrue(tiles.size in 150..400)
    }

    @Test
    fun `corridor does not skip tiles between distant points`() {
        val route = listOf(listOf(GeoPoint(43.0, 6.0), GeoPoint(43.0, 6.5)))
        val tiles = TileMath.corridorTiles(route, 0.0, 14..14)
        val x0 = floor(TileMath.x(6.0, 14)).toInt()
        val x1 = floor(TileMath.x(6.5, 14)).toInt()
        val y = floor(TileMath.y(43.0, 14)).toInt()
        assertTrue((x0..x1).all { TileKey(14, it, y) in tiles })
    }

    @Test
    fun `box containment`() {
        assertTrue(TileMath.contains(listOf(5.0, 43.0, 7.0, 44.0), doubleArrayOf(5.5, 43.1, 6.0, 43.5)))
        assertFalse(TileMath.contains(listOf(5.0, 43.0, 7.0, 44.0), doubleArrayOf(4.5, 43.1, 6.0, 43.5)))
    }

    @Test
    fun `range plan merges neighbours and splits the rest`() {
        val k = TileKey(0, 0, 0)
        val tiles = listOf(
            PlannedTile(k, 0, 100),
            PlannedTile(k, 100, 50),
            PlannedTile(k, 100, 50), // same bytes (deduplicated tile)
            PlannedTile(k, 1_000, 10), // small gap: same request
            PlannedTile(k, 1_000_000, 10), // far: new request
        )
        val plan = RangePlan.plan(tiles, maxGap = 32 * 1024, maxRange = 4 * 1024 * 1024)
        assertEquals(2, plan.size)
        assertEquals(RangeRequest(0, 1_010, tiles.take(4)), plan[0])
        assertEquals(1_000_000L, plan[1].offset)
        assertEquals(10L, plan[1].length)

        val capped = RangePlan.plan(tiles.take(4), maxGap = 32 * 1024, maxRange = 200)
        assertEquals(listOf(0L, 1_000L), capped.map { it.offset })
    }
}
