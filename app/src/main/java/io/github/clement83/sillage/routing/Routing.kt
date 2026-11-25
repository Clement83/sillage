package io.github.clement83.sillage.routing

import android.content.Context
import io.github.clement83.sillage.geo.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Routes from the map (#1733), on the phone: BRouter with the singletrack profile shipped in the
 * assets and the segments downloaded with the offline maps. One route at a time, off the main thread.
 */
class Routing(private val context: Context, private val segmentsDir: File) {
    private val profileDir = File(context.noBackupFilesDir, "brouter")
    private val mutex = Mutex()
    private var profileReady = false

    suspend fun route(from: GeoPoint, to: GeoPoint): RouteResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            runCatching {
                installProfile()
                Router(segmentsDir, File(profileDir, PROFILE)).route(from, to)
            }.getOrElse { RouteResult.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** The profile is copied from the assets once per run of the app, so an update brings the new one. */
    private fun installProfile() {
        if (profileReady) return
        profileDir.mkdirs()
        for (name in listOf(LOOKUPS, PROFILE)) {
            context.assets.open("brouter/$name").use { input -> File(profileDir, name).outputStream().use { input.copyTo(it) } }
        }
        profileReady = true
    }

    private companion object {
        const val LOOKUPS = "lookups.dat"
        const val PROFILE = "singletrack.brf"
    }
}
