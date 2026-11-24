package io.github.clement83.sillage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Insert
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Transaction
    suspend fun appendPoints(points: List<TrackPointEntity>, recording: RecordingEntity) {
        if (points.isNotEmpty()) insertPoints(points)
        update(recording)
    }

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Query("SELECT * FROM recording WHERE id = :id")
    fun observe(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recording WHERE status = 'FINISHED' ORDER BY started_at DESC")
    fun observeFinished(): Flow<List<RecordingEntity>>

    /** The recording left open by a killed process or a reboot, if any. */
    @Query("SELECT * FROM recording WHERE status != 'FINISHED' ORDER BY started_at DESC LIMIT 1")
    suspend fun findOpen(): RecordingEntity?

    @Query("SELECT * FROM recording WHERE status != 'FINISHED' ORDER BY started_at DESC LIMIT 1")
    fun observeOpen(): Flow<RecordingEntity?>

    @Query("SELECT * FROM track_point WHERE recording_id = :recordingId ORDER BY time")
    suspend fun points(recordingId: Long): List<TrackPointEntity>

    @Query("SELECT MAX(time) FROM track_point WHERE recording_id = :recordingId")
    suspend fun lastPointTime(recordingId: Long): Long?

    /** Upload of the ride to its outing (#1731). [state] is an [UploadState] name, or null. */
    @Query("UPDATE recording SET upload_state = :state, upload_error = :error, uploaded_at = :uploadedAt WHERE id = :id")
    suspend fun setUpload(id: Long, state: String?, error: String? = null, uploadedAt: Long? = null)

    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertKeyMoment(moment: KeyMomentEntity): Long

    @Query("SELECT * FROM key_moment WHERE recording_id = :recordingId ORDER BY time")
    suspend fun keyMoments(recordingId: Long): List<KeyMomentEntity>

    @Query("SELECT * FROM key_moment WHERE recording_id = :recordingId ORDER BY time")
    fun observeKeyMoments(recordingId: Long): Flow<List<KeyMomentEntity>>

    @Query("UPDATE key_moment SET label = :label WHERE id = :id")
    suspend fun renameKeyMoment(id: Long, label: String?)

    @Query("DELETE FROM key_moment WHERE id = :id")
    suspend fun deleteKeyMoment(id: Long)
}
