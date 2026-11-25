package io.github.clement83.sillage.singletrack

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SingletrackClientTest {

    private lateinit var server: MockWebServer
    private val client = SingletrackClient(userAgent = "Sillage-test")
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `guest outing is read without a token`() = runBlocking {
        server.enqueue(MockResponse().setBody(DETAIL))
        val r = client.guestOuting(base, "tok_EN-abc")
        val outing = (r as ApiResult.Ok).value
        assertEquals("Tour du Faron", outing.title)
        assertEquals(Outing.ROLE_GUEST, outing.role)
        assertNull(outing.slug)
        assertEquals("sportif", outing.level)
        assertEquals("yes", outing.ebike)
        assertEquals(1_795_510_000_000, outing.startsAtMs)
        assertEquals(2, outing.track!!.segments().single().size)
        assertEquals(1, outing.participants!!.count { it.isMe })

        val request = server.takeRequest()
        assertEquals("/api/app/i/tok_EN-abc", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("Sillage-test", request.getHeader("User-Agent"))
    }

    @Test
    fun `a ride becomes a solo outing with its client id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"slug":"sortie-du-25","url":"https://example.org/s/sortie-du-25"}"""))
        val r = client.soloOuting(base, "secret", "<gpx/>".toByteArray(), "ride.gpx", "c0ffee", "Sortie du 25", "2026-11-25T09:30:00+01:00")
        assertEquals("sortie-du-25", (r as ApiResult.Ok).value.slug)
        val request = server.takeRequest()
        assertEquals("/api/app/rides", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        for (part in listOf("name=\"clientId\"", "c0ffee", "name=\"title\"", "Sortie du 25", "name=\"startedAt\"", "2026-11-25T09:30:00+01:00", "filename=\"ride.gpx\"", "<gpx/>")) {
            assertTrue(part, part in body)
        }
    }

    @Test
    fun `a server without solo outings answers not found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApiResult.NotFound, client.soloOuting(base, "secret", ByteArray(0), "r.gpx", "c", "t", "2026-11-25T09:30:00Z"))
    }

    @Test
    fun `account calls send the bearer token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"outings":[{"id":"o1","role":"organizer","slug":"faron","title":"A","startsAt":"2026-11-24T08:46:40.000Z","unknownField":1}]}"""))
        val r = client.outings(base, "secret")
        assertEquals("faron", (r as ApiResult.Ok).value.single().slug)
        val request = server.takeRequest()
        assertEquals("/api/app/outings", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test
    fun `path segments are encoded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        assertEquals(ApiResult.NotFound, client.outing(base, "t", "a/b c"))
        assertEquals("/api/app/outings/a%2Fb%20c", server.takeRequest().path)
    }

    @Test
    fun `http errors become values`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"tooManyRequests"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"tooEarly"}"""))
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        assertEquals(ApiResult.Unauthorized, client.outings(base, "revoked"))
        assertEquals(ApiResult.TooManyRequests, client.pair(base, "K7M2QX", "Jelly2"))
        assertEquals(ApiResult.Failed(409, "tooEarly"), client.uploadRide(RideTarget.Guest(base, "t"), ByteArray(1), "a.gpx"))
        assertTrue(client.guestOuting(base, "t") is ApiResult.Offline)
    }

    @Test
    fun `no server is offline, not a crash`() = runBlocking {
        val url = base
        server.shutdown()
        assertTrue(client.guestOuting(url, "t") is ApiResult.Offline)
        assertTrue(client.guestOuting("not a url", "t") is ApiResult.Offline)
    }

    @Test
    fun `pairing posts the code and returns the token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"abc123"}"""))
        assertEquals(ApiResult.Ok("abc123"), client.pair(base, "K7M2QX", "Unihertz Jelly2"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/app/pair", request.path)
        assertEquals("""{"code":"K7M2QX","deviceName":"Unihertz Jelly2"}""", request.body.readUtf8())
    }

    @Test
    fun `ride upload is a multipart file, guest or account`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true,"id":"r1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true,"id":"r2"}"""))
        val gpx = "<gpx/>".toByteArray()
        assertEquals(ApiResult.Ok(Unit), client.uploadRide(RideTarget.Guest(base, "tok"), gpx, "ride.gpx"))
        assertEquals(ApiResult.Ok(Unit), client.uploadRide(RideTarget.Account(base, "secret", "o1"), gpx, "ride.gpx"))

        val guest = server.takeRequest()
        assertEquals("/api/i/tok/ride", guest.path)
        assertNull(guest.getHeader("Authorization"))
        val body = guest.body.readUtf8()
        assertTrue(body.contains("""name="file"; filename="ride.gpx""""))
        assertTrue(body.contains("<gpx/>"))

        val account = server.takeRequest()
        assertEquals("/api/app/outings/o1/ride", account.path)
        assertEquals("Bearer secret", account.getHeader("Authorization"))
    }

    @Test
    fun `server installed under a sub-path`() {
        assertEquals(
            "https://example.org/st/api/app/i/x",
            SingletrackClient.url("https://example.org/st", "api/app/i", "x").toString(),
        )
        assertEquals("https://example.org/api/app/pair", SingletrackClient.url("https://example.org", "api/app/pair").toString())
    }

    @Test
    fun `geometry of both kinds, bad positions skipped`() {
        val multi = AppApi.json.decodeFromString(
            OutingTrack.serializer(),
            """{"geometry":{"type":"MultiLineString","coordinates":[[[6,43,100],[6.001,43.001]],[[6,43],["x",1],[6.1,43.1,5]],[[6,43]]]}}""",
        ).segments()
        assertEquals(2, multi.size)
        assertEquals(100.0, multi[0][0].alt!!, 0.0)
        assertNull(multi[0][1].alt)
        assertEquals(2, multi[1].size)
        assertTrue(OutingTrack().segments().isEmpty())
    }

    private companion object {
        val DETAIL = """
            {"id":"o1","role":"guest","title":"Tour du Faron","startsAt":"2026-11-24T08:46:40.000Z","status":"planned",
             "cancelled":false,"done":false,"closed":false,"meeting":{"lat":43.13,"lon":5.93,"label":"Parking"},
             "distanceM":21500,"elevationGainM":640,"level":"sportif","ebike":"yes","organizerName":"Clément",
             "description":"Rando","maxParticipants":null,"visibility":"invite","webPath":null,"myAnswer":"yes","yesCount":2,
             "participants":[{"id":"i1","name":"Léa","status":"yes","isMe":true,"isOrganizer":false},
                             {"id":"owner-u1","name":"Clément","status":"yes","isMe":false,"isOrganizer":true}],
             "track":{"name":"Faron","geometry":{"type":"LineString","coordinates":[[5.93,43.13,120.5],[5.94,43.14,180]]},
                      "bbox":[5.93,43.13,5.94,43.14],"start":[5.93,43.13],"distanceM":21500,"elevationGainM":640,
                      "elevationLossM":640,"minEle":100,"maxEle":560,"difficulty":"red","loop":true,"attribution":null,
                      "license":null,"profile":[{"distKm":0,"ele":120.5,"lon":5.93,"lat":43.13},{"distKm":1.4,"ele":180,"lon":5.94,"lat":43.14}]}}
        """.trimIndent()
    }
}
