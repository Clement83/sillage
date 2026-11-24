package io.github.clement83.sillage

import android.content.Context
import io.github.clement83.sillage.data.SillageDatabase
import io.github.clement83.sillage.importer.TrackLibrary
import io.github.clement83.sillage.recording.RecordingController
import io.github.clement83.sillage.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.clement83.sillage.singletrack.AccountStore
import io.github.clement83.sillage.singletrack.OutingRepository
import io.github.clement83.sillage.singletrack.RideUploads
import io.github.clement83.sillage.singletrack.SingletrackClient
import io.github.clement83.sillage.singletrack.SingletrackLink

/** Manual dependency injection: one instance of each long-lived object, owned by the application. */
class AppContainer(context: Context) {
    /** Work that must outlive a screen (an import started from a share). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(context)
    val database = SillageDatabase.create(context)
    val recorder = RecordingController(context.applicationContext, database.recordings())
    val tracks = TrackLibrary(context.applicationContext, database.tracks())

    // singletrack (Phase 2): optional, nothing above depends on it.
    val account = AccountStore(context)
    val singletrack = SingletrackClient(userAgent = "Sillage/${BuildConfig.VERSION_NAME} (Android)")
    val outings = OutingRepository(
        database.outings(),
        database.tracks(),
        account,
        singletrack,
        followedTrackId = { recorder.state.value.takeIf { it.isActive }?.followedTrackId },
    )
    val rides = RideUploads(context.applicationContext, database.recordings(), database.outings(), account, outings, singletrack)

    /** A singletrack link opened from outside (browser, message), waiting for the UI to open it. */
    val incomingLink = MutableStateFlow<SingletrackLink?>(null)
}
