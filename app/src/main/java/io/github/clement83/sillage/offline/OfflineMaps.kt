package io.github.clement83.sillage.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.clement83.sillage.data.OfflineZoneDao
import io.github.clement83.sillage.data.OfflineZoneEntity
import io.github.clement83.sillage.data.ZoneKind
import io.github.clement83.sillage.data.ZoneState
import io.github.clement83.sillage.geo.GeoPoint
import io.github.clement83.sillage.map.MapStyles
import io.github.clement83.sillage.settings.SettingsRepository
import io.github.clement83.sillage.singletrack.AccountStore
import io.github.clement83.sillage.singletrack.ApiResult
import io.github.clement83.sillage.singletrack.OutingRepository
import io.github.clement83.sillage.singletrack.SingletrackClient
import io.github.clement83.sillage.singletrack.decode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** What the paired server offers for offline maps, as far as the app knows. */
sealed interface CatalogState {
    /** No paired account: offline maps are hidden. */
    data object NoAccount : CatalogState

    /** Could not ask the server (no network, server down). */
    data object Unreachable : CatalogState

    data class Ready(val server: String, val catalog: OfflineCatalog) : CatalogState
}

/**
 * Offline maps (#1729): zones (a region, or the corridor of an outing) copied tile by tile from
 * the paired server's PMTiles packs into local MBTiles files the map reads without network, plus
 * the relief tiles named by the server's offline style. Downloads run in WorkManager, Wi-Fi only
 * unless the user allows mobile data, and resume where they stopped.
 */
