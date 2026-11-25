package io.github.clement83.sillage.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** About 1.11 m per 0.00001° of latitude. */
private fun north(base: Fix, metres: Double, seconds: Long, alt: Double? = null) =
    base.copy(time = base.time + seconds * 1000, lat = base.lat + metres / 111_195.0, alt = alt)

class GeoMathTest {
    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, GeoMath.distanceM(43.0, 6.0, 44.0, 6.0), 1.0)
    }

    @Test
    fun `same point is zero`() {
        assertEquals(0.0, GeoMath.distanceM(43.1, 6.2, 43.1, 6.2), 1e-9)
    }
}

class FixFilterTest {
    private val start = Fix(time = 1_000_000, lat = 43.2, lon = 6.1, accuracy = 5f)

    @Test
    fun `rejects inaccurate fixes but accepts fixes without accuracy`() {
        val filter = FixFilter()
        assertFalse(filter.accept(start.copy(accuracy = 31f)))
        assertTrue(filter.accept(start.copy(accuracy = null)))
    }

    @Test
    fun `rejects fixes that do not move forward in time`() {
        val filter = FixFilter()
        assertTrue(filter.accept(start))
        assertFalse(filter.accept(start))
        assertFalse(filter.accept(start.copy(time = start.time - 1)))
    }

    @Test
    fun `rejects an impossible jump, accepts a fast descent`() {
        val filter = FixFilter()
        assertTrue(filter.accept(start))
        assertFalse(filter.accept(north(start, 200.0, 1)))
        assertTrue(filter.accept(north(start, 20.0, 1)))
    }

    @Test
    fun `gives up the speed check after five rejections in a row`() {
        val filter = FixFilter()
        assertTrue(filter.accept(start))
        val far = north(start, 5_000.0, 1)
        repeat(4) { assertFalse(filter.accept(far.copy(time = far.time + it * 1000))) }
        assertTrue(filter.accept(far.copy(time = far.time + 4_000)))
    }

    @Test
    fun `skips the speed check after a long gap and after a reset`() {
        val filter = FixFilter()
        assertTrue(filter.accept(start))
        assertTrue(filter.accept(north(start, 5_000.0, 31)))
        filter.reset()
        assertTrue(filter.accept(start.copy(time = start.time + 40_000)))
    }
}

class TrackStatsTest {
    private val start = Fix(time = 0, lat = 43.2, lon = 6.1, alt = 100.0)

    @Test
    fun `adds distance in steps of at least three metres`() {
        val stats = TrackStats()
        stats.add(start)
        stats.add(north(start, 2.0, 1))
        assertEquals(0.0, stats.distanceM, 1e-9)
        stats.add(north(start, 4.0, 2))
        assertEquals(4.0, stats.distanceM, 0.01)
        stats.add(north(start, 104.0, 30))
        assertEquals(104.0, stats.distanceM, 0.05)
    }

    @Test
    fun `counts nothing across segments`() {
        val stats = TrackStats()
        stats.add(start)
        stats.add(north(start, 1_000.0, 200, alt = 150.0), newSegment = true)
        assertEquals(0.0, stats.distanceM, 1e-9)
        assertEquals(0.0, stats.elevationGainM, 1e-9)
    }

    @Test
    fun `ignores altitude noise below the threshold`() {
        val stats = TrackStats.raw()
        listOf(100.0, 103.0, 97.0, 103.0, 98.0, 104.0, 100.0).forEachIndexed { i, alt ->
            stats.add(north(start, i * 5.0, i.toLong(), alt))
        }
        assertEquals(0.0, stats.elevationGainM, 1e-9)
    }

    @Test
    fun `counts real climbs, not descents`() {
        val stats = TrackStats.raw()
        listOf(100.0, 104.0, 108.0, 120.0, 90.0, 80.0, 95.0).forEachIndexed { i, alt ->
            stats.add(north(start, i * 5.0, i.toLong(), alt))
        }
        // 100 → 108 → 120 (+20), down to 80, then → 95 (+15)
        assertEquals(35.0, stats.elevationGainM, 1e-9)
    }

    /** One fix a second along a line, with the altitude given for each second. */
    private fun ride(stats: TrackStats, alts: List<Double>) =
        alts.forEachIndexed { i, alt -> stats.add(north(start, i * 4.0, i.toLong(), alt)) }

    @Test
    fun `GPS altitude, the wild start of a segment is not a climb`() {
        // Seen on a real ride: right after a resume, 108 → 177 → 44 m in 20 s, then flat around 85 m.
        val warmUp = listOf(108.0, 177.0, 168.0, 151.0, 104.0, 90.0, 73.0, 62.0, 59.0, 46.0, 44.0, 56.0, 58.0, 66.0, 76.0)
        val stats = TrackStats()
        ride(stats, warmUp + List(300) { 85.0 })
        assertEquals(0.0, stats.elevationGainM, 1e-9)
    }

    @Test
    fun `GPS altitude, noise and spikes on a flat ride add almost nothing`() {
        val noise = List(600) { i -> 85.0 + 9 * kotlin.math.sin(i / 1.6) + (if (i % 97 == 50) 40.0 else 0.0) }
        val stats = TrackStats()
        ride(stats, noise)
        assertTrue("gain ${stats.elevationGainM}", stats.elevationGainM < 6.0)
    }

    @Test
    fun `GPS altitude, a real climb is counted`() {
        // 100 m climbed at 0.5 m/s, with ±4 m of noise, then a rest at the top.
        val climb = List(60) { 100.0 } + List(200) { i -> 100.0 + i * 0.5 + if (i % 2 == 0) 4.0 else -4.0 } + List(150) { 200.0 }
        val stats = TrackStats()
        ride(stats, climb)
        assertEquals(100.0, stats.elevationGainM, 6.0)
    }
}
