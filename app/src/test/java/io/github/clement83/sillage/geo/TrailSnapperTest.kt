package io.github.clement83.sillage.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TrailSnapperTest {
    private val metreEast = 1 / (111_195.0 * cos(Math.toRadians(43.1)))

    /** A north-south trail [eastM] metres east of 6.0°E, 2 km long. */
    private fun trail(eastM: Double) = listOf(GeoPoint(43.1, 6.0 + eastM * metreEast), GeoPoint(43.1 + 2000 / 111_195.0, 6.0 + eastM * metreEast))

    private fun fix(second: Int, eastM: Double, northM: Double = second * 5.0) =
        Fix(time = second * 1000L, lat = 43.1 + northM / 111_195.0, lon = 6.0 + eastM * metreEast)

    private fun eastOf(f: Fix) = (f.lon - 6.0) / metreEast

    @Test
    fun `a ride along a trail lands on it`() {
        val a = trail(0.0)
        val snapper = TrailSnapper({ _, _ -> listOf(a) })
        val out = List(100) { s -> snapper.add(fix(s, 6 * sin(s * 1.7)), newSegment = s == 0) }
        assertTrue(out.all { abs(eastOf(it)) < 0.01 })
    }

    @Test
    fun `two parallel trails 20 m apart give no zigzag`() {
        val a = trail(0.0)
        val b = trail(20.0)
        val snapper = TrailSnapper({ _, _ -> listOf(a, b) })
        // On A, with noise pulling up to 11 m towards B.
        val out = List(200) { s -> snapper.add(fix(s, 3 + 8 * sin(s * 0.9)), newSegment = s == 0) }
        assertTrue(out.all { abs(eastOf(it)) < 0.01 })
    }

    @Test
    fun `off the known trails the GPS track is kept, then snapping resumes`() {
        val a = trail(0.0)
        val snapper = TrailSnapper({ _, _ -> listOf(a) })
        val on1 = List(20) { s -> snapper.add(fix(s, 2.0), newSegment = s == 0) }
        val off = List(20) { s -> snapper.add(fix(20 + s, 50.0)) }
        val on2 = List(20) { s -> snapper.add(fix(40 + s, 3.0)) }
        assertTrue(on1.all { abs(eastOf(it)) < 0.01 })
        assertTrue(off.all { abs(eastOf(it) - 50.0) < 0.01 })
        // Coming back sideways, the first point is not along the trail yet; the next ones are.
        assertTrue(on2.drop(1).all { abs(eastOf(it)) < 0.01 })
    }

    @Test
    fun `no trail around, nothing moves`() {
        val snapper = TrailSnapper({ _, _ -> emptyList() })
        val f = fix(1, 7.0)
        assertEquals(f, snapper.add(f, newSegment = true))
    }

    @Test
    fun `the clean track is built from new fixes only, and starts over for another recording`() {
        val a = trail(0.0)
        val clean = CleanTrack { TrailSnapper({ _, _ -> listOf(a) }) }
        val ride = List(60) { s -> fix(s, 4.0).copy(accuracy = 5f, speed = 5f) }
        val first = clean.feed(listOf(ride.take(30)))
        val all = clean.feed(listOf(ride))
        assertEquals(first.single(), all.single().take(first.single().size))
        assertEquals(CleanTrack { TrailSnapper({ _, _ -> listOf(a) }) }.feed(listOf(ride)), all)
        assertTrue(all.single().all { abs(eastOf(it)) < 0.01 })
        // A shorter input is another recording: built from scratch.
        assertEquals(CleanTrack { TrailSnapper({ _, _ -> listOf(a) }) }.feed(listOf(ride.take(5))), clean.feed(listOf(ride.take(5))))
    }
}
