package com.wiglywoo.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wiglywoo.MainActivity
import com.wiglywoo.R

/**
 * The "Mac is viewing your screen" notice. Shizuku mirroring only posts the
 * notification: capture runs in the Shizuku process, and a foreground service
 * cannot be started from the background on Android 12+. Basic mirroring is
 * started from the activity, so it can hold the mediaProjection service.
 */
class MirrorSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MirrorHost.stopFromPhone()
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notification(this))
        }
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        MirrorHost.startBasic(this, projection)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "wigly_mirror"
        const val ID = 4202
        const val ACTION_STOP = "com.wiglywoo.mirror.STOP"

        fun showWatching(context: Context) {
            ensureChannel(context)
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notification(context)) }
        }

        fun hideWatching(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(ID)
            context.stopService(Intent(context, MirrorSessionService::class.java))
        }

        fun startProjection(context: Context, code: Int, data: Intent) {
            val intent = Intent(context, MirrorSessionService::class.java)
                .putExtra("code", code)
                .putExtra("data", data)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Phone mirroring", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun notification(context: Context): Notification {
            val stop = PendingIntent.getService(
                context, 2, Intent(context, MirrorSessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.mirror_watching))
                .setContentText("Stop sharing any time")
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.mirror_stop), stop)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
