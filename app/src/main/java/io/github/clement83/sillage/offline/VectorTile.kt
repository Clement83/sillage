package io.github.clement83.sillage.offline

import io.github.clement83.sillage.geo.GeoPoint
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Just enough of the Mapbox Vector Tile format (protobuf) to read the lines of one layer (#1796):
 * the trails of the offline map, for the track to snap to. Pure JVM, no dependency.
 */
object VectorTile {

    /** A line of the layer with its `kind` and `kind_detail` (Protomaps' `roads`: path, track…). */
    data class Line(val kind: String?, val detail: String?, val points: List<GeoPoint>)

    /** The lines of [layer] in tile z/x/y (XYZ) whose `kind` is in [kinds]; [data] may be gzipped. */
    fun lines(data: ByteArray, z: Int, x: Int, y: Int, layer: String, kinds: Set<String>): List<Line> {
        val bytes = if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } else {
            data
        }
        val tile = Pb(bytes)
        val out = ArrayList<Line>()
        while (tile.more()) {
            val (field, wire) = tile.tag()
            if (field == 3 && wire == 2) {
                readLayer(tile.bytes(), z, x, y, layer, kinds, out)
            } else {
                tile.skip(wire)
            }
        }
        return out
    }

    private fun readLayer(bytes: Pb, z: Int, x: Int, y: Int, wanted: String, kinds: Set<String>, out: MutableList<Line>) {
        var name: String? = null
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        val features = ArrayList<Pb>()
        while (bytes.more()) {
            val (field, wire) = bytes.tag()
            when {
                field == 1 && wire == 2 -> name = bytes.string()
                field == 2 && wire == 2 -> features += bytes.bytes()
                field == 3 && wire == 2 -> keys += bytes.string()
                field == 4 && wire == 2 -> values += readValue(bytes.bytes())
                field == 5 && wire == 0 -> extent = bytes.varint().toInt()
                else -> bytes.skip(wire)
            }
            if (field == 1 && name != wanted) return // not the layer we want: skip it whole
        }
        if (name != wanted) return
        val kindKey = keys.indexOf("kind")
        val detailKey = keys.indexOf("kind_detail")
        for (f in features) {
            var type = 0
            var tags: LongArray? = null
            var geometry: LongArray? = null
            while (f.more()) {
                val (field, wire) = f.tag()
                when {
                    field == 2 && wire == 2 -> tags = f.packed()
                    field == 3 && wire == 0 -> type = f.varint().toInt()
                    field == 4 && wire == 2 -> geometry = f.packed()
                    else -> f.skip(wire)
                }
            }
            if (type != LINESTRING || geometry == null) continue
            var kind: String? = null
            var detail: String? = null
            tags?.let { t ->
                var i = 0
                while (i + 1 < t.size) {
                    val k = t[i].toInt()
                    val v = values.getOrNull(t[i + 1].toInt())
                    if (k == kindKey) kind = v
                    if (k == detailKey) detail = v
                    i += 2
                }
            }
            if (kind !in kinds) continue
            for (part in decodeLines(geometry, z, x, y, extent)) if (part.size >= 2) out += Line(kind, detail, part)
        }
    }

    /** A value as a string when it is one (the only kind read here). */
    private fun readValue(v: Pb): String? {
        var s: String? = null
        while (v.more()) {
            val (field, wire) = v.tag()
            if (field == 1 && wire == 2) s = v.string() else v.skip(wire)
        }
        return s
    }

    private fun decodeLines(g: LongArray, z: Int, x: Int, y: Int, extent: Int): List<List<GeoPoint>> {
        val lines = ArrayList<MutableList<GeoPoint>>()
        var cx = 0L
        var cy = 0L
        var i = 0
        val n = (1 shl z).toDouble()
        fun point(): GeoPoint {
            val px = (x + cx.toDouble() / extent) / n
            val py = (y + cy.toDouble() / extent) / n
            return GeoPoint(lat = atan(sinh(PI * (1 - 2 * py))) * 180 / PI, lon = px * 360 - 180)
        }
        while (i < g.size) {
            val command = (g[i] and 7L).toInt()
            val count = (g[i] ushr 3).toInt()
            i++
            when (command) {
                MOVE_TO, LINE_TO -> repeat(count) {
                    if (i + 1 >= g.size) return lines
                    cx += zigzag(g[i])
                    cy += zigzag(g[i + 1])
                    i += 2
                    if (command == MOVE_TO) lines += mutableListOf(point()) else lines.lastOrNull()?.add(point())
                }
                else -> Unit // ClosePath: not in lines
            }
        }
        return lines
    }

    private fun zigzag(v: Long) = (v ushr 1) xor -(v and 1)

    private const val LINESTRING = 2
    private const val MOVE_TO = 1
    private const val LINE_TO = 2

    /** A tiny protobuf reader over a slice of bytes. */
    private class Pb(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
        fun more() = pos < end

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }

        fun bytes(): Pb {
            val len = varint().toInt()
            val from = pos
            pos = (pos + len).coerceAtMost(end)
            return Pb(buf, from, pos)
        }

        fun string(): String {
            val len = varint().toInt()
            val s = String(buf, pos, len.coerceAtMost(end - pos), Charsets.UTF_8)
            pos = (pos + len).coerceAtMost(end)
            return s
        }

        fun packed(): LongArray {
            val p = bytes()
            val out = ArrayList<Long>()
            while (p.more()) out += p.varint()
            return out.toLongArray()
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}
