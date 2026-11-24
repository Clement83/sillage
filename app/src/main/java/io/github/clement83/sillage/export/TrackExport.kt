package io.github.clement83.sillage.export

import io.github.clement83.sillage.geo.Fix
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A waypoint to export: a key moment, or a waypoint of an imported track. */
data class ExportWaypoint(
    val time: Long?,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val name: String,
    val type: String? = KEY_MOMENT_TYPE,
)

const val KEY_MOMENT_TYPE = "keymoment"

data class ExportTrack(
    val name: String,
    val startedAt: Long,
    val segments: List<List<Fix>>,
    val waypoints: List<ExportWaypoint>,
)

/**
 * GPX 1.1 and KML writers. Pure functions, so they are tested on the JVM against the official
 * schema. Times are UTC ISO 8601; numbers never depend on the phone's locale.
 */
object TrackExport {

    private const val CREATOR = "Sillage"

    fun gpx(track: ExportTrack): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="$CREATOR" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
        append("""xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
        append('\n')
        append("  <metadata><name>").append(escape(track.name)).append("</name><time>")
            .append(isoTime(track.startedAt)).append("</time></metadata>\n")
        // The schema wants every <wpt> before the <trk>.
        for (w in track.waypoints) {
            append("  <wpt lat=\"").append(coord(w.lat)).append("\" lon=\"").append(coord(w.lon)).append("\">")
            w.alt?.let { append("<ele>").append(ele(it)).append("</ele>") }
            w.time?.let { append("<time>").append(isoTime(it)).append("</time>") }
            append("<name>").append(escape(w.name)).append("</name>")
            w.type?.let { append("<type>").append(escape(it)).append("</type>") }
            append("</wpt>\n")
        }
        append("  <trk>\n    <name>").append(escape(track.name)).append("</name>\n")
        for (segment in track.segments.filter { it.isNotEmpty() }) {
            append("    <trkseg>\n")
            for (p in segment) {
                append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
                p.alt?.let { append("<ele>").append(ele(it)).append("</ele>") }
                append("<time>").append(isoTime(p.time)).append("</time></trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    fun kml(track: ExportTrack, keyMomentsFolder: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">""").append('\n')
        append("<Document>\n  <name>").append(escape(track.name)).append("</name>\n")
        // KML colours are aabbggrr: this is the app's ember #C2560C.
        append("  <Style id=\"track\"><LineStyle><color>ff0c56c2</color><width>4</width></LineStyle></Style>\n")
        val segments = track.segments.filter { it.isNotEmpty() }
        segments.forEachIndexed { index, segment ->
            val name = if (segments.size > 1) "${track.name} (${index + 1})" else track.name
            val absolute = segment.all { it.alt != null }
            append("  <Placemark>\n    <name>").append(escape(name)).append("</name>\n")
            append("    <styleUrl>#track</styleUrl>\n    <gx:Track>\n")
            append("      <altitudeMode>").append(if (absolute) "absolute" else "clampToGround").append("</altitudeMode>\n")
            for (p in segment) append("      <when>").append(isoTime(p.time)).append("</when>\n")
            for (p in segment) {
                append("      <gx:coord>").append(coord(p.lon)).append(' ').append(coord(p.lat)).append(' ')
                    .append(if (absolute) ele(p.alt!!) else "0").append("</gx:coord>\n")
            }
            append("    </gx:Track>\n  </Placemark>\n")
        }
        if (track.waypoints.isNotEmpty()) {
            append("  <Folder>\n    <name>").append(escape(keyMomentsFolder)).append("</name>\n")
            for (w in track.waypoints) {
                append("    <Placemark><name>").append(escape(w.name)).append("</name>")
                w.time?.let { append("<TimeStamp><when>").append(isoTime(it)).append("</when></TimeStamp>") }
                append("<Point><coordinates>").append(coord(w.lon)).append(',').append(coord(w.lat))
                w.alt?.let { append(',').append(ele(it)) }
                append("</coordinates></Point></Placemark>\n")
            }
            append("  </Folder>\n")
        }
        append("</Document>\n</kml>\n")
    }

    /** `2026-11-24_10h32_ride.gpx`, or the outing title instead of `ride` once rides are linked. */
    fun fileName(startedAt: Long, title: String?, extension: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm", Locale.ROOT)
            .format(Instant.ofEpochMilli(startedAt).atZone(zone))
        val slug = title?.let(::slug)?.takeIf { it.isNotEmpty() } ?: "ride"
        return "${stamp}_$slug.$extension"
    }

    internal fun slug(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)

    private fun isoTime(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
    private fun coord(value: Double) = String.format(Locale.ROOT, "%.7f", value)
    private fun ele(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    internal fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Control characters are not allowed in XML 1.0.
                else -> if (c >= ' ' || c == '\t' || c == '\n' || c == '\r') append(c)
            }
        }
    }
}
