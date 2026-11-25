package io.github.clement83.sillage.recording

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.clement83.sillage.MainActivity
import io.github.clement83.sillage.R
import io.github.clement83.sillage.data.RecordingStatus
import io.github.clement83.sillage.ui.Format

object RecordingNotifications {
    const val ID = 1
    private const val CHANNEL = "recording"

    fun createChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_recording))
                .setShowBadge(false)
                .build(),
        )
    }

    fun build(context: Context, state: RecorderState): Notification {
        val paused = state.status == RecordingStatus.PAUSED
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = state.offTrackM?.let { context.getString(R.string.off_track, Format.distance(it)) }
            ?: context.getString(
                R.string.notification_recording_text,
                Format.distance(state.distanceM),
                Format.elevation(state.elevationGainM),
            )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sillage)
            .setContentTitle(
                context.getString(
                    when {
                        paused -> R.string.notification_recording_paused
                        !state.locationEnabled -> R.string.notification_recording_no_location
                        else -> R.string.notification_recording_title
                    },
                ),
            )
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (paused || state.status == null) {
            builder.setShowWhen(false).setUsesChronometer(false)
        } else {
            builder.setShowWhen(true).setUsesChronometer(true).setWhen(System.currentTimeMillis() - state.activeMs())
        }

        if (state.status != null) {
            builder.addAction(
                0,
                context.getString(R.string.key_moment),
                serviceIntent(context, RecordingService.ACTION_KEY_MOMENT, requestCode = 2),
            )
            val (action, label) = if (paused) {
                RecordingService.ACTION_RESUME to R.string.recording_resume
            } else {
                RecordingService.ACTION_PAUSE to R.string.recording_pause
            }
            builder.addAction(0, context.getString(label), serviceIntent(context, action, requestCode = 1))
        }
        return builder.build()
    }

    fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context,
        requestCode,
        Intent(context, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
