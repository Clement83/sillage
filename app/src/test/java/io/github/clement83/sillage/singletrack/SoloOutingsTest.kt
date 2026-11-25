package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.OutingEntity
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val paris = ZoneId.of("Europe/Paris")
    private val server = "https://example.org"

    // 2026-11-25 09:00 in Paris.
    private val nine = 1_795_593_600_000L

    private fun outing(id: Long, startsAt: Long, cancelled: Boolean = false, server: String = this.server) = OutingEntity(
        id = id,
        server = server,
        remoteId = "o$id",
        title = "Sortie $id",
        startsAt = startsAt,
        json = """{"id":"o$id","title":"Sortie $id","startsAt":"2026-11-25T08:00:00Z","cancelled":$cancelled}""",
        fetchedAt = 0,
        hasDetail = true,
    )

    @Test
    fun `the outing of the day starting within the hour, the closest one`() {
        val ride = nine + 20 * 60_000 // 9:20
        val outings = listOf(outing(1, nine - 30 * 60_000), outing(2, nine), outing(3, nine + 3 * 3_600_000))
        assertEquals(2L, SoloOutings.matchingOuting(ride, outings, server, paris)?.id)
    }

    @Test
    fun `no outing more than an hour away, cancelled, or on another server`() {
        val ride = nine
        assertNull(SoloOutings.matchingOuting(ride, listOf(outing(1, nine + 61 * 60_000)), server, paris))
        assertNull(SoloOutings.matchingOuting(ride, listOf(outing(1, nine, cancelled = true)), server, paris))
        assertNull(SoloOutings.matchingOuting(ride, listOf(outing(1, nine, server = "https://other.example")), server, paris))
    }

    @Test
    fun `not an outing of another day, even within the hour`() {
        val beforeMidnight = 1_795_647_000_000L // 2026-11-25 23:50 in Paris
        val afterMidnight = beforeMidnight + 20 * 60_000 // 00:10 the next day
        assertNull(SoloOutings.matchingOuting(beforeMidnight, listOf(outing(1, afterMidnight)), server, paris))
    }
}
