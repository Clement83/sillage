package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.OutingEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutingStorageTest {

    private val storage = Json(AppApi.json) {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `a list entry updates the list fields of a stored detail and keeps the rest`() {
        val detail = Outing(
            id = "o1", title = "Old", startsAt = "2026-11-24T08:00:00Z", cancelled = false,
            description = "Bring a lamp", track = OutingTrack(name = "Faron"),
        )
        val summary = Outing(id = "o1", title = "New title", startsAt = "2026-11-25T08:00:00Z", cancelled = true)
        val merged = OutingRepository.mergeSummary(
            storage.encodeToString(Outing.serializer(), detail),
            storage.encodeToJsonElement(Outing.serializer(), summary).jsonObject,
        )
        val outing = AppApi.json.decodeFromJsonElement(Outing.serializer(), merged)
        assertEquals("New title", outing.title)
        assertTrue(outing.cancelled)
        assertEquals("Bring a lamp", outing.description)
        assertEquals("Faron", outing.track?.name)
    }

    @Test
    fun `website link prefers the personal link, then the organiser page`() {
        val row = OutingEntity(
            server = "https://st.example", remoteId = "o1", title = "T", startsAt = 0, json = "{}",
            hasDetail = false, fetchedAt = 0,
        )
        assertEquals("https://st.example/i/tok", row.copy(inviteToken = "tok", slug = "s").webUrl(null))
        assertEquals("https://st.example/s/faron", row.copy(slug = "faron").webUrl(null))
        assertNull(row.webUrl(null))
        assertEquals("https://st.example/i/x", SingletrackLink.Invite("https://st.example", "x").webUrl())
    }
}
