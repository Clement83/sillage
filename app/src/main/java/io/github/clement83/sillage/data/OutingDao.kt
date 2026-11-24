package io.github.clement83.sillage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OutingDao {

    @Insert
    suspend fun insert(outing: OutingEntity): Long

    @Update
    suspend fun update(outing: OutingEntity)

    @Query("SELECT * FROM outing WHERE id = :id")
    suspend fun get(id: Long): OutingEntity?

    @Query("SELECT * FROM outing WHERE id = :id")
    fun observe(id: Long): Flow<OutingEntity?>

    @Query("SELECT * FROM outing ORDER BY starts_at")
    fun observeAll(): Flow<List<OutingEntity>>

    @Query("SELECT * FROM outing WHERE server = :server AND remote_id = :remoteId")
    suspend fun find(server: String, remoteId: String): OutingEntity?

    @Query("SELECT * FROM outing WHERE server = :server AND invite_token = :token LIMIT 1")
    suspend fun findByInvite(server: String, token: String): OutingEntity?

    @Query("SELECT * FROM outing WHERE server = :server AND slug = :slug LIMIT 1")
    suspend fun findBySlug(server: String, slug: String): OutingEntity?

    @Query("SELECT * FROM outing WHERE from_account = 1")
    suspend fun fromAccount(): List<OutingEntity>

    @Query("DELETE FROM outing WHERE id = :id")
    suspend fun delete(id: Long)
}
