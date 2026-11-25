package io.github.clement83.sillage.offline

import io.github.clement83.sillage.geo.GeoPoint
import io.github.clement83.sillage.singletrack.ApiResult
import io.github.clement83.sillage.singletrack.SingletrackClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a real singletrack server, skipped unless `SILLAGE_LIVE_SERVER` and `SILLAGE_LIVE_TOKEN`
 * (an app token of a paired account) are set: checks that the offline packs read as the app reads
 * them. Never run by the CI.
 */
class OfflineLiveTest {
    private val server: String? = System.getenv("SILLAGE_LIVE_SERVER")
    private val token: String? = System.getenv("SILLAGE_LIVE_TOKEN")
    private val client = SingletrackClient("Sillage-test")

    @Test
    fun `copies a corridor out of the first region`() = runBlocking {
        assumeTrue(server != null && token != null)
        val catalog = (client.offlineCatalog(server!!, token!!) as ApiResult.Ok).value
        val region = catalog.regions.firstOrNull()
        assumeTrue("no region offered yet", region != null)
        println("region ${region!!.id} ${region.name} v${region.version} ${region.pmtilesSize} bytes bbox=${region.bbox} segments=${region.segments.map { it.name }}")

        val archive = RemoteArchive.open { offset, length ->
            (client.offlineRange(server, token, "${region.id}.pmtiles", offset, length) as ApiResult.Ok).value
        }
        val h = archive.header
        println("header z${h.minZoom}-${h.maxZoom} tileType=${h.tileType} tileCompression=${h.tileCompression} internal=${h.internalCompression}")
        assertEquals(PmTiles.TYPE_MVT, h.tileType)

        // A 6 km route in the middle of the region's box.
        val lat = (region.bbox[1] + region.bbox[3]) / 2
        val lon = (region.bbox[0] + region.bbox[2]) / 2
        val route = listOf(listOf(GeoPoint(lat, lon), GeoPoint(lat + 0.03, lon + 0.04), GeoPoint(lat + 0.05, lon + 0.02)))
        val keys = TileMath.corridorTiles(route, OfflineMaps.CORRIDOR_M, 10..minOf(h.maxZoom, OfflineMaps.MAX_ZOOM))
        val tiles = archive.locate(keys)
        val ranges = RangePlan.plan(tiles)
        println("corridor: ${keys.size} tiles wanted, ${tiles.size} in the archive, ${RangePlan.bytes(ranges)} bytes in ${ranges.size} requests")
        assertTrue(tiles.size > keys.size / 2)

        val range = ranges.first()
        val bytes = archive.read(range)
        assertEquals(range.length, bytes.size.toLong())
        for (t in range.entries) {
            val data = bytes.copyOfRange((t.offset - range.offset).toInt(), (t.offset - range.offset + t.length).toInt())
            val mvt = PmTiles.decompress(data, h.tileCompression)
            // A vector tile is a protobuf message of layers: field 3, length-delimited.
            assertEquals("tile ${t.key}", 0x1a, mvt[0].toInt())
        }

        val style = (client.offlineStyle(server, token, region.id) as ApiResult.Ok).value
        val composed = Json.parseToJsonElement(OfflineStyle.compose(style, "mbtiles:///m.mbtiles", "mbtiles:///r.mbtiles", false)).jsonObject
        assertTrue("protomaps" in composed["sources"]!!.jsonObject || composed["sources"]!!.jsonObject.size > 1)
    }
}
