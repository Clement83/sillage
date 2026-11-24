package io.github.clement83.sillage.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackShapeTest {

    /** Points every 10 m northwards, climbing [rise] metres per point. */
    private fun climb(count: Int, rise: Double, start: Double = 100.0) =
        List(count) { i -> GeoPoint(43.0 + i * 10 / 111_195.0, 6.0, start + i * rise) }

    @Test
    fun `distance and climb of a planned track`() {
        val seg = climb(11, rise = 1.0) // 100 m, +10 m
        assertEquals(100.0, TrackShape.distanceM(listOf(seg)), 0.1)
        // 5 m hysteresis, as for a recording: the last metres under the threshold are not counted.
        assertEquals(6.0, TrackShape.elevationGainM(listOf(seg)), 1e-9)
    }

    @Test
    fun `grade chunks of about the requested length`() {
        val seg = climb(31, rise = 1.0) // 300 m at 10 %
        val chunks = TrackShape.gradeChunks(listOf(seg), chunkM = 100.0)
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(0.10, it.grade!!, 0.001) }
        // Chunks join: each starts where the previous one ended.
        assertEquals(chunks[0].points.last(), chunks[1].points.first())
    }

    @Test
    fun `no altitude means no grade and no profile`() {
        val seg = climb(5, 0.0).map { it.copy(alt = null) }
        assertNull(TrackShape.gradeChunks(listOf(seg)).single().grade)
        assertTrue(TrackShape.profile(listOf(seg)).isEmpty())
    }

    @Test
    fun `locate finds the distance along the track`() {
        val seg = climb(11, rise = 0.0)
        val (along, away) = TrackShape.locate(listOf(seg), seg[7].lat, seg[7].lon + 0.0001)!!
        assertEquals(70.0, along, 0.1)
        assertTrue(away in 7.0..9.0)
    }
}
