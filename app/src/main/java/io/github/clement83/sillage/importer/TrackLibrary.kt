package io.github.clement83.sillage.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.clement83.sillage.data.ImportedPointEntity
import io.github.clement83.sillage.data.ImportedTrackDao
import io.github.clement83.sillage.data.ImportedTrackEntity
import io.github.clement83.sillage.data.ImportedWaypointEntity
import io.github.clement83.sillage.geo.GeoPoint
import io.github.clement83.sillage.geo.TrackShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** A planned track ready to draw: its segments and waypoints. */
data class TrackGeometry(val segments: List<List<GeoPoint>>, val waypoints: List<ImportedWaypointEntity>)

sealed interface ImportOutcome {
    data class Imported(val trackId: Long) : ImportOutcome
    data class Failed(val reason: ImportException.Reason?) : ImportOutcome
}

/** Imported tracks: files opened with or shared to Sillage, or picked in the Tracks screen. */
class TrackLibrary(private val context: Context, private val dao: ImportedTrackDao) {

    private val _lastImport = MutableStateFlow<ImportOutcome?>(null)

    /** The latest import, until the UI has shown it ([consumeImport]). */
    val lastImport: StateFlow<ImportOutcome?> = _lastImport.asStateFlow()

    fun consumeImport() {
        _lastImport.value = null
    }

    suspend fun import(uri: Uri): ImportOutcome {
        val outcome = try {
            ImportOutcome.Imported(save(read(uri), displayName(uri)))
        } catch (e: ImportException) {
            ImportOutcome.Failed(e.reason)
        } catch (e: Exception) {
            ImportOutcome.Failed(null)
        }
        _lastImport.value = outcome
        return outcome
    }

    suspend fun geometry(trackId: Long): TrackGeometry {
        val segments = dao.points(trackId).groupBy { it.segment }.toSortedMap().values
            .map { seg -> seg.map { GeoPoint(it.lat, it.lon, it.alt) } }
        return TrackGeometry(segments, dao.waypoints(trackId))
    }

    private suspend fun read(uri: Uri): ParsedTrack = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > TrackParser.MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
            }
            buffer.toByteArray()
        } ?: throw java.io.IOException("Cannot open $uri")
        TrackParser.parse(bytes)
    }

    private suspend fun save(track: ParsedTrack, fileName: String?): Long {
        val segments = track.segments.map { seg -> seg.map { GeoPoint(it.lat, it.lon, it.alt) } }
        val entity = ImportedTrackEntity(
            name = track.name ?: fileName?.substringBeforeLast('.') ?: "Track",
            importedAt = System.currentTimeMillis(),
            distanceM = TrackShape.distanceM(segments),
            elevationGainM = TrackShape.elevationGainM(segments),
            pointCount = track.pointCount,
        )
        return dao.insertAll(
            entity,
            points = { id ->
                track.segments.flatMapIndexed { index, seg ->
                    seg.map { ImportedPointEntity(trackId = id, segment = index, lat = it.lat, lon = it.lon, alt = it.alt) }
                }
            },
            waypoints = { id ->
                track.waypoints.map { ImportedWaypointEntity(trackId = id, name = it.name, lat = it.lat, lon = it.lon, alt = it.alt) }
            },
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}
