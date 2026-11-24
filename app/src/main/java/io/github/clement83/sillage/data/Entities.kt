package io.github.clement83.sillage.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.clement83.sillage.geo.Fix

enum class RecordingStatus { RECORDING, PAUSED, FINISHED }

@Entity(tableName = "recording")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UTC epoch ms. */
    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** UTC epoch ms of the end, null while the recording is open. */
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val status: RecordingStatus = RecordingStatus.RECORDING,
    /** Time spent recording, pauses and interruptions excluded. */
    @ColumnInfo(name = "active_ms") val activeMs: Long = 0,
    @ColumnInfo(name = "distance_m") val distanceM: Double = 0.0,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double = 0.0,
    @ColumnInfo(name = "point_count") val pointCount: Int = 0,
    val title: String? = null,
    /** Imported track shown while recording, if any (no foreign key: deleting it just stops showing it). */
    @ColumnInfo(name = "followed_track_id") val followedTrackId: Long? = null,
    /** singletrack outing ridden ([OutingEntity.id]), set when its route is followed (#1727, #1731). */
    @ColumnInfo(name = "outing_id") val outingId: Long? = null,
    /** Upload of the ride to [outingId] (#1731); null = never asked. */
    @ColumnInfo(name = "upload_state") val uploadState: UploadState? = null,
    /** singletrack's error code (or `offline`, `unauthorized`, `not_found`) of the last failed upload. */
    @ColumnInfo(name = "upload_error") val uploadError: String? = null,
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long? = null,
)

enum class UploadState { QUEUED, SENT, FAILED, CANCELLED }

@Entity(
    tableName = "track_point",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recording_id")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    /** Segment index: a new one starts after each pause or interruption. */
    val segment: Int,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val accuracy: Float?,
    val speed: Float?,
) {
    fun toFix() = Fix(time = time, lat = lat, lon = lon, alt = alt, accuracy = accuracy, speed = speed)

    companion object {
        fun of(recordingId: Long, segment: Int, fix: Fix) = TrackPointEntity(
            recordingId = recordingId,
            segment = segment,
            time = fix.time,
            lat = fix.lat,
            lon = fix.lon,
            alt = fix.alt,
            accuracy = fix.accuracy,
            speed = fix.speed,
        )
    }
}

@Entity(
    tableName = "key_moment",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recording_id")],
)
data class KeyMomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    /** UTC epoch ms of the tap (not of the fix that placed it). */
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    /** Name given afterwards; null = "Key moment N" in lists and exports. */
    val label: String? = null,
    /** Free category for later (photo, crash, view…); exported as GPX `<type>`. */
    val type: String? = null,
)

/** A track imported from a GPX / KML / KMZ file, to follow while recording (#1742). */
@Entity(tableName = "imported_track")
data class ImportedTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double,
    @ColumnInfo(name = "point_count") val pointCount: Int,
    /** Route of a singletrack outing ([OutingEntity.id]): not listed with the user's own imports. */
    @ColumnInfo(name = "outing_id") val outingId: Long? = null,
)

/**
 * A singletrack outing opened in the app (#1727) or listed for the paired account (#1728), kept to be
 * read offline. The server's JSON is stored as received and decoded on read, so a new server field
 * needs no migration.
 */
@Entity(tableName = "outing", indices = [Index(value = ["server", "remote_id"], unique = true)])
data class OutingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Base URL of the singletrack server it comes from. */
    val server: String,
    /** The outing's id on that server. */
    @ColumnInfo(name = "remote_id") val remoteId: String,
    /** Organiser only: the `/s/<slug>` of the outing. */
    val slug: String? = null,
    /** Personal link (`/i/<token>`) it was opened with: refresh, "see on the website" and upload without an account. */
    @ColumnInfo(name = "invite_token") val inviteToken: String? = null,
    /** Listed in the paired account's outings. */
    @ColumnInfo(name = "from_account") val fromAccount: Boolean = false,
    val title: String,
    /** UTC epoch ms. */
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    /** [io.github.clement83.sillage.singletrack.Outing] JSON as last received. */
    val json: String,
    /** [json] is a full detail (route, participants), not just a list entry. */
    @ColumnInfo(name = "has_detail") val hasDetail: Boolean,
    /** UTC epoch ms of the last successful fetch. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    /** Imported track holding the route, created the first time it is followed. */
    @ColumnInfo(name = "track_id") val trackId: Long? = null,
)

@Entity(
    tableName = "imported_point",
    foreignKeys = [
        ForeignKey(
            entity = ImportedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("track_id")],
)
data class ImportedPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val segment: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
)

@Entity(
    tableName = "imported_waypoint",
    foreignKeys = [
        ForeignKey(
            entity = ImportedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("track_id")],
)
data class ImportedWaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
)

enum class ZoneKind { REGION, OUTING }

enum class ZoneState { QUEUED, DOWNLOADING, READY, FAILED }

/**
 * A downloaded offline map area (#1729): a whole region of the server's catalogue, or the corridor
 * around an outing's route. Its tiles live in the offline MBTiles files, not in this database.
 */
@Entity(tableName = "offline_zone")
data class OfflineZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ZoneKind,
    val name: String,
    val server: String,
    @ColumnInfo(name = "region_id") val regionId: String,
    /** Pack version of the tiles on the phone; the catalogue's newer one means an update. */
    val version: String,
    /** Local outing whose route gives the corridor ([ZoneKind.OUTING]). */
    @ColumnInfo(name = "outing_id") val outingId: Long? = null,
    val state: ZoneState,
    @ColumnInfo(name = "done_tiles") val doneTiles: Int = 0,
    @ColumnInfo(name = "total_tiles") val totalTiles: Int = 0,
    val bytes: Long = 0,
    val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
