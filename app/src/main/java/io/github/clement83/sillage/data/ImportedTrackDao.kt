package io.github.clement83.sillage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedTrackDao {

    @Insert
    suspend fun insertTrack(track: ImportedTrackEntity): Long

    @Insert
    suspend fun insertPoints(points: List<ImportedPointEntity>)

    @Insert
    suspend fun insertWaypoints(waypoints: List<ImportedWaypointEntity>)

    @Transaction
    suspend fun insertAll(
        track: ImportedTrackEntity,
        points: (Long) -> List<ImportedPointEntity>,
        waypoints: (Long) -> List<ImportedWaypointEntity>,
    ): Long {
        val id = insertTrack(track)
        insertPoints(points(id))
        insertWaypoints(waypoints(id))
        return id
    }

    /** The user's own imports; outing routes are reached through their outing. */
    @Query("SELECT * FROM imported_track WHERE outing_id IS NULL ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<ImportedTrackEntity>>

    @Query("SELECT * FROM imported_track WHERE id = :id")
    fun observe(id: Long): Flow<ImportedTrackEntity?>

    @Query("SELECT * FROM imported_track WHERE id = :id")
    suspend fun get(id: Long): ImportedTrackEntity?

    @Query("SELECT * FROM imported_point WHERE track_id = :trackId ORDER BY id")
    suspend fun points(trackId: Long): List<ImportedPointEntity>

    @Query("SELECT * FROM imported_waypoint WHERE track_id = :trackId ORDER BY id")
    suspend fun waypoints(trackId: Long): List<ImportedWaypointEntity>

    @Query("UPDATE imported_track SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM imported_track WHERE id = :id")
    suspend fun delete(id: Long)
}
