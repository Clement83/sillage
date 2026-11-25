package io.github.clement83.sillage.offline

import kotlinx.serialization.Serializable

/** `GET /api/app/offline` (#1739): the regions the paired server offers for offline use. */
@Serializable
data class OfflineCatalog(
    val enabled: Boolean = false,
    val regions: List<OfflineRegion> = emptyList(),
    val attribution: String? = null,
)

@Serializable
data class OfflineRegion(
    val id: String,
    val name: String,
    /** `[minLon, minLat, maxLon, maxLat]`. */
    val bbox: List<Double> = emptyList(),
    val pmtilesSize: Long = 0,
    val version: String = "",
    val segments: List<OfflineSegment> = emptyList(),
)

/** A BRouter segment file of the region (#1733). */
@Serializable
data class OfflineSegment(val name: String, val size: Long = 0, val sha256: String? = null)

/** Reads [length] bytes at [offset] of a remote file, or throws. */
fun interface RangeReader {
    suspend fun read(offset: Long, length: Long): ByteArray
}

/**
 * A PMTiles archive read through HTTP ranges: the header and root directory up front, leaf
 * directories on demand (kept, a region has a few of them). Finds where tiles are without
 * downloading them, so that only the wanted bytes are fetched.
 */
class RemoteArchive private constructor(
    private val reader: RangeReader,
    val header: PmHeader,
    private val root: List<PmEntry>,
) {
    private val leaves = HashMap<Long, List<PmEntry>>()

    /** Where each of [keys] lies; tiles the archive does not have (empty sea, outside) are left out. */
    suspend fun locate(keys: Collection<TileKey>): List<PlannedTile> {
        val out = ArrayList<PlannedTile>(keys.size)
        // In tile id order, consecutive keys mostly share a leaf directory.
        for ((id, key) in keys.map { PmTiles.tileId(it) to it }.sortedBy { it.first }) {
            var dir = root
            for (depth in 0 until MAX_DEPTH) {
                val e = PmTiles.find(dir, id) ?: break
                if (e.runLength > 0) {
                    if (e.length > 0) out += PlannedTile(key, e.offset, e.length)
                    break
                }
                dir = leaf(e)
            }
        }
        return out
    }

    /** Every tile of the archive within [zooms]: a whole region. */
    suspend fun all(zooms: IntRange): List<PlannedTile> {
        val first = zoomBase(zooms.first)
        val end = zoomBase(zooms.last + 1)
        val out = ArrayList<PlannedTile>()
        suspend fun walk(dir: List<PmEntry>, depth: Int) {
            for ((i, e) in dir.withIndex()) {
                // A leaf covers the ids up to the next entry: skip the ones outside the zooms.
                val next = dir.getOrNull(i + 1)?.tileId ?: Long.MAX_VALUE
                if (next <= first || e.tileId >= end) continue
                if (e.runLength == 0) {
                    if (depth < MAX_DEPTH) walk(leaf(e), depth + 1)
                } else if (e.length > 0) {
                    for (id in e.tileId until e.tileId + e.runLength) {
                        if (id in first until end) out += PlannedTile(PmTiles.tileKey(id), e.offset, e.length)
                    }
                }
            }
        }
        walk(root, 1)
        return out
    }

    private suspend fun leaf(e: PmEntry): List<PmEntry> = leaves.getOrPut(e.offset) {
        PmTiles.parseDirectory(reader.read(header.leafOffset + e.offset, e.length.toLong()), header.internalCompression)
    }

    /** Reads the bytes of [range], relative to the tile data section. */
    suspend fun read(range: RangeRequest): ByteArray = reader.read(header.dataOffset + range.offset, range.length)

    companion object {
        private const val MAX_DEPTH = 4

        suspend fun open(reader: RangeReader): RemoteArchive {
            val first = reader.read(0, PmTiles.FIRST_READ.toLong())
            val header = PmTiles.parseHeader(first)
            val rootEnd = header.rootOffset + header.rootLength
            val rootBytes = if (rootEnd <= first.size) {
                first.copyOfRange(header.rootOffset.toInt(), rootEnd.toInt())
            } else {
                reader.read(header.rootOffset, header.rootLength)
            }
            return RemoteArchive(reader, header, PmTiles.parseDirectory(rootBytes, header.internalCompression))
        }

        /** The first tile id of zoom [z]. */
        fun zoomBase(z: Int): Long = ((1L shl (2 * z)) - 1) / 3
    }
}