class OfflineMaps(
    private val context: Context,
    private val dao: OfflineZoneDao,
    private val accounts: AccountStore,
    private val client: SingletrackClient,
    private val outings: OutingRepository,
    private val settings: SettingsRepository,
) {
    private val dir = File(context.noBackupFilesDir, "offline")
    private val map = TileStore(File(dir, "map.mbtiles"), "pbf")
    private val relief = TileStore(File(dir, "relief.mbtiles"), "png")
    private val styleFile = File(dir, "style.json")

    /** Vector map tiles on the phone (XYZ), for the trails the track snaps to (#1796). */
    fun mapTile(z: Int, x: Int, y: Int): ByteArray? = map.tile(z, x, y)

    /** BRouter segments for routing on the phone (#1733), shared by the zones that need them. */
    val segmentsDir = File(dir, "segments")
    private val segmentLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Bumped whenever the stored tiles change, so that maps reload them. */
    private val revision = MutableStateFlow(0)
    private val catalogLock = Mutex()
    private var cachedCatalog: Pair<Long, CatalogState.Ready>? = null

    val zones: Flow<List<OfflineZoneEntity>> = dao.observeAll()

    fun zoneForOuting(outingId: Long): Flow<OfflineZoneEntity?> = dao.observeForOuting(outingId)

    /** The base map style: OpenTopoMap alone until something is downloaded. */
    val baseStyle: Flow<String> = combine(revision, settings.offlineFirst.distinctUntilChanged()) { r, first -> r to first }
        .distinctUntilChanged()
        .map { (_, first) -> withContext(Dispatchers.IO) { buildStyle(first) } }

    private fun buildStyle(offlineFirst: Boolean): String {
        if (!map.hasTiles()) return MapStyles.OPEN_TOPO_MAP
        val reliefUrl = relief.url.takeIf { relief.hasTiles() }
        val style = styleFile.takeIf { it.exists() }?.readText()
        return OfflineStyle.compose(style, map.url, reliefUrl, offlineFirst)
    }

    /** Space used on the phone by all the zones. */
    suspend fun sizeOnDisk(): Long = withContext(Dispatchers.IO) {
        map.sizeOnDisk() + relief.sizeOnDisk() + (segmentsDir.listFiles()?.sumOf { it.length() } ?: 0L)
    }

    /** The server's catalogue, kept a few minutes. */
    suspend fun catalog(force: Boolean = false): CatalogState = catalogLock.withLock {
        val account = accounts.current() ?: return CatalogState.NoAccount
        cachedCatalog?.let { (at, state) ->
            if (!force && state.server == account.server && System.currentTimeMillis() - at < CATALOG_TTL_MS) return state
        }
        when (val r = client.offlineCatalog(account.server, account.token)) {
            is ApiResult.Ok -> CatalogState.Ready(account.server, r.value).also { cachedCatalog = System.currentTimeMillis() to it }
            ApiResult.Unauthorized -> {
                outings.onUnauthorized()
                CatalogState.NoAccount
            }
            else -> CatalogState.Unreachable
        }
    }

    /** The catalogue region that covers [outingId]'s route best, or null. */
    suspend fun regionFor(catalog: OfflineCatalog, outingId: Long): OfflineRegion? {
        val bounds = route(outingId)?.let { TileMath.bounds(it) } ?: return null
        return catalog.regions.firstOrNull { TileMath.contains(it.bbox, bounds) }
            ?: catalog.regions.firstOrNull { it.bbox.size == 4 && intersects(it.bbox, bounds) }
    }

    /**
     * About how many bytes the download of [region] (or [outingId]'s corridor in it) costs. The
     * corridor is measured exactly from the archive's directories (a few hundred KB read); the
     * relief is an estimate.
     */
    suspend fun estimate(region: OfflineRegion, outingId: Long?): Long? = withContext(Dispatchers.IO) {
        val account = accounts.current() ?: return@withContext null
        val reliefTiles: Int
        val mapBytes: Long
        val route = outingId?.let { route(it) ?: return@withContext null }
        if (outingId == null) {
            mapBytes = region.pmtilesSize
            reliefTiles = if (region.bbox.size == 4) {
                TileMath.boxTiles(region.bbox[0], region.bbox[1], region.bbox[2], region.bbox[3], RELIEF_ZOOMS).size
            } else {
                0
            }
        } else {
            route!!
            val archive = try {
                RemoteArchive.open(reader(account.server, account.token, region.id))
            } catch (_: DownloadException) {
                return@withContext null
            } catch (_: IOException) {
                return@withContext null
            }
            val tiles = try {
                archive.locate(corridorKeys(route, archive.header.minZoom, min(archive.header.maxZoom, MAX_ZOOM)))
            } catch (_: DownloadException) {
                return@withContext null
            }
            mapBytes = tiles.distinctBy { it.offset }.sumOf { it.length.toLong() }
            reliefTiles = TileMath.corridorTiles(route, CORRIDOR_M, RELIEF_ZOOMS).size
        }
        mapBytes + reliefTiles * RELIEF_TILE_BYTES
    }

    /** Bytes still to download for [region]'s routing data. */
    suspend fun estimateRouting(region: OfflineRegion): Long = withContext(Dispatchers.IO) {
        region.segments.filterNot { isPresent(it) }.sumOf { it.size }
    }

    /** Starts downloading [region]'s routing data (#1733), apart from its map. */
    suspend fun downloadRouting(server: String, region: OfflineRegion, name: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            OfflineZoneEntity(
                kind = ZoneKind.ROUTING,
                name = name,
                server = server,
                regionId = region.id,
                version = region.version,
                state = ZoneState.QUEUED,
                segments = region.segments.joinToString(",") { segmentName(it) },
                createdAt = now,
                updatedAt = now,
            ),
        )
        enqueue(id)
        return id
    }

    /** Starts downloading [region], or [outingId]'s corridor in it. */
    suspend fun download(server: String, region: OfflineRegion, outingId: Long?, name: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            OfflineZoneEntity(
                kind = if (outingId == null) ZoneKind.REGION else ZoneKind.OUTING,
                name = name,
                server = server,
                regionId = region.id,
                version = region.version,
                outingId = outingId,
                state = ZoneState.QUEUED,
                createdAt = now,
                updatedAt = now,
            ),
        )
        enqueue(id)
        return id
    }

    /** Downloads [zone] again from the newer pack. */
    suspend fun update(zone: OfflineZoneEntity) {
        withContext(Dispatchers.IO) {
            map.restart(zone.id)
            relief.restart(zone.id)
        }
        dao.setProgress(zone.id, ZoneState.QUEUED, 0, zone.totalTiles, null, System.currentTimeMillis())
        enqueue(zone.id)
    }

    /** Tries a failed or waiting download again, from where it stopped. */
    suspend fun retry(zone: OfflineZoneEntity) {
        dao.setProgress(zone.id, ZoneState.QUEUED, zone.doneTiles, zone.totalTiles, null, System.currentTimeMillis())
        enqueue(zone.id)
    }

    /** Stops [zone]'s download if any and frees its space. */
    suspend fun delete(zone: OfflineZoneEntity) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(zone.id))
        dao.delete(zone.id)
        withContext(Dispatchers.IO) {
            map.delete(zone.id)
            relief.delete(zone.id)
        }
        pruneSegments()
        revision.value++
    }

    /** Re-queues the downloads still pending with the current network preference. */
    suspend fun requeuePending() {
        dao.all().filter { it.state == ZoneState.QUEUED || it.state == ZoneState.DOWNLOADING }.forEach { enqueue(it.id) }
    }

    private suspend fun enqueue(zoneId: Long) {
        val mobile = settings.offlineMobileData.first()
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf(OfflineDownloadWorker.KEY_ZONE to zoneId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(zoneId), ExistingWorkPolicy.REPLACE, request)
    }

    /** The download itself, run by [OfflineDownloadWorker]. True when finished or given up, false to retry later. */
    suspend fun run(zoneId: Long, onProgress: suspend (name: String, done: Int, total: Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val zone = dao.get(zoneId) ?: return@withContext true
        val account = accounts.current()
        if (account == null || account.server != zone.server) return@withContext fail(zone, ERROR_NO_ACCOUNT)
        try {
            val catalog = when (val r = client.offlineCatalog(zone.server, account.token)) {
                is ApiResult.Ok -> r.value
                else -> throw DownloadException.of(r)
            }
            val region = catalog.regions.firstOrNull { it.id == zone.regionId } ?: throw DownloadException(ERROR_GONE)
            setState(zone, ZoneState.DOWNLOADING, null)
            if (zone.kind == ZoneKind.ROUTING) return@withContext runRouting(zone, account.token, region, onProgress)

            // The style first: it names the relief tiles.
            val style = when (val r = client.offlineStyle(zone.server, account.token, region.id)) {
                is ApiResult.Ok -> r.value.takeIf { s -> runCatching { json.parseToJsonElement(s).jsonObject }.isSuccess }
                    ?: throw DownloadException(ERROR_OTHER)
                else -> throw DownloadException.of(r)
            }
            val reliefTemplate = reliefTemplate(style)

            val archive = RemoteArchive.open(reader(zone.server, account.token, region.id))
            val minZ = archive.header.minZoom
            val maxZ = min(archive.header.maxZoom, MAX_ZOOM)
            val route = if (zone.kind == ZoneKind.OUTING) {
                zone.outingId?.let { route(it) } ?: throw DownloadException(ERROR_NO_ROUTE)
            } else {
                null
            }
            val regionTiles = if (route == null) archive.all(minZ..maxZ) else null
            map.plan(zone.id, route?.let { corridorKeys(it, minZ, maxZ) } ?: regionTiles!!.map { it.key })
            map.setMetadata(minZ, maxZ, catalog.attribution)
            if (reliefTemplate != null) {
                val keys = route?.let { TileMath.corridorTiles(it, CORRIDOR_M, RELIEF_ZOOMS) }
                    ?: region.bbox.takeIf { it.size == 4 }?.let { TileMath.boxTiles(it[0], it[1], it[2], it[3], RELIEF_ZOOMS) }
                    ?: emptySet()
                relief.plan(zone.id, keys)
                relief.setMetadata(RELIEF_ZOOMS.first, RELIEF_ZOOMS.last, null)
            }

            var lastReport = 0L
            suspend fun report(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastReport < REPORT_MS) return
                lastReport = now
                val (md, mt) = map.progress(zone.id)
                val (rd, rt) = relief.progress(zone.id)
                dao.setProgress(zone.id, ZoneState.DOWNLOADING, md + rd, mt + rt, null, now)
                onProgress(zone.name, md + rd, mt + rt)
            }
            report(force = true)

            // Vector tiles: grouped into large ranges of the archive.
            val pending = map.pending(zone.id).toHashSet()
            val planned = regionTiles?.filter { it.key in pending } ?: archive.locate(pending)
            map.put(zone.id, emptyMap(), pending - planned.mapTo(HashSet()) { it.key })
            for (range in RangePlan.plan(planned)) {
                currentCoroutineContext().ensureActive()
                val bytes = archive.read(range)
                val tiles = HashMap<TileKey, ByteArray>(range.entries.size)
                for (t in range.entries) {
                    val from = (t.offset - range.offset).toInt()
                    if (from + t.length <= bytes.size) tiles[t.key] = bytes.copyOfRange(from, from + t.length)
                }
                if (dao.get(zone.id) == null) return@withContext true // deleted meanwhile
                map.put(zone.id, tiles)
                report()
            }

            // Relief: one public PNG per tile, a few at a time.
            if (reliefTemplate != null) {
                for (batch in relief.pending(zone.id).chunked(RELIEF_PARALLEL)) {
                    currentCoroutineContext().ensureActive()
                    val results = coroutineScope {
                        batch.map { k -> async { k to client.download(tileUrl(reliefTemplate, k)) } }.awaitAll()
                    }
                    val got = HashMap<TileKey, ByteArray>()
                    val missing = ArrayList<TileKey>()
                    for ((k, r) in results) {
                        when (r) {
                            is ApiResult.Ok -> got[k] = r.value
                            ApiResult.NotFound -> missing += k
                            else -> throw DownloadException(ERROR_OFFLINE)
                        }
                    }
                    if (dao.get(zone.id) == null) return@withContext true
                    relief.put(zone.id, got, missing)
                    report()
                }
            }

            styleFile.parentFile?.mkdirs()
            val tmp = File(styleFile.path + ".tmp")
            tmp.writeText(style)
            tmp.renameTo(styleFile)
            val (md, mt) = map.progress(zone.id)
            val (rd, rt) = relief.progress(zone.id)
            dao.get(zone.id)?.let {
                dao.update(
                    it.copy(
                        state = ZoneState.READY,
                        version = region.version,
                        doneTiles = md + rd,
                        totalTiles = mt + rt,
                        bytes = map.bytes(zone.id) + relief.bytes(zone.id),
                        error = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            revision.value++
            true
        } catch (e: DownloadException) {
            when (e.code) {
                ERROR_OFFLINE -> {
                    setState(zone, ZoneState.QUEUED, ERROR_OFFLINE)
                    false
                }
                ERROR_UNAUTHORIZED -> {
                    outings.onUnauthorized()
                    fail(zone, ERROR_UNAUTHORIZED)
                }
                else -> fail(zone, e.code)
            }
        } catch (_: IOException) {
            // Not a PMTiles archive, or corrupt: retrying would not help.
            fail(zone, ERROR_OTHER)
        }
    }

    /**
     * A routing zone (#1733): the region's BRouter segments, large files fetched by chunks and
     * resumable, progress counted in chunks.
     */
    private suspend fun runRouting(
        zone: OfflineZoneEntity,
        token: String,
        region: OfflineRegion,
        onProgress: suspend (name: String, done: Int, total: Int) -> Unit,
    ): Boolean {
        val segments = region.segments
        val total = segments.sumOf { chunks(it.size) }
        var done = segments.sumOf { s -> if (isPresent(s)) chunks(s.size) else chunks(partLength(s)) }
        dao.get(zone.id)?.let { dao.update(it.copy(segments = segments.joinToString(",") { s -> segmentName(s) })) }
        var lastReport = 0L
        suspend fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastReport < REPORT_MS) return
            lastReport = now
            dao.setProgress(zone.id, ZoneState.DOWNLOADING, done, total, null, now)
            onProgress(zone.name, done, total)
        }
        report(force = true)
        for (segment in segments) {
            val finished = downloadSegment(zone.server, token, segment) {
                if (dao.get(zone.id) == null) return@downloadSegment false
                done++
                report()
                true
            }
            if (!finished) return true // deleted meanwhile
        }
        dao.get(zone.id)?.let {
            dao.update(
                it.copy(
                    state = ZoneState.READY,
                    version = region.version,
                    doneTiles = total,
                    totalTiles = total,
                    bytes = segments.sumOf { s -> s.size },
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return true
    }

    private fun segmentName(s: OfflineSegment) = s.name.removeSuffix(".rd5")
    private fun segmentFile(s: OfflineSegment) = File(segmentsDir, "${segmentName(s)}.rd5")
    private fun shaFile(s: OfflineSegment) = File(segmentsDir, "${segmentName(s)}.sha256")
    private fun partFile(s: OfflineSegment) = File(segmentsDir, "${segmentName(s)}.rd5.part")
    private fun partShaFile(s: OfflineSegment) = File(segmentsDir, "${segmentName(s)}.part.sha256")
    private fun shaOf(s: OfflineSegment) = s.sha256.orEmpty().lowercase()
    private fun chunks(bytes: Long) = ((bytes + SEGMENT_CHUNK - 1) / SEGMENT_CHUNK).toInt()

    /** Already on the phone in the catalogue's version (or of the right size, if put there by hand). */
    private fun isPresent(s: OfflineSegment) =
        segmentFile(s).length() == s.size && shaFile(s).let { !it.isFile || it.readText() == shaOf(s) }

    private fun partLength(s: OfflineSegment) =
        if (partShaFile(s).takeIf { it.isFile }?.readText() == shaOf(s)) partFile(s).length() else 0L

    /**
     * Downloads [segment] into the segments folder, resuming a `.part` of the same version. [onChunk]
     * runs after each chunk and returns false to stop (zone deleted): then false is returned.
     */
    private suspend fun downloadSegment(server: String, token: String, segment: OfflineSegment, onChunk: suspend () -> Boolean): Boolean =
        segmentLock.withLock {
            segmentsDir.mkdirs()
            val existing = segmentFile(segment)
            if (existing.length() == segment.size) {
                val known = shaFile(segment)
                if (known.isFile && known.readText() == shaOf(segment)) return@withLock true
                // There without its checksum (copied by hand): kept if it is the right file.
                if (!known.isFile && (shaOf(segment).isBlank() || sha256(existing) == shaOf(segment))) {
                    known.writeText(shaOf(segment))
                    return@withLock true
                }
            }
            val part = partFile(segment)
            if (partLength(segment) == 0L) {
                part.delete()
                partShaFile(segment).writeText(shaOf(segment))
            }
            var offset = part.length()
            FileOutputStream(part, true).use { out ->
                while (offset < segment.size) {
                    currentCoroutineContext().ensureActive()
                    val length = min(SEGMENT_CHUNK, segment.size - offset)
                    val bytes = when (val r = client.offlineSegmentRange(server, token, segmentName(segment), offset, length)) {
                        is ApiResult.Ok -> r.value
                        else -> throw DownloadException.of(r)
                    }
                    out.write(bytes)
                    out.flush()
                    offset += bytes.size
                    if (!onChunk()) return@withLock false
                }
            }
            if (shaOf(segment).isNotBlank() && sha256(part) != shaOf(segment)) {
                part.delete()
                partShaFile(segment).delete()
                throw DownloadException(ERROR_OTHER)
            }
            val file = segmentFile(segment)
            file.delete()
            if (!part.renameTo(file)) throw DownloadException(ERROR_OTHER)
            shaFile(segment).writeText(shaOf(segment))
            partShaFile(segment).delete()
            true
        }

    /** Deletes the segments no zone needs any more. */
    private suspend fun pruneSegments() = segmentLock.withLock {
        withContext(Dispatchers.IO) {
            val keep = dao.all().flatMap { it.segments.split(',') }.filter { it.isNotBlank() }.toSet()
            segmentsDir.listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in keep) f.delete() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun setState(zone: OfflineZoneEntity, state: ZoneState, error: String?) {
        val current = dao.get(zone.id) ?: return
        dao.setProgress(zone.id, state, current.doneTiles, current.totalTiles, error, System.currentTimeMillis())
    }

    private suspend fun fail(zone: OfflineZoneEntity, error: String): Boolean {
        setState(zone, ZoneState.FAILED, error)
        // Tiles already stored are kept: the map shows them, and a retry resumes.
        revision.value++
        return true
    }

    private fun reader(server: String, token: String, regionId: String) = RangeReader { offset, length ->
        when (val r = client.offlineRange(server, token, "$regionId.pmtiles", offset, length)) {
            is ApiResult.Ok -> r.value
            else -> throw DownloadException.of(r)
        }
    }

    private suspend fun route(outingId: Long): List<List<GeoPoint>>? =
        outings.observe(outingId).first()?.decode()?.track?.segments()?.takeIf { s -> s.any { it.size >= 2 } }

    private fun corridorKeys(route: List<List<GeoPoint>>, minZ: Int, maxZ: Int): Set<TileKey> {
        val out = LinkedHashSet<TileKey>()
        val b = TileMath.bounds(route) ?: return out
        if (minZ <= LOW_ZOOM_MAX) out += TileMath.boxTiles(b[0], b[1], b[2], b[3], minZ..min(LOW_ZOOM_MAX, maxZ))
        if (maxZ > LOW_ZOOM_MAX) out += TileMath.corridorTiles(route, CORRIDOR_M, max(minZ, LOW_ZOOM_MAX + 1)..maxZ)
        return out
    }

    /** The relief tile URL template of the server's style (`raster-dem` source), if any. */
    private fun reliefTemplate(style: String): String? = runCatching {
        val sources = json.parseToJsonElement(style).jsonObject["sources"]!!.jsonObject
        sources.values.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "raster-dem" }
            ?.get("tiles")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            ?.takeIf { it.startsWith("https://") && "{z}" in it && "{x}" in it && "{y}" in it }
    }.getOrNull()

    private fun tileUrl(template: String, k: TileKey) =
        template.replace("{z}", "${k.z}").replace("{x}", "${k.x}").replace("{y}", "${k.y}")

    private fun intersects(b: List<Double>, a: DoubleArray) = a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]

    private class DownloadException(val code: String) : Exception(code) {
        companion object {
            fun of(r: ApiResult<*>) = DownloadException(
                when (r) {
                    ApiResult.Unauthorized -> ERROR_UNAUTHORIZED
                    ApiResult.NotFound -> ERROR_GONE
                    is ApiResult.Failed -> if (r.status >= 500) ERROR_OFFLINE else ERROR_OTHER
                    else -> ERROR_OFFLINE // offline, timeout, 429 (quota): later
                },
            )
        }
    }

    companion object {
        const val ERROR_OFFLINE = "offline"
        const val ERROR_GONE = "gone"
        const val ERROR_UNAUTHORIZED = "unauthorized"
        const val ERROR_NO_ACCOUNT = "no_account"
        const val ERROR_NO_ROUTE = "no_route"
        const val ERROR_OTHER = "other"

        /** Half-width of an outing's corridor. */
        const val CORRIDOR_M = 2_000.0

        /** Highest zoom copied; the map draws deeper zooms from it. */
        const val MAX_ZOOM = 16

        /** Up to this zoom an outing gets its whole bounding box (a handful of tiles), above only the corridor. */
        const val LOW_ZOOM_MAX = 9

        /** Relief zooms: enough for a soft hillshade, ~80 KB a tile. */
        val RELIEF_ZOOMS = 5..11
        const val RELIEF_TILE_BYTES = 80_000L
        private const val RELIEF_PARALLEL = 4

        /** Segments come by 4 MB requests (a segment is 60–240 MB). */
        private const val SEGMENT_CHUNK = 4L * 1024 * 1024

        private const val CATALOG_TTL_MS = 5 * 60_000L
        private const val REPORT_MS = 1_000L

        fun workName(zoneId: Long) = "offline-zone-$zoneId"
    }
}
