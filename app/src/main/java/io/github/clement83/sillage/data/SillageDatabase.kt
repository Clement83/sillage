package io.github.clement83.sillage.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingEntity::class,
        TrackPointEntity::class,
        KeyMomentEntity::class,
        ImportedTrackEntity::class,
        ImportedPointEntity::class,
        ImportedWaypointEntity::class,
        OutingEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class SillageDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun tracks(): ImportedTrackDao
    abstract fun outings(): OutingDao

    companion object {
        fun create(context: Context): SillageDatabase =
            Room.databaseBuilder(context.applicationContext, SillageDatabase::class.java, "sillage.db").build()
    }
}
