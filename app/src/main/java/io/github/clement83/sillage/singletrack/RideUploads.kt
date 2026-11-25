package io.github.clement83.sillage.singletrack

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.clement83.sillage.R
import io.github.clement83.sillage.SillageApp
import io.github.clement83.sillage.data.OutingDao
import io.github.clement83.sillage.data.RecordingDao
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.UploadState
import io.github.clement83.sillage.export.ExportTrack
import io.github.clement83.sillage.export.ExportWaypoint
import io.github.clement83.sillage.export.TrackExport
import io.github.clement83.sillage.geo.CleanTrack
import io.github.clement83.sillage.geo.TrailSnapper
import io.github.clement83.sillage.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends a ride to its singletrack outing (#1731), or makes a ride ridden off any outing a private
 * solo outing (#1766), through WorkManager: it waits for the network, survives the app being
 * closed, and retries. The state shown to the user is in the recording row.
 */
class RideUploads(
    private val context: Context,
    private val recordings: RecordingDao,
    private val outings: OutingDao,
    private val accounts: AccountStore,
    private val repository: OutingRepository,
    private val client: SingletrackClient,
    private val settings: SettingsRepository,
    private val trails: () -> TrailSnapper,
) {
    private val work get() = WorkManager.getInstance(context)

    /** How the ride would be sent, or null when it cannot be (no outing, no personal link, no account). */
    suspend fun target(recording: RecordingEntity): RideTarget? {
        val outing = recording.outingId?.let { outings.get(it) } ?: return null
        outing.inviteToken?.let { return RideTarget.Guest(outing.server, it) }
        val account = accounts.current()?.takeIf { it.server == outing.server } ?: return null
        return RideTarget.Account(account.server, account.token, outing.remoteId)
    }

    /** At the end of a ride on an outing: sent without asking when an account is paired, after [AUTO_DELAY_MS] to allow cancelling. */
    suspend fun autoSend(recordingId: Long) {
        val recording = recordings.get(recordingId) ?: return
        val outing = recording.outingId?.let { outings.get(it) } ?: return
        if (accounts.current()?.server != outing.server || target(recording) == null) return
        send(recordingId, AUTO_DELAY_MS)
    }

    /** Joins a ride made off any outing to [outingId] (#1777), then sends it there like a ride on that outing. */
    suspend fun joinOuting(recordingId: Long, outingId: Long) {
        recordings.setOuting(recordingId, outingId)
        send(recordingId)
    }

    /** Makes the ride a private solo outing of the paired account (#1766), as soon as the network is there. */
    suspend fun makeOuting(recordingId: Long) {
        recordings.setClientIdIfAbsent(recordingId, UUID.randomUUID().toString())
        send(recordingId)
    }

    /** Queues the upload (or a new attempt); it starts as soon as the network is there. */
    suspend fun send(recordingId: Long, delayMs: Long = 0) {
        recordings.setUpload(recordingId, UploadState.QUEUED.name)
        val request = OneTimeWorkRequestBuilder<RideUploadWorker>()
            .setInputData(workDataOf(KEY_RECORDING to recordingId))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(workName(recordingId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels a queued upload that has not left yet. */
    suspend fun cancel(recordingId: Long) {
        work.cancelUniqueWork(workName(recordingId))
        recordings.setUpload(recordingId, UploadState.CANCELLED.name)
    }

    /** One attempt, run by [RideUploadWorker]. True when done (sent or failed for good), false to retry. */
    internal suspend fun attempt(recordingId: Long): Boolean {
        val recording = recordings.get(recordingId) ?: return true
        if (recording.uploadState != UploadState.QUEUED) return true
        if (recording.outingId == null) return attemptSolo(recording)
        val target = target(recording) ?: return fail(recordingId, ERROR_NO_ACCESS)
        val gpx = gpx(recording).toByteArray()
        val name = TrackExport.fileName(recording.startedAt, recording.title, "gpx")
        return when (val r = client.uploadRide(target, gpx, name)) {
            is ApiResult.Ok -> sent(recordingId)
            ApiResult.NotFound -> fail(recordingId, ERROR_NOT_FOUND)
            ApiResult.Unauthorized -> {
                if (target is RideTarget.Account) repository.onUnauthorized()
                fail(recordingId, ERROR_UNAUTHORIZED)
            }
            is ApiResult.Failed -> if (r.status >= 500) retry(recordingId) else fail(recordingId, r.error ?: "http_${r.status}")
            ApiResult.TooManyRequests, is ApiResult.Offline -> retry(recordingId)
        }
    }

    /** The solo outing (#1766): created (or found again by its client id), then read back and linked to the ride. */
    private suspend fun attemptSolo(recording: RecordingEntity): Boolean {
        val id = recording.id
        val account = accounts.current() ?: return fail(id, ERROR_NO_ACCESS)
        val clientId = recording.clientId ?: return fail(id, ERROR_NO_ACCESS)
        val title = recording.title
            ?: context.getString(R.string.solo_title, DateFormat.getDateInstance(DateFormat.LONG).format(Date(recording.startedAt)))
        val r = client.soloOuting(
            account.server,
            account.token,
            gpx(recording).toByteArray(),
            TrackExport.fileName(recording.startedAt, recording.title, "gpx"),
            clientId,
            title,
            SoloOutings.startedAt(recording.startedAt),
        )
        return when (r) {
            // Read back so that the ride shows its outing; a new attempt is safe, the client id gives the same one.
            is ApiResult.Ok -> when (val o = repository.fetchOwn(account.server, account.token, r.value.slug)) {
                is ApiResult.Ok -> {
                    recordings.setOuting(id, o.value)
                    sent(id)
                }
                ApiResult.TooManyRequests, is ApiResult.Offline -> retry(id)
                is ApiResult.Failed -> if (o.status >= 500) retry(id) else sent(id)
                ApiResult.Unauthorized, ApiResult.NotFound -> sent(id)
            }
            ApiResult.NotFound -> fail(id, ERROR_SERVER_TOO_OLD)
            ApiResult.Unauthorized -> {
                repository.onUnauthorized()
                fail(id, ERROR_UNAUTHORIZED)
            }
            is ApiResult.Failed -> if (r.status >= 500) retry(id) else fail(id, r.error ?: "http_${r.status}")
            ApiResult.TooManyRequests, is ApiResult.Offline -> retry(id)
        }
    }

    private suspend fun sent(recordingId: Long): Boolean {
        recordings.setUpload(recordingId, UploadState.SENT.name, uploadedAt = System.currentTimeMillis())
        return true
    }

    private suspend fun fail(recordingId: Long, error: String): Boolean {
        recordings.setUpload(recordingId, UploadState.FAILED.name, error)
        return true
    }

    private suspend fun retry(recordingId: Long): Boolean {
        recordings.setUpload(recordingId, UploadState.QUEUED.name, ERROR_OFFLINE)
        return false
    }

    private suspend fun gpx(recording: RecordingEntity): String {
        val raw = recordings.points(recording.id).groupBy { it.segment }.toSortedMap().values
            .map { seg -> seg.map { it.toFix() } }
        val segments = if (settings.smoothTrack.first()) CleanTrack(trails).feed(raw) else raw
        val defaultName = context.getString(R.string.key_moment_default_name)
        val outingTitle = recording.outingId?.let { outings.get(it) }?.title
        return TrackExport.gpx(
            ExportTrack(
                name = recording.title ?: outingTitle ?: context.getString(R.string.app_name),
                startedAt = recording.startedAt,
                segments = segments,
                waypoints = recordings.keyMoments(recording.id).mapIndexed { i, m ->
                    ExportWaypoint(m.time, m.lat, m.lon, m.alt, m.label ?: defaultName.format(i + 1))
                },
            ),
        )
    }

    companion object {
        /** Time left to cancel an automatic upload. */
        const val AUTO_DELAY_MS = 60_000L
        internal const val KEY_RECORDING = "recording_id"

        const val ERROR_OFFLINE = "offline"
        const val ERROR_NOT_FOUND = "not_found"
        const val ERROR_UNAUTHORIZED = "unauthorized"
        const val ERROR_NO_ACCESS = "no_access"

        /** The server has no `POST /api/app/rides` (singletrack before v0.10.0). */
        const val ERROR_SERVER_TOO_OLD = "server_too_old"

        private fun workName(recordingId: Long) = "ride-upload-$recordingId"
    }
}

class RideUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(RideUploads.KEY_RECORDING, -1)
        if (id < 0) return Result.failure()
        val rides = (applicationContext as SillageApp).container.rides
        return if (rides.attempt(id)) Result.success() else Result.retry()
    }
}
