package io.github.clement83.sillage.export

import io.github.clement83.sillage.geo.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.StringReader
import java.time.ZoneId
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class TrackExportTest {

    private val t0 = 1_795_510_000_000L // 2026-11-24T08:46:40Z

    private val track = ExportTrack(
        name = "Sortie <Var> & \"amis\"",
        startedAt = t0,
        segments = listOf(
            listOf(
                Fix(t0, 43.2247421, 6.1491234, alt = 175.3),
                Fix(t0 + 1000, 43.2248, 6.1492, alt = 176.0),
            ),
            listOf(Fix(t0 + 60_000, 43.23, 6.15, alt = 180.0)),
        ),
        waypoints = listOf(ExportWaypoint(t0 + 500, 43.2247, 6.1491, 175.5, "Saut d'l'arbre")),
    )

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(org.xml.sax.InputSource(StringReader(xml)))

    @Test
    fun `gpx is valid against the official GPX 1_1 schema`() {
        val xsd = javaClass.classLoader!!.getResource("gpx11.xsd")
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd)
        schema.newValidator().validate(StreamSource(StringReader(TrackExport.gpx(track))))
    }

    @Test
    fun `gpx keeps segments, times and key moments as typed waypoints`() {
        val doc = parse(TrackExport.gpx(track))
        assertEquals(2, doc.getElementsByTagName("trkseg").length)
        assertEquals(3, doc.getElementsByTagName("trkpt").length)
        val wpt = doc.getElementsByTagName("wpt").item(0)
        assertEquals("Saut d'l'arbre", wpt.childNodes.let { nodes ->
            (0 until nodes.length).map { nodes.item(it) }.first { it.nodeName == "name" }.textContent
        })
        assertTrue(TrackExport.gpx(track).contains("<type>keymoment</type>"))
        assertTrue(TrackExport.gpx(track).contains("<time>2026-11-24T08:46:40Z</time>"))
        assertTrue(TrackExport.gpx(track).contains("<time>2026-11-24T08:46:40.500Z</time>"))
        assertEquals("Sortie <Var> & \"amis\"", doc.getElementsByTagName("name").item(0).textContent)
    }

    @Test
    fun `kml has one timed track per segment and key moments as timestamped points`() {
        val xml = TrackExport.kml(track, keyMomentsFolder = "Moments clés")
        val doc = parse(xml)
        val ns = "http://www.google.com/kml/ext/2.2"
        assertEquals(2, doc.getElementsByTagNameNS(ns, "Track").length)
        assertEquals(3, doc.getElementsByTagNameNS(ns, "coord").length)
        assertEquals("6.1491234 43.2247421 175.3", doc.getElementsByTagNameNS(ns, "coord").item(0).textContent)
        assertTrue(xml.contains("<TimeStamp><when>2026-11-24T08:46:40.500Z</when></TimeStamp>"))
        assertTrue(xml.contains("<coordinates>6.1491000,43.2247000,175.5</coordinates>"))
    }

    @Test
    fun `numbers ignore the phone locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertTrue(TrackExport.gpx(track).contains("lat=\"43.2247421\""))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `file name uses local start time and a slug of the title`() {
        val paris = ZoneId.of("Europe/Paris")
        assertEquals("2026-11-24_09h46_ride.gpx", TrackExport.fileName(t0, null, "gpx", paris))
        assertEquals("2026-11-24_09h46_creux-de-l-ane-col-d-eze.kml", TrackExport.fileName(t0, "Creux de l'Âne / Col d'Èze !", "kml", paris))
    }

    @Test
    fun `escape drops control characters`() {
        assertEquals("a&amp;b", TrackExport.escape("a&\u0001b"))
    }
}
