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
        OfflineZoneEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class SillageDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun tracks(): ImportedTrackDao
    abstract fun outings(): OutingDao
    abstract fun offlineZones(): OfflineZoneDao

    companion object {
        fun create(context: Context): SillageDatabase =
            Room.databaseBuilder(context.applicationContext, SillageDatabase::class.java, "sillage.db").build()
    }
}
