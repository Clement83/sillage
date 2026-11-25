package io.github.clement83.sillage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineZoneDao {

    @Insert
    suspend fun insert(zone: OfflineZoneEntity): Long

    @Update
    suspend fun update(zone: OfflineZoneEntity)

    @Query("SELECT * FROM offline_zone WHERE id = :id")
    suspend fun get(id: Long): OfflineZoneEntity?

    @Query("SELECT * FROM offline_zone ORDER BY created_at")
    fun observeAll(): Flow<List<OfflineZoneEntity>>

    @Query("SELECT * FROM offline_zone ORDER BY created_at")
    suspend fun all(): List<OfflineZoneEntity>

    @Query("SELECT * FROM offline_zone WHERE outing_id = :outingId LIMIT 1")
    fun observeForOuting(outingId: Long): Flow<OfflineZoneEntity?>

    @Query("UPDATE offline_zone SET state = :state, done_tiles = :done, total_tiles = :total, error = :error, updated_at = :at WHERE id = :id")
    suspend fun setProgress(id: Long, state: ZoneState, done: Int, total: Int, error: String?, at: Long)

    @Query("DELETE FROM offline_zone WHERE id = :id")
    suspend fun delete(id: Long)
}
