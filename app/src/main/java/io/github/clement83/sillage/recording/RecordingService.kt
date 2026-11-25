package io.github.clement83.sillage.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.clement83.sillage.SillageApp
import io.github.clement83.sillage.data.KeyMomentEntity
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import io.github.clement83.sillage.data.TrackPointEntity
import io.github.clement83.sillage.geo.Fix
import io.github.clement83.sillage.geo.FixFilter
import io.github.clement83.sillage.geo.OffTrackDetector
import io.github.clement83.sillage.geo.TrackStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Records the track: GPS fixes from [LocationManager] (no Play Services), filtered, written to Room
 * in batches every few seconds. Never touches the network.
 *
 * All state lives on the main thread; database writes are serialised by [writeLock] so a late batch
 * can never overwrite the final status.
 */
class RecordingService : LifecycleService() {

    private val container by lazy { (application as SillageApp).container }
    private val dao by lazy { container.database.recordings() }
    private val controller by lazy { container.recorder }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }

    private val filter = FixFilter()
    private var stats = TrackStats()
    private var recording: RecordingEntity? = null
    private var status: RecordingStatus? = null
    private var activeMsBase = 0L
    private var resumedAtElapsed: Long? = null
    private val segments = mutableListOf<MutableList<Fix>>()
    private var nextSegmentIndex = 0
    private var currentSegmentIndex = 0
    private var newSegmentPending = true
    private var pointCount = 0
    private var lastFix: Fix? = null
    private var locationEnabled = true
    private val keyMoments = mutableListOf<KeyMomentEntity>()
    /** Taps made before the first fix: placed at the next accepted fix, keeping the tap time. */
    private val pendingMomentTimes = mutableListOf<Long>()

    private val pending = mutableListOf<TrackPointEntity>()
    private val writeLock = Mutex()
    private var flushJob: Job? = null
    private var listening = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** Fixes arrive here, off the main thread: the sea-level altitude lookup reads a geoid model from disk. */
    private val locationExecutor = Executors.newSingleThreadExecutor()
    private val mainThread by lazy { ContextCompat.getMainExecutor(this) }

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            val seaLevelAltitude = seaLevelAltitude(location)
            mainThread.execute { onFix(location, seaLevelAltitude) }
        }
        override fun onProviderEnabled(provider: String) = mainThread.execute { onLocationAvailability() }
        override fun onProviderDisabled(provider: String) = mainThread.execute { onLocationAvailability() }
    }

    override fun onCreate() {
        super.onCreate()
        RecordingNotifications.createChannel(this)
        controller.update { it.copy(serviceRunning = true) }
        lifecycleScope.launch { container.settings.offTrackAlert.collect { offTrackAlert = it } }
        lifecycleScope.launch { container.settings.offTrackReminder.collect { offTrackReminder = it } }
        lifecycleScope.launch { container.settings.offTrackSound.collect { offTrackSound = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!promoteToForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch { startNew(intent.followedTrack(), intent.outing()) }
            ACTION_FOLLOW -> follow(intent.followedTrack(), intent.outing())
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> lifecycleScope.launch { stop() }
            ACTION_KEY_MOMENT -> keyMoment()
            // A null intent is a sticky restart after the system killed us: pick the recording up again.
            ACTION_RESUME_INTERRUPTED, null -> lifecycleScope.launch { restoreOpen(resumeRecording = intent != null) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocation()
        locationExecutor.shutdown()
        releaseWakeLock()
        controller.update { RecorderState(serviceRunning = false) }
        super.onDestroy()
    }

    // --- Commands -------------------------------------------------------------------------------

    private fun Intent.followedTrack(): Long? =
        getLongExtra(EXTRA_TRACK_ID, RecordingController.NO_TRACK).takeIf { it != RecordingController.NO_TRACK }

    private fun Intent.outing(): Long? =
        getLongExtra(EXTRA_OUTING_ID, RecordingController.NO_TRACK).takeIf { it != RecordingController.NO_TRACK }

    private suspend fun startNew(followTrackId: Long?, outingId: Long?) {
        if (recording != null) return follow(followTrackId, outingId)
        if (!hasLocationPermission()) return shutdown()
        val entity = RecordingEntity(startedAt = System.currentTimeMillis(), followedTrackId = followTrackId, outingId = outingId)
        recording = entity.copy(id = dao.insert(entity))
        loadFollowed(followTrackId)
        stats = TrackStats()
        segments.clear()
        nextSegmentIndex = 0
        pointCount = 0
        activeMsBase = 0
        keyMoments.clear()
        pendingMomentTimes.clear()
        beginRecording()
    }

    private suspend fun restoreOpen(resumeRecording: Boolean) {
        if (recording != null) {
            if (resumeRecording) resume()
            return
        }
        val open = dao.findOpen()
        if (open == null || !hasLocationPermission()) return shutdown()
        val points = dao.points(open.id)
        stats = TrackStats()
        segments.clear()
        var previousSegment: Int? = null
        for (point in points) {
            val fix = point.toFix()
            val isNew = point.segment != previousSegment
            if (isNew) segments.add(mutableListOf())
            stats.add(fix, newSegment = isNew)
            segments.last().add(fix)
            previousSegment = point.segment
        }
        nextSegmentIndex = (points.maxOfOrNull { it.segment } ?: -1) + 1
        pointCount = points.size
        lastFix = points.lastOrNull()?.toFix()
        activeMsBase = open.activeMs
        keyMoments.clear()
        keyMoments.addAll(dao.keyMoments(open.id))
        recording = open
        loadFollowed(open.followedTrackId)
        if (resumeRecording || open.status == RecordingStatus.RECORDING) {
            beginRecording()
        } else {
            status = RecordingStatus.PAUSED
            publish()
            startFlushLoop()
        }
    }

    private fun beginRecording() {
        status = RecordingStatus.RECORDING
        resumedAtElapsed = SystemClock.elapsedRealtime()
        newSegmentPending = true
        filter.reset()
        startLocation()
        acquireWakeLock()
        startFlushLoop()
        publish()
        lifecycleScope.launch { flush() }
    }

    /** [outingId]: the route followed is an outing's, so the ride belongs to it; null keeps the current one. */
    private fun follow(trackId: Long?, outingId: Long? = null) {
        val rec = recording ?: return
        recording = rec.copy(followedTrackId = trackId, outingId = outingId ?: rec.outingId)
        loadFollowed(trackId)
        publish()
        lifecycleScope.launch { flush() }
    }

    private fun pause() {
        if (status != RecordingStatus.RECORDING) return
        activeMsBase = activeMs()
        resumedAtElapsed = null
        status = RecordingStatus.PAUSED
        stopLocation()
        releaseWakeLock()
        publish()
        lifecycleScope.launch { flush() }
    }

    private fun resume() {
        if (status != RecordingStatus.PAUSED) return
        beginRecording()
    }

    private suspend fun stop() {
        val rec = recording ?: return shutdown()
        activeMsBase = activeMs()
        resumedAtElapsed = null
        stopLocation()
        releaseWakeLock()
        flushJob?.cancel()
        status = RecordingStatus.FINISHED
        lastFix?.let { fix -> pendingMomentTimes.toList().forEach { saveKeyMoment(it, fix) } }
        pendingMomentTimes.clear()
        flush(endedAt = lastFix?.time ?: System.currentTimeMillis())
        if (pointCount == 0) {
            dao.delete(rec.id)
        } else if (rec.outingId != null) {
            // Ridden on a singletrack outing: sent by itself with a paired account (#1731).
            container.appScope.launch { container.rides.autoSend(rec.id) }
        }
        recording = null
        shutdown()
    }

    private fun shutdown() {
        controller.update { RecorderState(serviceRunning = true) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Location -------------------------------------------------------------------------------

    /**
     * Altitude above sea level, as maps and GPX readers expect. Android reports the height above the
     * WGS84 ellipsoid (about 50 m more in France); AndroidX converts it with a bundled geoid model.
     */
    private fun seaLevelAltitude(location: Location): Double? {
        if (!location.hasAltitude()) return null
        return try {
            AltitudeConverterCompat.addMslAltitudeToLocation(this, location)
            if (LocationCompat.hasMslAltitude(location)) LocationCompat.getMslAltitudeMeters(location) else location.altitude
        } catch (e: Exception) {
            Log.w(TAG, "Sea-level altitude unavailable, keeping the GPS altitude", e)
            location.altitude
        }
    }

    private fun onFix(location: Location, altitude: Double?) {
        if (status != RecordingStatus.RECORDING) return
        val rec = recording ?: return
        val fix = Fix(
            time = location.time,
            lat = location.latitude,
            lon = location.longitude,
            alt = altitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            speed = if (location.hasSpeed()) location.speed else null,
        )
        if (!filter.accept(fix)) return
        val isNew = newSegmentPending
        if (isNew) {
            currentSegmentIndex = nextSegmentIndex++
            segments.add(mutableListOf())
            newSegmentPending = false
        }
        stats.add(fix, newSegment = isNew)
        segments.last().add(fix)
        pending.add(TrackPointEntity.of(rec.id, currentSegmentIndex, fix))
        pointCount++
        lastFix = fix
        if (pendingMomentTimes.isNotEmpty()) {
            pendingMomentTimes.toList().forEach { lifecycleScope.launch { saveKeyMoment(it, fix) } }
            pendingMomentTimes.clear()
        }
        publish(notify = checkOffTrack(fix))
    }

    // --- Off the followed track (#1797) ---------------------------------------------------------

    // Set on the main thread, read where the fixes arrive.
    @Volatile private var offTrack: OffTrackDetector? = null
    @Volatile private var offTrackM: Double? = null
    @Volatile private var offTrackAlert = true
    @Volatile private var offTrackReminder = true
    @Volatile private var offTrackSound = false

    /** Watches the followed track, or none. */
    private fun loadFollowed(trackId: Long?) {
        offTrack = null
        offTrackM = null
        if (trackId == null) return
        lifecycleScope.launch {
            val segments = container.tracks.geometry(trackId).segments.filter { it.size >= 2 }
            if (recording?.followedTrackId == trackId && segments.isNotEmpty()) offTrack = OffTrackDetector(segments)
        }
    }

    /** Alerts when I leave the followed track. True when the notification text must change. */
    private fun checkOffTrack(fix: Fix): Boolean {
        val detector = offTrack ?: return false
        val status = detector.update(fix.time, fix.lat, fix.lon, reminders = offTrackReminder)
        val before = offTrackM
        offTrackM = status.distanceM?.takeIf { status.offTrack }
        if (offTrackAlert && (status.event == OffTrackDetector.Event.LEFT || status.event == OffTrackDetector.Event.REMIND)) alertOffTrack()
        val now = offTrackM
        return (before == null) != (now == null) || (before != null && now != null && abs(now - before) >= NOTIFY_STEP_M)
    }

    /** Three short pulses, felt in a pocket, screen off; a beep if asked for. */
    private fun alertOffTrack() {
        getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200, 150, 200), -1),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
        if (offTrackSound) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                lifecycleScope.launch {
                    delay(600)
                    tone.release()
                }
            }
        }
    }

    // --- Key moments ----------------------------------------------------------------------------

    /** One tap = one waypoint at my position, now. Works from the notification, phone locked. */
    private fun keyMoment() {
        if (recording == null || status == null || status == RecordingStatus.FINISHED) return
        vibrate()
        val now = System.currentTimeMillis()
        val fix = lastFix
        val fresh = fix != null && (status == RecordingStatus.PAUSED || now - fix.time <= FRESH_FIX_MS)
        if (fresh) {
            lifecycleScope.launch { saveKeyMoment(now, fix!!) }
        } else {
            pendingMomentTimes.add(now)
            publish()
        }
    }

    private suspend fun saveKeyMoment(time: Long, fix: Fix) {
        val rec = recording ?: return
        val moment = KeyMomentEntity(recordingId = rec.id, time = time, lat = fix.lat, lon = fix.lon, alt = fix.alt)
        val id = writeLock.withLock { dao.insertKeyMoment(moment) }
        keyMoments.add(moment.copy(id = id))
        publish()
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun onLocationAvailability() {
        locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (listening || !hasLocationPermission()) return
        val provider = when {
            locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val request = LocationRequestCompat.Builder(1000L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, locationExecutor, listener)
        listening = true
        locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
    }

    @SuppressLint("MissingPermission") // Removing updates needs no permission; revoked = nothing to remove.
    private fun stopLocation() {
        if (!listening) return
        try {
            LocationManagerCompat.removeUpdates(locationManager, listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked while recording", e)
        }
        listening = false
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // --- Persistence ----------------------------------------------------------------------------

    private fun startFlushLoop() {
        if (flushJob?.isActive == true) return
        flushJob = lifecycleScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
                publish()
            }
        }
    }

    private suspend fun flush(endedAt: Long? = null) {
        val rec = recording ?: return
        val batch = pending.toList()
        pending.clear()
        val snapshot = rec.copy(
            status = status ?: RecordingStatus.RECORDING,
            endedAt = endedAt,
            activeMs = activeMs(),
            distanceM = stats.distanceM,
            elevationGainM = stats.elevationGainM,
            pointCount = pointCount,
        )
        try {
            writeLock.withLock { dao.appendPoints(batch, snapshot) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save ${batch.size} points, will retry", e)
            pending.addAll(0, batch)
        }
    }

    // --- Foreground, notification, state ---------------------------------------------------------

    private fun promoteToForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, RecordingNotifications.ID, RecordingNotifications.build(this, currentState()), type)
        true
    } catch (e: Exception) {
        // Android 14+ refuses a location foreground service without the permission.
        Log.e(TAG, "Cannot start the recording in the foreground", e)
        stopSelf()
        false
    }

    private fun activeMs(): Long = activeMsBase + (resumedAtElapsed?.let { SystemClock.elapsedRealtime() - it } ?: 0)

    private fun currentState() = RecorderState(
        serviceRunning = true,
        recordingId = recording?.id,
        status = status?.takeIf { it != RecordingStatus.FINISHED },
        startedAt = recording?.startedAt ?: 0,
        activeMsBase = activeMsBase,
        resumedAtElapsed = resumedAtElapsed,
        distanceM = stats.distanceM,
        elevationGainM = stats.elevationGainM,
        segments = segments.map { it.toList() },
        lastFix = lastFix,
        locationEnabled = locationEnabled,
        keyMoments = keyMoments.toList(),
        pendingKeyMoments = pendingMomentTimes.size,
        followedTrackId = recording?.followedTrackId,
        offTrackM = offTrackM,
    )

    @SuppressLint("MissingPermission")
    private fun publish(notify: Boolean = true) {
        val state = currentState()
        controller.update { state }
        if (notify && state.status != null && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(RecordingNotifications.ID, RecordingNotifications.build(this, state))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sillage:recording")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "io.github.clement83.sillage.action.START"
        const val ACTION_PAUSE = "io.github.clement83.sillage.action.PAUSE"
        const val ACTION_RESUME = "io.github.clement83.sillage.action.RESUME"
        const val ACTION_STOP = "io.github.clement83.sillage.action.STOP"
        const val ACTION_RESUME_INTERRUPTED = "io.github.clement83.sillage.action.RESUME_INTERRUPTED"
        const val ACTION_KEY_MOMENT = "io.github.clement83.sillage.action.KEY_MOMENT"
        const val ACTION_FOLLOW = "io.github.clement83.sillage.action.FOLLOW"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_OUTING_ID = "outing_id"

        private const val TAG = "RecordingService"
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val FRESH_FIX_MS = 30_000L
        private const val VIBRATION_MS = 80L

        /** The notification follows the distance off the track by steps of this many metres. */
        private const val NOTIFY_STEP_M = 10.0
    }
}
