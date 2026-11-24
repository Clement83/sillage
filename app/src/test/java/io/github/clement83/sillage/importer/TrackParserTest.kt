package io.github.clement83.sillage.importer

import io.github.clement83.sillage.export.ExportTrack
import io.github.clement83.sillage.export.ExportWaypoint
import io.github.clement83.sillage.export.TrackExport
import io.github.clement83.sillage.geo.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrackParserTest {

    private fun parse(text: String) = TrackParser.parse(text.toByteArray())

    @Test
    fun `gpx 1_1 track, route and waypoints`() {
        val track = parse(
            """<?xml version="1.0"?>
            <gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Boucle du Coudon</name></metadata>
              <wpt lat="43.15" lon="5.99"><ele>700</ele><name>Sommet</name></wpt>
              <trk><name>ignored</name>
                <trkseg>
                  <trkpt lat="43.1" lon="5.9"><ele>100.5</ele><time>2026-11-24T08:00:00Z</time></trkpt>
                  <trkpt lat="43.2" lon="5.95"><ele>110</ele></trkpt>
                </trkseg>
                <trkseg><trkpt lat="1" lon="1"/></trkseg>
              </trk>
              <rte><rtept lat="43.3" lon="6.0"/><rtept lat="43.31" lon="6.01"/></rte>
            </gpx>""",
        )
        assertEquals("Boucle du Coudon", track.name)
        assertEquals(2, track.segments.size) // the one-point segment is dropped
        assertEquals(ParsedPoint(43.1, 5.9, 100.5, 1_795_507_200_000L), track.segments[0][0])
        assertEquals(listOf(ParsedWaypoint("Sommet", 43.15, 5.99, 700.0)), track.waypoints)
    }

    @Test
    fun `gpx 1_0 without metadata takes the track name`() {
        val track = parse(
            """<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><name>Old file</name><trkseg>
                <trkpt lat="43.1" lon="5.9"/><trkpt lat="43.2" lon="5.9"/>
              </trkseg></trk></gpx>""",
        )
        assertEquals("Old file", track.name)
        assertEquals(2, track.pointCount)
    }

    @Test
    fun `kml line strings, multi geometry and points`() {
        val track = parse(
            """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Rando</name>
              <Folder>
                <Placemark><name>Aller</name><MultiGeometry>
                  <LineString><coordinates>5.9,43.1,100 5.95,43.2,120</coordinates></LineString>
                  <LineString><coordinates>
                    5.96,43.21 5.97,43.22
                  </coordinates></LineString>
                </MultiGeometry></Placemark>
                <Placemark><name>Source</name><Point><coordinates>5.93,43.15,300</coordinates></Point></Placemark>
              </Folder></Document></kml>""",
        )
        assertEquals("Rando", track.name)
        assertEquals(2, track.segments.size)
        assertEquals(ParsedPoint(43.2, 5.95, 120.0), track.segments[0][1])
        assertNull(track.segments[1][0].alt)
        assertEquals(listOf(ParsedWaypoint("Source", 43.15, 5.93, 300.0)), track.waypoints)
    }

    @Test
    fun `kml gx track keeps times`() {
        val track = parse(
            """<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
              <Placemark><name>Ride</name><gx:Track>
                <when>2026-11-24T08:00:00Z</when><when>2026-11-24T08:00:01Z</when>
                <gx:coord>5.9 43.1 100</gx:coord><gx:coord>5.91 43.11 101</gx:coord>
              </gx:Track></Placemark></kml>""",
        )
        assertEquals("Ride", track.name)
        assertEquals(1_795_507_201_000L, track.segments[0][1].time)
    }

    @Test
    fun `kmz reads doc kml`() {
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><name>Zip</name>
            <LineString><coordinates>5.9,43.1 5.95,43.2</coordinates></LineString></Placemark></kml>"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("files/icon.png")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("doc.kml")); zip.write(kml.toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        assertEquals("Zip", TrackParser.parse(bytes).name)
    }

    @Test
    fun `our own exports read back the same`() {
        val t0 = 1_795_507_200_000L
        val export = ExportTrack(
            name = "Sillage",
            startedAt = t0,
            segments = listOf(listOf(Fix(t0, 43.1, 5.9, 100.0), Fix(t0 + 1000, 43.1001, 5.9001, 101.0))),
            waypoints = listOf(ExportWaypoint(t0 + 500, 43.1, 5.9, 100.0, "Saut")),
        )
        for (text in listOf(TrackExport.gpx(export), TrackExport.kml(export, "Moments clés"))) {
            val track = parse(text)
            assertEquals("Sillage", track.name)
            assertEquals(ParsedPoint(43.1001, 5.9001, 101.0, t0 + 1000), track.segments[0][1])
            assertEquals("Saut", track.waypoints.single().name)
            assertEquals(t0 + 500, track.waypoints.single().time)
        }
    }

    @Test
    fun `rejects non tracks, empty tracks and doctype tricks`() {
        fun reason(text: String) = assertThrows(ImportException::class.java) { parse(text) }.reason
        assertEquals(ImportException.Reason.NOT_A_TRACK, reason("hello"))
        assertEquals(ImportException.Reason.NOT_A_TRACK, reason("<html><body/></html>"))
        assertEquals(ImportException.Reason.NO_POINTS, reason("""<gpx><wpt lat="1" lon="1"/></gpx>"""))
        assertEquals(
            ImportException.Reason.NOT_A_TRACK,
            reason("""<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx>&x;</gpx>"""),
        )
    }
}
