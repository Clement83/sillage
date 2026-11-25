package io.github.clement83.sillage.offline

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One MBTiles file that MapLibre reads directly (`mbtiles:///…`), plus which zone needs which tile.
 * Zones overlap: a tile is stored once and deleted with the last zone that needs it. Full auto-vacuum
 * gives the space back to the phone as soon as a zone is deleted.
 */
class TileStore(val file: File, private val format: String) {

    private val db: SQLiteDatabase by lazy {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY).apply {
            // Before any table: only a new, empty file takes it.
            execSQL("PRAGMA auto_vacuum = FULL")
            enableWriteAheadLogging()
            execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
            execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, " +
                    "tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL, PRIMARY KEY (zoom_level, tile_column, tile_row))",
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS zone_tiles (zone_id INTEGER NOT NULL, zoom_level INTEGER NOT NULL, " +
                    "tile_column INTEGER NOT NULL, tile_row INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (zone_id, zoom_level, tile_column, tile_row)) WITHOUT ROWID",
            )
            execSQL("CREATE INDEX IF NOT EXISTS zone_tiles_tile ON zone_tiles (zoom_level, tile_column, tile_row)")
        }
    }

    /** The URL MapLibre opens; only valid once the file exists. */
    val url: String get() = "mbtiles://${file.absolutePath}"

    @Synchronized
    fun setMetadata(minZoom: Int, maxZoom: Int, attribution: String?) = db.inTransaction {
        mapOf("name" to file.nameWithoutExtension, "format" to format, "minzoom" to "$minZoom", "maxzoom" to "$maxZoom", "attribution" to attribution)
            .forEach { (k, v) ->
                if (v == null) {
                    delete("metadata", "name = ?", arrayOf(k))
                } else {
                    insertWithOnConflict("metadata", null, ContentValues().apply { put("name", k); put("value", v) }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
    }

    /** Records the tiles [zoneId] needs (again: those already known keep their state). */
    @Synchronized
    fun plan(zoneId: Long, keys: Collection<TileKey>) = db.inTransaction {
        val stmt = compileStatement("INSERT OR IGNORE INTO zone_tiles (zone_id, zoom_level, tile_column, tile_row) VALUES (?, ?, ?, ?)")
        for (k in keys) {
            stmt.bindLong(1, zoneId)
            stmt.bindLong(2, k.z.toLong())
            stmt.bindLong(3, k.x.toLong())
            stmt.bindLong(4, k.tmsY.toLong())
            stmt.executeInsert()
        }
    }

    /** Marks every tile of [zoneId] to download again: an update to a newer pack. */
    @Synchronized
    fun restart(zoneId: Long) {
        if (file.exists()) db.execSQL("UPDATE zone_tiles SET done = 0 WHERE zone_id = ?", arrayOf(zoneId))
    }

    /** The tiles of [zoneId] not downloaded yet. */
    @Synchronized
    fun pending(zoneId: Long): List<TileKey> =
        db.rawQuery("SELECT zoom_level, tile_column, tile_row FROM zone_tiles WHERE zone_id = ? AND done = 0", arrayOf("$zoneId")).use { c ->
            List(c.count) {
                c.moveToNext()
                val z = c.getInt(0)
                TileKey(z, c.getInt(1), (1 shl z) - 1 - c.getInt(2))
            }
        }

    /** How many of [zoneId]'s tiles are done, out of how many. */
    @Synchronized
    fun progress(zoneId: Long): Pair<Int, Int> =
        db.rawQuery("SELECT COALESCE(SUM(done), 0), COUNT(*) FROM zone_tiles WHERE zone_id = ?", arrayOf("$zoneId")).use { c ->
            c.moveToFirst()
            c.getInt(0) to c.getInt(1)
        }

    /** Stores downloaded tiles and ticks them off for [zoneId], in one transaction. */
    @Synchronized
    fun put(zoneId: Long, tiles: Map<TileKey, ByteArray>, missing: Collection<TileKey> = emptyList()) = db.inTransaction {
        val insert = compileStatement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)")
        val done = compileStatement("UPDATE zone_tiles SET done = 1 WHERE zone_id = ? AND zoom_level = ? AND tile_column = ? AND tile_row = ?")
        for ((k, data) in tiles) {
            insert.bindLong(1, k.z.toLong())
            insert.bindLong(2, k.x.toLong())
            insert.bindLong(3, k.tmsY.toLong())
            insert.bindBlob(4, data)
            insert.executeInsert()
        }
        // Tiles the source does not have (open sea, outside the region) are done too: nothing to fetch.
        for (k in tiles.keys + missing) {
            done.bindLong(1, zoneId)
            done.bindLong(2, k.z.toLong())
            done.bindLong(3, k.x.toLong())
            done.bindLong(4, k.tmsY.toLong())
            done.executeUpdateDelete()
        }
    }

    /** Bytes of the tiles [zoneId] uses (shared tiles count for every zone that uses them). */
    @Synchronized
    fun bytes(zoneId: Long): Long =
        db.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(t.tile_data)), 0) FROM zone_tiles z JOIN tiles t " +
                "ON t.zoom_level = z.zoom_level AND t.tile_column = z.tile_column AND t.tile_row = z.tile_row WHERE z.zone_id = ?",
            arrayOf("$zoneId"),
        ).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    /** Forgets [zoneId] and deletes the tiles no other zone needs. */
    @Synchronized
    fun delete(zoneId: Long) {
        db.inTransaction {
            execSQL(
                "DELETE FROM tiles WHERE (zoom_level, tile_column, tile_row) IN " +
                    "(SELECT zoom_level, tile_column, tile_row FROM zone_tiles WHERE zone_id = ?1) " +
                    "AND NOT EXISTS (SELECT 1 FROM zone_tiles o WHERE o.zone_id != ?1 AND o.zoom_level = tiles.zoom_level " +
                    "AND o.tile_column = tiles.tile_column AND o.tile_row = tiles.tile_row)",
                arrayOf(zoneId),
            )
            execSQL("DELETE FROM zone_tiles WHERE zone_id = ?", arrayOf(zoneId))
        }
        // Fold the write-ahead log back so the freed pages leave the disk now.
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    /** One stored tile (XYZ [y]), as stored (possibly gzipped); null when absent or no store yet. */
    @Synchronized
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (!file.exists()) return null
        val tmsY = (1 shl z) - 1 - y
        return db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
            arrayOf(z.toString(), x.toString(), tmsY.toString()),
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    /** True once at least one tile is stored. */
    @Synchronized
    fun hasTiles(): Boolean = file.exists() && db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null).use { it.moveToFirst() }

    /** Size on disk, write-ahead log included. */
    fun sizeOnDisk(): Long = listOf(file, File(file.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            val r = block()
            setTransactionSuccessful()
            return r
        } finally {
            endTransaction()
        }
    }
}
