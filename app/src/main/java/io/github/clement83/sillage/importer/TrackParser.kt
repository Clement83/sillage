package io.github.clement83.sillage.importer

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedPoint(val lat: Double, val lon: Double, val alt: Double? = null, val time: Long? = null)

data class ParsedWaypoint(val name: String?, val lat: Double, val lon: Double, val alt: Double? = null, val time: Long? = null)

data class ParsedTrack(
    val name: String?,
    val segments: List<List<ParsedPoint>>,
    val waypoints: List<ParsedWaypoint>,
) {
    val pointCount: Int get() = segments.sumOf { it.size }
}

class ImportException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { TOO_LARGE, NOT_A_TRACK, NO_POINTS }
}

/**
 * Reads GPX (1.0 / 1.1: tracks, routes, waypoints), KML (LineString, gx:Track, MultiGeometry,
 * Points) and KMZ. Pure JVM code: tested without Android. Element names are matched without their
 * namespace, so files from any tool or GPX version are read the same way.
 */
object TrackParser {

    const val MAX_BYTES = 20 * 1024 * 1024

    fun parse(bytes: ByteArray): ParsedTrack {
        if (bytes.size > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
        val xml = if (isZip(bytes)) kmlFromKmz(bytes) else bytes
        val root = try {
            documentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        } catch (e: SAXException) {
            throw ImportException(ImportException.Reason.NOT_A_TRACK, e)
        } catch (e: java.io.IOException) {
            throw ImportException(ImportException.Reason.NOT_A_TRACK, e)
        }
        val track = when (root.name()) {
            "gpx" -> parseGpx(root)
            "kml" -> parseKml(root)
            else -> throw ImportException(ImportException.Reason.NOT_A_TRACK)
        }
        val segments = track.segments.filter { it.size >= 2 }
        if (segments.isEmpty()) throw ImportException(ImportException.Reason.NO_POINTS)
        return track.copy(segments = segments)
    }

    // --- GPX ------------------------------------------------------------------------------------

    private fun parseGpx(root: Element): ParsedTrack {
        val segments = mutableListOf<List<ParsedPoint>>()
        for (trk in root.children("trk")) {
            for (seg in trk.children("trkseg")) segments += seg.children("trkpt").mapNotNull(::gpxPoint)
        }
        for (rte in root.children("rte")) segments += rte.children("rtept").mapNotNull(::gpxPoint)
        val waypoints = root.children("wpt").mapNotNull { wpt ->
            gpxPoint(wpt)?.let { ParsedWaypoint(wpt.childText("name"), it.lat, it.lon, it.alt, it.time) }
        }
        val name = root.children("metadata").firstNotNullOfOrNull { it.childText("name") }
            ?: root.children("trk").firstNotNullOfOrNull { it.childText("name") }
            ?: root.children("rte").firstNotNullOfOrNull { it.childText("name") }
        return ParsedTrack(name, segments, waypoints)
    }

    private fun gpxPoint(e: Element): ParsedPoint? {
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = e.getAttribute("lon").toDoubleOrNull() ?: return null
        if (!validLatLon(lat, lon)) return null
        return ParsedPoint(lat, lon, e.childText("ele")?.toDoubleOrNull(), e.childText("time")?.let(::parseTime))
    }

    // --- KML ------------------------------------------------------------------------------------

    private fun parseKml(root: Element): ParsedTrack {
        val segments = mutableListOf<List<ParsedPoint>>()
        val waypoints = mutableListOf<ParsedWaypoint>()
        var firstPlacemarkName: String? = null
        for (placemark in root.descendants("Placemark")) {
            val name = placemark.childText("name")
            if (firstPlacemarkName == null) firstPlacemarkName = name
            val lines = placemark.descendants("LineString")
            val tracks = placemark.descendants("Track")
            lines.forEach { segments += parseCoordinates(it.childText("coordinates").orEmpty()) }
            tracks.forEach { segments += gxTrack(it) }
            // A Point is a waypoint only in a placemark that is not a line (some tools add one at the start).
            val point = placemark.descendants("Point").firstOrNull()
            if (point != null && lines.isEmpty() && tracks.isEmpty()) {
                val p = parseCoordinates(point.childText("coordinates").orEmpty()).firstOrNull()
                val time = placemark.descendants("TimeStamp").firstOrNull()?.childText("when")?.let(::parseTime)
                if (p != null) waypoints += ParsedWaypoint(name, p.lat, p.lon, p.alt, time)
            }
        }
        val document = root.children("Document").firstOrNull() ?: root
        val name = document.childText("name") ?: firstPlacemarkName
        return ParsedTrack(name, segments, waypoints)
    }

    private fun gxTrack(track: Element): List<ParsedPoint> {
        val whens = track.children("when").map { parseTime(it.textContent.trim()) }
        return track.children("coord").mapIndexedNotNull { i, c ->
            val parts = c.textContent.trim().split(Regex("\\s+"))
            val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            if (!validLatLon(lat, lon)) return@mapIndexedNotNull null
            ParsedPoint(lat, lon, parts.getOrNull(2)?.toDoubleOrNull(), whens.getOrNull(i))
        }
    }

    /** KML `lon,lat[,alt]` tuples separated by whitespace. */
    private fun parseCoordinates(text: String): List<ParsedPoint> = text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
        val parts = tuple.split(',')
        val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        if (!validLatLon(lat, lon)) return@mapNotNull null
        ParsedPoint(lat, lon, parts.getOrNull(2)?.toDoubleOrNull())
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun isZip(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** The KML inside a KMZ: `doc.kml` by convention, else the first `.kml` entry. */
    private fun kmlFromKmz(bytes: ByteArray): ByteArray {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
                    entries[entry.name] = data
                }
            }
        }
        return entries.entries.firstOrNull { it.key.substringAfterLast('/').equals("doc.kml", ignoreCase = true) }?.value
            ?: entries.values.firstOrNull()
            ?: throw ImportException(ImportException.Reason.NOT_A_TRACK)
    }

    private fun documentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // No DTDs at all: no external entities, no entity expansion bombs.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private fun parseTime(text: String): Long? = runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrNull()

    private fun validLatLon(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

    private fun Node.name(): String = localName ?: nodeName.substringAfter(':')

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.name() == name }

    private fun Element.descendants(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childText(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }
}
