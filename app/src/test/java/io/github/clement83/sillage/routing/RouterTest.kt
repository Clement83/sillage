package io.github.clement83.sillage.routing

import io.github.clement83.sillage.geo.GeoMath
import io.github.clement83.sillage.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

class SegmentTilesTest {
    @Test
    fun `names follow BRouter's 5 degree squares`() {
        assertEquals("E5_N40", SegmentTiles.nameOf(43.2, 6.1))
        assertEquals("E0_N45", SegmentTiles.nameOf(45.0, 0.0))
        assertEquals("W5_N40", SegmentTiles.nameOf(43.5, -1.5))
        assertEquals("W75_S35", SegmentTiles.nameOf(-33.4, -70.6))
    }

    @Test
    fun `a box over a corner needs four segments`() {
        assertEquals(setOf("E0_N40", "E5_N40", "E0_N45", "E5_N45"), SegmentTiles.namesIn(4.9, 44.9, 5.1, 45.1))
        assertEquals(setOf("E5_N40"), SegmentTiles.namesIn(5.8, 43.0, 6.5, 43.6))
    }

    @Test
    fun `no segment on the phone means no data`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sillage-no-segments").apply { mkdirs() }
        val r = Router(dir, File("src/main/assets/brouter/singletrack.brf")).route(GeoPoint(43.13, 5.93), GeoPoint(43.15, 5.95))
        assertEquals(RouteResult.NoData, r)
    }
}

/**
 * With a real segment, skipped unless `SILLAGE_BROUTER_SEGMENTS` names a folder holding `E5_N40.rd5`
 * (brouter.de/brouter/segments4/E5_N40.rd5). Never run by the CI.
 */
class RouterLiveTest {
    @Test
    fun `routes along the paths around Toulon`() {
        val dir = System.getenv("SILLAGE_BROUTER_SEGMENTS")?.let(::File)
        assumeTrue(dir != null && File(dir, "E5_N40.rd5").isFile)
        val router = Router(dir!!, File("src/main/assets/brouter/singletrack.brf"))
        // From Toulon's harbour up Mont Faron.
        val from = GeoPoint(43.1205, 5.9306)
        val to = GeoPoint(43.1460, 5.9370)
        lateinit var r: RouteResult
        val ms = measureTimeMillis { r = router.route(from, to) }
        val route = (r as? RouteResult.Found)?.route ?: error("no route: $r")
        val straight = GeoMath.distanceM(from.lat, from.lon, to.lat, to.lon)
        println("route ${route.distanceM} m (straight $straight m), D+ ${route.climbM} m, ${route.points.size} points, $ms ms")
        assertTrue(route.distanceM > straight)
        assertTrue(route.climbM > 200)
        assertTrue(route.points.first().let { GeoMath.distanceM(it.lat, it.lon, from.lat, from.lon) } < 500)
    }
}
