package io.github.clement83.sillage.offline

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** A tile address. `y` counts from the top (XYZ, as in URLs), not from the bottom (TMS). */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    /** The MBTiles row, which counts from the bottom. */
    val tmsY: Int get() = (1 shl z) - 1 - y
}

/** The fixed 127-byte header of a PMTiles v3 archive. */
data class PmHeader(
    val rootOffset: Long,
    val rootLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafOffset: Long,
    val leafLength: Long,
    val dataOffset: Long,
    val dataLength: Long,
    val internalCompression: Int,
    val tileCompression: Int,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
)

/**
 * A directory entry. With [runLength] > 0 it holds tiles [tileId] until [tileId] + [runLength] - 1,
 * which all share the bytes at [offset] (relative to the tile data section). With [runLength] 0 it
 * points to a leaf directory (offset relative to the leaf section).
 */
data class PmEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

/**
 * The parts of the PMTiles v3 format (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md)
 * Sillage needs to copy tiles out of a remote archive read by HTTP ranges. Pure JVM, tested.
 */
object PmTiles {
    const val HEADER_LENGTH = 127

    /** The root directory always fits in the first 16 KiB, header included. */
    const val FIRST_READ = 16_384

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2
    const val TYPE_MVT = 1

    fun parseHeader(bytes: ByteArray): PmHeader {
        if (bytes.size < HEADER_LENGTH || String(bytes, 0, 7, Charsets.US_ASCII) != "PMTiles") {
            throw IOException("Not a PMTiles archive")
        }
        if (bytes[7].toInt() != 3) throw IOException("PMTiles version ${bytes[7]} is not supported")
        val b = ByteBuffer.wrap(bytes, 0, HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        fun u8(at: Int) = bytes[at].toInt() and 0xff
        fun e7(at: Int) = b.getInt(at) / 10_000_000.0
        return PmHeader(
            rootOffset = b.getLong(8),
            rootLength = b.getLong(16),
            metadataOffset = b.getLong(24),
            metadataLength = b.getLong(32),
            leafOffset = b.getLong(40),
            leafLength = b.getLong(48),
            dataOffset = b.getLong(56),
            dataLength = b.getLong(64),
            internalCompression = u8(97),
            tileCompression = u8(98),
            tileType = u8(99),
            minZoom = u8(100),
            maxZoom = u8(101),
            minLon = e7(102),
            minLat = e7(106),
            maxLon = e7(110),
            maxLat = e7(114),
        )
    }

    /** Decodes a directory, decompressing it first per the header's internal compression. */
    fun parseDirectory(raw: ByteArray, compression: Int): List<PmEntry> {
        val bytes = decompress(raw, compression)
        val r = VarintReader(bytes)
        val n = r.next().toInt()
        if (n < 0 || n > bytes.size) throw IOException("Corrupt PMTiles directory")
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) {
            last += r.next()
            ids[i] = last
        }
        val runs = IntArray(n) { r.next().toInt() }
        val lengths = IntArray(n) { r.next().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            // 0 means "right after the previous entry", anything else is the offset plus one.
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { PmEntry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE, 0 -> bytes
        COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> throw IOException("PMTiles compression $compression is not supported")
    }

    /**
     * The entry that holds [tileId] in a sorted directory: its tile entry, the leaf directory to
     * look into, or null when the archive has no such tile.
     */
    fun find(entries: List<PmEntry>, tileId: Long): PmEntry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = tileId.compareTo(entries[mid].tileId)
            when {
                c > 0 -> lo = mid + 1
                c < 0 -> hi = mid - 1
                else -> return entries[mid]
            }
        }
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0) return e
            if (tileId - e.tileId < e.runLength) return e
        }
        return null
    }

    /** Hilbert-curve tile id: all the tiles of the lower zooms first, then the position along the curve. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..31) { "zoom $z" }
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y" }
        val base = ((1L shl (2 * z)) - 1) / 3
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (tx and s != 0L) 1L else 0L
            val ry = if (ty and s != 0L) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = n - 1 - tx
                    ty = n - 1 - ty
                }
                val t = tx
                tx = ty
                ty = t
            }
            s /= 2
        }
        return base + d
    }

    fun tileId(key: TileKey): Long = tileId(key.z, key.x, key.y)

    /** Inverse of [tileId]. */
    fun tileKey(id: Long): TileKey {
        var z = 0
        var base = 0L
        while (true) {
            val count = 1L shl (2 * z)
            if (id < base + count) break
            base += count
            z++
            require(z <= 31) { "tile id $id" }
        }
        val n = 1L shl z
        var t = id - base
        var x = 0L
        var y = 0L
        var s = 1L
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x
                x = y
                y = tmp
            }
            x += s * rx
            y += s * ry
            t /= 4
            s *= 2
        }
        return TileKey(z, x.toInt(), y.toInt())
    }

    private class VarintReader(private val bytes: ByteArray) {
        private var pos = 0

        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= bytes.size) throw IOException("Truncated PMTiles directory")
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Corrupt PMTiles varint")
            }
        }
    }
}
