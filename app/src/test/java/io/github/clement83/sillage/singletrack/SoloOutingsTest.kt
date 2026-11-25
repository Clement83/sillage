package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SoloOutingsTest {
    @Test
    fun `startedAt carries the offset and the seconds`() {
        // 2026-11-25 08:30:00 UTC, winter time in Paris.
        val ms = 1_795_595_400_000
        assertEquals("2026-11-25T09:30:00+01:00", SoloOutings.startedAt(ms, ZoneId.of("Europe/Paris")))
        assertEquals("2026-11-25T08:30:00Z", SoloOutings.startedAt(ms, ZoneId.of("UTC")))
    }

    @Test
    fun `only a finished ride off any outing can become one`() {
        val ride = RecordingEntity(startedAt = 0, status = RecordingStatus.FINISHED, pointCount = 2)
        assertTrue(SoloOutings.canMake(ride))
        assertFalse(SoloOutings.canMake(ride.copy(outingId = 3)))
        assertFalse(SoloOutings.canMake(ride.copy(status = RecordingStatus.RECORDING)))
        assertFalse(SoloOutings.canMake(ride.copy(pointCount = 1)))
    }
}
