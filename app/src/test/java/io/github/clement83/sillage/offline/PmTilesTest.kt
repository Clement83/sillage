package io.github.clement83.sillage.offline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class PmTilesTest {

    @Test
    fun `tile ids follow the reference implementation`() {
        // Values from the reference pmtiles Python package.
        val cases = mapOf(
            TileKey(0, 0, 0) to 0L,
            TileKey(1, 0, 0) to 1L,
            TileKey(1, 0, 1) to 2L,
            TileKey(1, 1, 1) to 3L,
            TileKey(1, 1, 0) to 4L,
            TileKey(2, 0, 0) to 5L,
            TileKey(12, 3423, 1763) to 19_078_479L,
            TileKey(15, 16938, 11958) to 1_269_309_223L,
            TileKey(16, 33877, 23917) to 5_077_236_893L,
        )
        cases.forEach { (key, id) ->
            assertEquals("$key", id, PmTiles.tileId(key))
            assertEquals("$id", key, PmTiles.tileKey(id))
        }
    }

    @Test
    fun `tile id round trip at every zoom`() {
        for (z in 0..16) {
            val n = 1 shl z
            listOf(0 to 0, n - 1 to 0, 0 to n - 1, n - 1 to n - 1, n / 3 to n / 2).forEach { (x, y) ->
                assertEquals(TileKey(z, x, y), PmTiles.tileKey(PmTiles.tileId(z, x, y)))
            }
        }
    }

    @Test
    fun `parses a gzip directory written by the reference implementation`() {
        // serialize_directory([Entry(0,0,100,1), Entry(1,100,50,1), Entry(2,150,20,3), Entry(5,0,100,1),
        //                      Entry(40,500,7,0), Entry(19078479,9000,1234,1)])
        val raw = Base64.getDecoder().decode("H4sIAAAAAAACA2NjYGRkVl6+q4cTSDMyMKYYiaSwX+JkZGBg/Mq80g0AQ1Ny6x8AAAA=")
        assertEquals(
            listOf(
                PmEntry(0, 0, 100, 1),
                PmEntry(1, 100, 50, 1),
                PmEntry(2, 150, 20, 3),
                PmEntry(5, 0, 100, 1),
                PmEntry(40, 500, 7, 0),
                PmEntry(19_078_479, 9000, 1234, 1),
            ),
            PmTiles.parseDirectory(raw, PmTiles.COMPRESSION_GZIP),
        )
    }

    @Test
    fun `find honours run lengths and leaf pointers`() {
        val dir = listOf(PmEntry(0, 0, 10, 1), PmEntry(2, 10, 5, 3), PmEntry(10, 0, 40, 0))
        assertEquals(dir[0], PmTiles.find(dir, 0))
        assertNull(PmTiles.find(dir, 1))
        assertEquals(dir[1], PmTiles.find(dir, 4))
        assertNull(PmTiles.find(dir, 5))
        assertEquals(dir[2], PmTiles.find(dir, 12)) // in the leaf
    }

    @Test
    fun `reads the header`() {
        val h = PmTiles.parseHeader(header(rootOffset = 127, rootLength = 20, leafOffset = 147, leafLength = 30, dataOffset = 177, dataLength = 500, minZoom = 0, maxZoom = 15))
        assertEquals(127L, h.rootOffset)
        assertEquals(177L, h.dataOffset)
        assertEquals(15, h.maxZoom)
        assertEquals(PmTiles.COMPRESSION_NONE, h.internalCompression)
        assertEquals(5.5, h.minLon, 1e-7)
        assertEquals(44.25, h.maxLat, 1e-7)
    }

    @Test
    fun `locates tiles across the root and a leaf, then reads them`() = runBlocking {
        val archive = RemoteArchive.open(fakeArchive.reader())
        val wanted = listOf(TileKey(0, 0, 0), PmTiles.tileKey(2), PmTiles.tileKey(6), PmTiles.tileKey(11), PmTiles.tileKey(9))
        val found = archive.locate(wanted).associateBy { it.key }
        assertEquals(4, found.size) // id 9 is not in the archive
        assertEquals(PlannedTile(TileKey(0, 0, 0), 0, 4), found[TileKey(0, 0, 0)])
        assertEquals(PlannedTile(PmTiles.tileKey(2), 4, 3), found[PmTiles.tileKey(2)]) // run of tiles 1 and 2
        assertEquals(PlannedTile(PmTiles.tileKey(6), 11, 2), found[PmTiles.tileKey(6)])
        assertEquals(PlannedTile(PmTiles.tileKey(11), 13, 1), found[PmTiles.tileKey(11)]) // run of 10 to 12

        val range = RangePlan.plan(found.values).single()
        assertArrayEquals(fakeData, archive.read(range))
    }

    @Test
    fun `lists a whole archive by zoom`() = runBlocking {
        val archive = RemoteArchive.open(fakeArchive.reader())
        assertEquals(1 + 2 + 5, archive.all(0..2).size)
        assertEquals(listOf(5L, 6L, 10L, 11L, 12L), archive.all(2..2).map { PmTiles.tileId(it.key) })
        assertEquals(listOf(0L), archive.all(0..0).map { PmTiles.tileId(it.key) })
    }

    private companion object {
        val fakeData = byteArrayOf(0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 3, 3, 4)

        /** z0 tile, z1 tiles 1-2 sharing bytes, then a leaf with z2 tiles 5, 6 and the run 10-12. */
        val fakeArchive: ByteArray = run {
            val leaf = encodeDir(listOf(PmEntry(5, 7, 4, 1), PmEntry(6, 11, 2, 1), PmEntry(10, 13, 1, 3)))
            val root = encodeDir(listOf(PmEntry(0, 0, 4, 1), PmEntry(1, 4, 3, 2), PmEntry(5, 0, leaf.size, 0)))
            val rootOffset = PmTiles.HEADER_LENGTH.toLong()
            val leafOffset = rootOffset + root.size
            val dataOffset = leafOffset + leaf.size
            header(rootOffset, root.size.toLong(), leafOffset, leaf.size.toLong(), dataOffset, fakeData.size.toLong(), 0, 2) +
                root + leaf + fakeData
        }

        fun ByteArray.reader() = RangeReader { offset, length ->
            copyOfRange(offset.toInt(), minOf(size.toLong(), offset + length).toInt())
        }

        fun encodeDir(entries: List<PmEntry>): ByteArray {
            val out = ByteArrayOutputStream()
            fun v(x: Long) {
                var n = x
                while (n >= 0x80) {
                    out.write(((n and 0x7f) or 0x80).toInt())
                    n = n ushr 7
                }
                out.write(n.toInt())
            }
            v(entries.size.toLong())
            var last = 0L
            entries.forEach {
                v(it.tileId - last)
                last = it.tileId
            }
            entries.forEach { v(it.runLength.toLong()) }
            entries.forEach { v(it.length.toLong()) }
            entries.forEachIndexed { i, e ->
                val prev = entries.getOrNull(i - 1)
                v(if (prev != null && e.offset == prev.offset + prev.length) 0 else e.offset + 1)
            }
            return out.toByteArray()
        }

        fun header(
            rootOffset: Long,
            rootLength: Long,
            leafOffset: Long,
            leafLength: Long,
            dataOffset: Long,
            dataLength: Long,
            minZoom: Int,
            maxZoom: Int,
        ): ByteArray {
            val b = ByteBuffer.allocate(PmTiles.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            b.put("PMTiles".toByteArray()).put(3)
            b.putLong(rootOffset).putLong(rootLength).putLong(0).putLong(0)
            b.putLong(leafOffset).putLong(leafLength).putLong(dataOffset).putLong(dataLength)
            b.putLong(0).putLong(0).putLong(0)
            b.put(1).put(PmTiles.COMPRESSION_NONE.toByte()).put(PmTiles.COMPRESSION_GZIP.toByte()).put(PmTiles.TYPE_MVT.toByte())
            b.put(minZoom.toByte()).put(maxZoom.toByte())
            b.putInt(55_000_000).putInt(430_000_000).putInt(77_000_000).putInt(442_500_000)
            return b.array()
        }
    }
}
