package io.github.clement83.sillage.offline

import io.github.clement83.sillage.map.MapStyles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineStyleTest {

    private val server = """
        {"version":8,"name":"Singletrack offline","glyphs":"https://example.org/{fontstack}/{range}.pbf",
         "sources":{
           "protomaps":{"type":"vector","url":"pmtiles://https://0.0.0.0:3000/api/app/offline/{region}.pmtiles"},
           "dem":{"type":"raster-dem","encoding":"terrarium","tileSize":256,"tiles":["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"]},
           "satellite":{"type":"raster","tiles":["https://example.org/{z}/{x}/{y}.jpg"]}},
         "layers":[
           {"id":"background","type":"background","paint":{"background-color":"#f2efe9"}},
           {"id":"hillshade","type":"hillshade","source":"dem"},
           {"id":"water","type":"fill","source":"protomaps","source-layer":"water"},
           {"id":"photo","type":"raster","source":"satellite"}]}
    """.trimIndent()

    private fun compose(reliefUrl: String? = "mbtiles:///r.mbtiles", offlineFirst: Boolean = false) =
        Json.parseToJsonElement(OfflineStyle.compose(server, "mbtiles:///m.mbtiles", reliefUrl, offlineFirst)).jsonObject

    private fun layerIds(style: kotlinx.serialization.json.JsonObject) =
        style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `points the server style at the local files, OpenTopoMap on top`() {
        val style = compose()
        val sources = style["sources"]!!.jsonObject
        assertEquals("mbtiles:///m.mbtiles", sources["protomaps"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("mbtiles:///r.mbtiles", sources["dem"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("terrarium", sources["dem"]!!.jsonObject["encoding"]!!.jsonPrimitive.content)
        assertNull(sources["satellite"]) // would need the network
        assertEquals(listOf("background", "hillshade", "water", "opentopomap"), layerIds(style))
        assertNull(style["glyphs"])
    }

    @Test
    fun `offline first leaves OpenTopoMap out`() {
        val style = compose(offlineFirst = true)
        assertEquals(listOf("background", "hillshade", "water"), layerIds(style))
        assertFalse("opentopomap" in style["sources"]!!.jsonObject)
    }

    @Test
    fun `no relief file drops the hillshade`() {
        val style = compose(reliefUrl = null)
        assertEquals(listOf("background", "water", "opentopomap"), layerIds(style))
    }

    @Test
    fun `an unusable server style falls back to OpenTopoMap`() {
        assertEquals(MapStyles.OPEN_TOPO_MAP, OfflineStyle.compose(null, "mbtiles:///m", null, false))
        assertEquals(MapStyles.OPEN_TOPO_MAP, OfflineStyle.compose("not json", "mbtiles:///m", null, false))
        assertEquals(MapStyles.OPEN_TOPO_MAP, OfflineStyle.compose("""{"sources":{},"layers":[]}""", "mbtiles:///m", null, false))
    }
}
