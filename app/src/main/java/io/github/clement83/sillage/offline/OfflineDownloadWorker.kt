package io.github.clement83.sillage.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.clement83.sillage.MainActivity
import io.github.clement83.sillage.R
import io.github.clement83.sillage.SillageApp

/** Downloads one offline zone (#1729), in the foreground with its progress in a notification. */
class OfflineDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val zoneId = inputData.getLong(KEY_ZONE, -1)
        if (zoneId < 0) return Result.failure()
        val maps = (applicationContext as SillageApp).container.offline
        createChannel(applicationContext)
        val finished = maps.run(zoneId) { name, done, total ->
            // Android may refuse a foreground service from the background: the download goes on anyway.
            runCatching { setForeground(foregroundInfo(zoneId, name, done, total)) }
        }
        return if (finished) Result.success() else Result.retry()
    }

    private fun foregroundInfo(zoneId: Long, name: String, done: Int, total: Int): ForegroundInfo {
        val id = NOTIFICATION_BASE + zoneId.toInt()
        val notification = notification(applicationContext, name, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val KEY_ZONE = "zone_id"
        private const val CHANNEL = "offline_maps"
        private const val NOTIFICATION_BASE = 1_000

        private fun createChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.offline_channel))
                    .setShowBadge(false)
                    .build(),
            )
        }

        private fun notification(context: Context, name: String, done: Int, total: Int): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val percent = if (total > 0) done * 100 / total else 0
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sillage)
                .setContentTitle(context.getString(R.string.offline_downloading, name))
                .setContentText("$percent %")
                .setProgress(100, percent, total == 0)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }
    }
}
