package io.github.clement83.sillage.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TrackSmootherTest {
    private val metreEast = 1 / (111_195.0 * cos(Math.toRadians(43.1)))

    /** A fix [eastM] / [northM] metres from (43.1, 6.0), one a second. */
    private fun fix(second: Int, eastM: Double, northM: Double, accuracy: Float = 5f, speed: Float? = null) =
        Fix(time = second * 1000L, lat = 43.1 + northM / 111_195.0, lon = 6.0 + eastM * metreEast, accuracy = accuracy, speed = speed)

    private fun eastOf(f: Fix) = (f.lon - 6.0) / metreEast

    @Test
    fun `a stop leaves a point or two, not a tangle`() {
        // Two minutes standing still with ±5 m of jitter.
        val raw = List(120) { s -> fix(s, 5 * sin(s * 1.3), 5 * cos(s * 0.7), speed = 0f) }
        val clean = TrackSmoother.smooth(listOf(raw)).single()
        assertTrue("${clean.size} points", clean.size <= 3)
    }

    @Test
    fun `riding straight with noise gives a straighter line`() {
        // North at 5 m/s, ±6 m of sideways noise.
        val raw = List(300) { s -> fix(s, 6 * sin(s * 2.1), s * 5.0, speed = 5f) }
        val clean = TrackSmoother.smooth(listOf(raw)).single()
        val rawWorst = raw.maxOf { abs(eastOf(it)) }
        val cleanWorst = clean.drop(5).maxOf { abs(eastOf(it)) }
        assertTrue("clean $cleanWorst vs raw $rawWorst", cleanWorst < rawWorst / 2)
        // Still about as long: no corner cut short.
        assertEquals(1495.0, (clean.last().lat - clean.first().lat) * 111_195.0, 30.0)
    }

    @Test
    fun `inaccurate fixes are dropped`() {
        val raw = listOf(fix(0, 0.0, 0.0), fix(1, 0.0, 5.0), fix(2, 80.0, 10.0, accuracy = 35f), fix(3, 0.0, 15.0))
        val clean = TrackSmoother.smooth(listOf(raw)).single()
        assertTrue(clean.all { abs(eastOf(it)) < 1.0 })
    }

    @Test
    fun `same raw fixes, same clean track`() {
        val raw = List(100) { s -> fix(s, 4 * sin(s.toDouble()), s * 4.0, speed = 4f) }
        assertEquals(TrackSmoother.smooth(listOf(raw)), TrackSmoother.smooth(listOf(raw)))
    }
}
