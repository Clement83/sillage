package io.github.clement83.sillage.geo

import io.github.clement83.sillage.geo.OffTrackDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffTrackDetectorTest {
    // A straight track 2 km north from (43.1, 6.0), one point every 100 m.
    private val track = listOf(List(21) { GeoPoint(43.1 + it * 100 / 111_195.0, 6.0) })
    private val metreEast = 1 / (111_195.0 * kotlin.math.cos(Math.toRadians(43.1)))

    private fun at(eastM: Double, northM: Double) = (43.1 + northM / 111_195.0) to (6.0 + eastM * metreEast)

    private fun OffTrackDetector.feed(second: Long, eastM: Double, northM: Double = second * 5.0): OffTrackDetector.Status {
        val (lat, lon) = at(eastM, northM)
        return update(second * 1000, lat, lon)
    }

    @Test
    fun `distance is measured to the line, not only to its points`() {
        val d = OffTrackDetector(track)
        val (lat, lon) = at(30.0, 150.0) // between two points of the track
        assertEquals(30.0, d.distanceTo(lat, lon)!!, 0.5)
    }

    @Test
    fun `leaving for 15 s alerts once, then a reminder every 2 min, and back without alert`() {
        val d = OffTrackDetector(track)
        assertNull(d.feed(0, 0.0).event)
        assertNull(d.feed(10, 60.0).event) // away, but not for long yet
        assertFalse(d.feed(20, 60.0).offTrack)
        val left = d.feed(25, 60.0)
        assertEquals(Event.LEFT, left.event)
        assertTrue(left.offTrack)
        assertEquals(60.0, left.distanceM!!, 1.0)
        assertNull(d.feed(60, 70.0).event)
        assertEquals(Event.REMIND, d.feed(145, 70.0).event)
        val back = d.feed(150, 5.0)
        assertEquals(Event.BACK, back.event)
        assertFalse(back.offTrack)
    }

    @Test
    fun `a short swerve does not count`() {
        val d = OffTrackDetector(track)
        d.feed(0, 0.0)
        for (s in 1L..14L) assertNull(d.feed(s, 80.0).event)
        assertNull(d.feed(15, 10.0).event)
        assertNull(d.feed(40, 80.0).event) // the 15 s start again
    }

    @Test
    fun `nothing before reaching the track the first time`() {
        val d = OffTrackDetector(track)
        for (s in 0L..200L step 10) assertNull(d.feed(s, 500.0).event)
    }

    @Test
    fun `no reminder when they are off`() {
        val d = OffTrackDetector(track)
        d.feed(0, 0.0)
        d.feed(10, 60.0)
        d.feed(30, 60.0)
        val (lat, lon) = at(60.0, 200.0)
        assertNull(d.update(200_000, lat, lon, reminders = false).event)
    }
}
