package io.github.clement83.sillage.recording

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.clement83.sillage.data.KeyMomentEntity
import io.github.clement83.sillage.data.RecordingDao
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import io.github.clement83.sillage.geo.Fix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the UI shows of the recording in progress. Written by [RecordingService] only. */
data class RecorderState(
    /** The service process is alive. False with an open recording in the database = interrupted. */
    val serviceRunning: Boolean = false,
    val recordingId: Long? = null,
    /** Null when idle. */
    val status: RecordingStatus? = null,
    val startedAt: Long = 0,
    /** Active time accumulated up to the last pause or resume. */
    val activeMsBase: Long = 0,
    /** [SystemClock.elapsedRealtime] of the last start or resume, null while paused. */
    val resumedAtElapsed: Long? = null,
    val distanceM: Double = 0.0,
    val elevationGainM: Double = 0.0,
    /** The track so far, one list per segment. */
    val segments: List<List<Fix>> = emptyList(),
    val lastFix: Fix? = null,
    val locationEnabled: Boolean = true,
    val keyMoments: List<KeyMomentEntity> = emptyList(),
    /** Key moments tapped before the first fix, waiting for a position. */
    val pendingKeyMoments: Int = 0,
    /** Imported track shown while recording (#1742), if any. */
    val followedTrackId: Long? = null,
) {
    val keyMomentCount: Int get() = keyMoments.size + pendingKeyMoments

    val isActive: Boolean get() = status == RecordingStatus.RECORDING || status == RecordingStatus.PAUSED

    fun activeMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        activeMsBase + (resumedAtElapsed?.let { nowElapsed - it } ?: 0)
}

class RecordingController(private val context: Context, private val dao: RecordingDao) {

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** The recording not yet finished in the database (in progress, or left by a killed process). */
    val openRecording: Flow<RecordingEntity?> = dao.observeOpen()

    internal fun update(transform: (RecorderState) -> RecorderState) = _state.update(transform)

    /** Starts a recording, optionally following an imported track, or a singletrack outing's route ([outingId]). */
    fun start(followTrackId: Long? = null, outingId: Long? = null) = send(RecordingService.ACTION_START, foreground = true) {
        if (followTrackId != null) putExtra(RecordingService.EXTRA_TRACK_ID, followTrackId)
        if (outingId != null) putExtra(RecordingService.EXTRA_OUTING_ID, outingId)
    }

    /** Follows another imported track (or none) during the recording in progress; [outingId] links the ride to that outing. */
    fun follow(trackId: Long?, outingId: Long? = null) = send(RecordingService.ACTION_FOLLOW) {
        putExtra(RecordingService.EXTRA_TRACK_ID, trackId ?: NO_TRACK)
        if (outingId != null) putExtra(RecordingService.EXTRA_OUTING_ID, outingId)
    }
    fun pause() = send(RecordingService.ACTION_PAUSE)
    fun resume() = send(RecordingService.ACTION_RESUME)
    fun stop() = send(RecordingService.ACTION_STOP)
    fun keyMoment() = send(RecordingService.ACTION_KEY_MOMENT)

    /** Continue the recording a killed process or a reboot left open, as a new segment. */
    fun resumeInterrupted() = send(RecordingService.ACTION_RESUME_INTERRUPTED, foreground = true)

    /** Close the recording a killed process left open, at its last recorded point. */
    suspend fun finishInterrupted() {
        val open = dao.findOpen() ?: return
        if (open.pointCount == 0 && dao.lastPointTime(open.id) == null) {
            dao.delete(open.id)
        } else {
            val end = dao.lastPointTime(open.id) ?: open.startedAt
            dao.update(open.copy(status = RecordingStatus.FINISHED, endedAt = end))
        }
    }

    private fun send(action: String, foreground: Boolean = false, extras: Intent.() -> Unit = {}) {
        val intent = Intent(context, RecordingService::class.java).setAction(action).apply(extras)
        if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    companion object {
        const val NO_TRACK = -1L
    }
}
